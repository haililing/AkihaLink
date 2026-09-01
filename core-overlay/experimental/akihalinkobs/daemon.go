//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	netlink "github.com/sagernet/netlink"
	"golang.org/x/sys/unix"
)

type flowRecord struct {
	cookie           uint64
	id               string
	fingerprint      string
	addressFamily    string
	pathGeneration   uint64
	pmtu             int
	mss              int
	startedAt        time.Time
	lastSeen         time.Time
	closedAt         time.Time
	lastAckProgress  time.Time
	state            string
	established      bool
	blackholeMarked  bool
	rttMs            float64
	handshakeMs      float64
	sendCWND         uint32
	deliveryRateBps  uint64
	totalRetrans     uint32
	stallRetransBase uint32
	segmentsOut      uint32
	estimatedLoss    float64
	failure          string
	bytesAcked       uint64
	lastSample       time.Time
}

type daemon struct {
	mu                    sync.Mutex
	socketPath            string
	pinRoot               string
	configPath            string
	coreLogPath           string
	salt                  [32]byte
	corePID               int
	startedAt             time.Time
	bpf                   *bpfRuntime
	loaderMode            string
	attachMode            string
	eventMode             string
	degradedReason        string
	flows                 map[uint64]*flowRecord
	pendingBPF            map[uint64][]bpfEvent
	proxyTargets          map[string]struct{}
	currentFingerprint    string
	currentTargetTag      string
	completed             []*flowRecord
	failures              map[string]uint64
	paths                 map[string]*pathAccumulator
	routeGeneration       uint64
	defaultRouteMTU       int
	tcpMTUProbing         int
	perSocketPMTU         bool
	perSocketMSS          bool
	supervisor            *processSupervisor
	networkEvents         []NetworkEvent
	networkSequence       uint64
	tcpSampleWake         chan struct{}
	tcpSampleForce        chan struct{}
	tcpActiveSamples      uint64
	tcpIdleSamples        uint64
	tcpSamplingLeaseUntil time.Time
	tcpSampleGeneration   uint64
	tcpSampleDone         chan struct{}
	logMonitorMode        string
	networkPathSignature  string
	stop                  chan struct{}
	stopOnce              sync.Once
}

func RunDaemon(socketPath, pinRoot, configPath, coreLogPath string) error {
	return runDaemon(socketPath, pinRoot, configPath, coreLogPath, nil)
}

func runDaemon(
	socketPath, pinRoot, configPath, coreLogPath string,
	supervisor *processSupervisor,
) error {
	if os.Geteuid() != 0 {
		return errors.New("akihalink-daemon requires root")
	}
	if err := os.MkdirAll(filepath.Dir(socketPath), 0o700); err != nil {
		return err
	}
	_ = os.Remove(socketPath)
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		return err
	}
	if err = os.Chmod(socketPath, 0o600); err != nil {
		listener.Close()
		return err
	}

	state := &daemon{
		socketPath:      socketPath,
		pinRoot:         pinRoot,
		configPath:      configPath,
		coreLogPath:     coreLogPath,
		startedAt:       time.Now(),
		loaderMode:      "runtime_instruction_fallback",
		attachMode:      "legacy",
		eventMode:       "tcp_info",
		flows:           make(map[uint64]*flowRecord),
		pendingBPF:      make(map[uint64][]bpfEvent),
		proxyTargets:    make(map[string]struct{}),
		failures:        make(map[string]uint64),
		paths:           make(map[string]*pathAccumulator),
		routeGeneration: 1,
		tcpMTUProbing:   readTCPMTUProbing(),
		supervisor:      supervisor,
		tcpSampleWake:   make(chan struct{}, 1),
		tcpSampleForce:  make(chan struct{}, 1),
		tcpSampleDone:   make(chan struct{}),
		stop:            make(chan struct{}),
	}
	if _, err = rand.Read(state.salt[:]); err != nil {
		listener.Close()
		return fmt.Errorf("create anonymous flow salt: %w", err)
	}
	traceID := currentTraceID(filepath.Dir(socketPath))
	traceBegin("AKL/ebpf_load", traceID)
	runtime, loadErr := loadBPF(pinRoot)
	traceEnd(traceID)
	if loadErr == nil {
		state.bpf = runtime
		state.loaderMode = runtime.loaderMode
		state.attachMode = runtime.attachMode
		state.eventMode = "ring_buffer+tcp_info"
		traceInstant("AKL/ebpf_attach", traceID)
		go state.consumeRingBuffer()
	} else {
		state.degradedReason = safeReason(loadErr)
	}

	go state.sampleTCPInfo()
	go state.watchCoreFailures()
	go state.watchNetwork()
	if supervisor != nil {
		go supervisor.run(state)
	}
	traceInstant("AKL/observability_ready", traceID)

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		select {
		case <-signals:
			state.shutdown(false)
		case <-state.stop:
		}
		listener.Close()
	}()

	for {
		connection, acceptErr := listener.Accept()
		if acceptErr != nil {
			select {
			case <-state.stop:
				return nil
			default:
				return acceptErr
			}
		}
		go state.handle(connection)
	}
}

func SendCommand(socketPath string, request commandRequest) (commandResponse, error) {
	connection, err := net.DialTimeout("unix", socketPath, 2*time.Second)
	if err != nil {
		return commandResponse{}, err
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(4 * time.Second))
	if err = json.NewEncoder(connection).Encode(request); err != nil {
		return commandResponse{}, err
	}
	var response commandResponse
	if err = json.NewDecoder(io.LimitReader(connection, 2<<20)).Decode(&response); err != nil {
		return response, err
	}
	if !response.OK {
		return response, errors.New(response.Error)
	}
	return response, nil
}

func SendCommandForCLI(
	socketPath, command string,
	limit, corePID int,
	target, fingerprint string,
) (any, error) {
	response, err := SendCommand(socketPath, commandRequest{
		Command:     command,
		Limit:       limit,
		CorePID:     corePID,
		Target:      target,
		Fingerprint: fingerprint,
	})
	if err != nil {
		return nil, err
	}
	switch command {
	case "status", "clear":
		return response.Telemetry, nil
	case "network-events":
		return response.NetworkEvents, nil
	case "path-status":
		return response.PathStatus, nil
	case "probe":
		return response.Capabilities, nil
	case "runtime-status", "sampling-acquire", "sampling-renew", "sampling-release":
		return response.RuntimeStatus, nil
	case "snapshot":
		return response.Snapshot, nil
	default:
		return map[string]bool{"ok": true}, nil
	}
}

func (state *daemon) handle(connection net.Conn) {
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(5 * time.Second))
	var request commandRequest
	if err := json.NewDecoder(io.LimitReader(connection, 1<<20)).Decode(&request); err != nil {
		_ = json.NewEncoder(connection).Encode(commandResponse{Error: "invalid request"})
		return
	}
	response := commandResponse{OK: true}
	switch request.Command {
	case "status":
		summary := state.summary()
		response.Telemetry = &summary
	case "clear":
		state.clear()
		summary := state.summary()
		response.Telemetry = &summary
	case "network-events":
		response.NetworkEvents = state.recentNetworkEvents(request.Limit)
	case "path-status":
		status := state.pathStatus()
		response.PathStatus = &status
	case "runtime-status":
		status := state.runtimeStatus()
		response.RuntimeStatus = &status
	case "sampling-acquire", "sampling-renew", "sampling-release":
		status := state.updateTCPSampling(request.Command, time.Duration(request.Limit)*time.Millisecond)
		response.RuntimeStatus = &status
	case "snapshot":
		snapshot := state.observabilitySnapshot(request.Limit)
		response.Snapshot = &snapshot
	case "ready":
		if request.CorePID <= 0 || !processAlive(request.CorePID) {
			response.OK = false
			response.Error = "invalid core PID"
		} else {
			state.mu.Lock()
			state.corePID = request.CorePID
			state.mu.Unlock()
			if state.bpf != nil {
				_ = state.bpf.setCorePID(request.CorePID)
			}
			if err := state.refreshProxyTargets(); err != nil {
				state.mu.Lock()
				state.degradedReason = "proxy_target_unresolved"
				state.mu.Unlock()
			}
		}
	case "target":
		if err := state.refreshProxyTargets(request.Target, request.Fingerprint); err != nil {
			response.OK = false
			response.Error = "proxy target unavailable"
		}
	case "probe":
		response.Capabilities = Probe()
	case "stop":
		go state.shutdown(false)
	case "cleanup":
		go state.shutdown(true)
	default:
		response.OK = false
		response.Error = "unknown command"
	}
	_ = json.NewEncoder(connection).Encode(response)
}

func (state *daemon) observabilitySnapshot(limit int) ObservabilitySnapshot {
	result := ObservabilitySnapshot{
		PathStatus:    state.pathStatus(),
		NetworkEvents: []NetworkEvent{},
	}
	if limit > 0 {
		telemetry := state.summary()
		result.Telemetry = &telemetry
		result.NetworkEvents = state.recentNetworkEvents(limit)
	}
	return result
}

func (state *daemon) shutdown(cleanup bool) {
	state.stopOnce.Do(func() {
		if state.supervisor != nil {
			state.supervisor.stop()
		}
		close(state.stop)
		if state.bpf != nil {
			state.bpf.Close(cleanup)
		}
		_ = os.Remove(state.socketPath)
	})
}

func (state *daemon) consumeRingBuffer() {
	for {
		record, err := state.bpf.reader.Read()
		if err != nil {
			if errors.Is(err, os.ErrClosed) {
				return
			}
			state.mu.Lock()
			state.degradedReason = "ring buffer read failed"
			state.eventMode = "tcp_info"
			state.mu.Unlock()
			return
		}
		var event bpfEvent
		if len(record.RawSample) != binary.Size(event) ||
			binary.Read(bytes.NewReader(record.RawSample), binary.LittleEndian, &event) != nil ||
			event.ABIVersion != eventABIVersion {
			continue
		}
		state.applyEvent(event)
	}
}

func (state *daemon) applyEvent(event bpfEvent) {
	state.requestTCPSample()
	now := time.Now()
	state.mu.Lock()
	defer state.mu.Unlock()
	flow := state.flows[event.SocketCookie]
	if flow == nil {
		pending := state.pendingBPF[event.SocketCookie]
		if len(pending) < 4 {
			state.pendingBPF[event.SocketCookie] = append(pending, event)
		}
		if len(state.pendingBPF) > 4096 {
			state.pendingBPF = make(map[uint64][]bpfEvent)
		}
		return
	}
	state.applyEventLocked(flow, event, now)
}

func (state *daemon) applyEventLocked(flow *flowRecord, event bpfEvent, now time.Time) {
	flow.lastSeen = now
	if event.SRTTUS > 0 {
		flow.rttMs = float64(event.SRTTUS) / 1000
	}
	if event.HandshakeUS > 0 {
		flow.handshakeMs = float64(event.HandshakeUS) / 1000
	}
	flow.sendCWND = event.SendCWND
	flow.deliveryRateBps = event.DeliveryRate * 8
	if event.SegmentsOut >= flow.segmentsOut && event.TotalRetrans >= flow.totalRetrans {
		deltaSegments := event.SegmentsOut - flow.segmentsOut
		deltaRetrans := event.TotalRetrans - flow.totalRetrans
		flow.estimatedLoss = estimateLoss(deltaRetrans, deltaSegments)
	}
	flow.totalRetrans = event.TotalRetrans
	flow.segmentsOut = event.SegmentsOut
	switch event.EventType {
	case 1:
		flow.state = "connecting"
	case 2:
		flow.state = "established"
		flow.established = true
	case 4:
		flow.state = "retransmit"
	case 5:
		if event.State == netlink.TCP_CLOSE {
			state.completeLocked(flow, now)
		}
	}
}

func (state *daemon) sampleTCPInfo() {
	for {
		select {
		case <-state.tcpSampleWake:
		case <-state.tcpSampleForce:
		case <-state.stop:
			return
		}
		state.mu.Lock()
		leased := time.Now().Before(state.tcpSamplingLeaseUntil)
		state.mu.Unlock()
		if !leased {
			continue
		}
		active := state.sampleTCPInfoOnce()
		state.mu.Lock()
		if active {
			state.tcpActiveSamples++
		} else {
			state.tcpIdleSamples++
		}
		state.tcpSampleGeneration++
		close(state.tcpSampleDone)
		state.tcpSampleDone = make(chan struct{})
		leased = time.Now().Before(state.tcpSamplingLeaseUntil)
		state.mu.Unlock()
		if leased {
			timer := time.NewTimer(5 * time.Second)
			waiting := true
			for waiting {
				select {
				case <-timer.C:
					state.requestTCPSample()
					waiting = false
				case <-state.tcpSampleWake:
					// Passive events stay available but cannot accelerate an
					// expensive full-machine socket-diag scan.
				case <-state.tcpSampleForce:
					if !timer.Stop() {
						select {
						case <-timer.C:
						default:
						}
					}
					state.requestTCPSample()
					waiting = false
				case <-state.stop:
					if !timer.Stop() {
						<-timer.C
					}
					return
				}
			}
		}
	}
}

func (state *daemon) requestTCPSample() {
	select {
	case state.tcpSampleWake <- struct{}{}:
	default:
	}
}

func (state *daemon) requestForcedTCPSample() {
	select {
	case state.tcpSampleForce <- struct{}{}:
	default:
	}
}

func (state *daemon) updateTCPSampling(command string, wait time.Duration) RuntimeStatus {
	state.mu.Lock()
	done := state.tcpSampleDone
	switch command {
	case "sampling-acquire", "sampling-renew":
		state.tcpSamplingLeaseUntil = time.Now().Add(15 * time.Second)
	case "sampling-release":
		state.tcpSamplingLeaseUntil = time.Time{}
	}
	state.mu.Unlock()
	if command == "sampling-acquire" {
		state.requestForcedTCPSample()
		if wait > 0 {
			if wait > 2*time.Second {
				wait = 2 * time.Second
			}
			timer := time.NewTimer(wait)
			select {
			case <-done:
			case <-timer.C:
			}
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
		}
	}
	return state.runtimeStatus()
}

var socketInodePattern = regexp.MustCompile(`^socket:\[(\d+)\]$`)

func (state *daemon) sampleTCPInfoOnce() bool {
	state.mu.Lock()
	pid := state.corePID
	state.mu.Unlock()
	if pid <= 0 {
		return false
	}
	inodes := socketInodes(pid)
	if len(inodes) == 0 {
		return false
	}
	now := time.Now()
	seen := make(map[uint64]struct{})
	for _, family := range []uint8{unix.AF_INET, unix.AF_INET6} {
		responses, err := netlink.SocketDiagTCPInfo(family)
		if err != nil {
			continue
		}
		for _, response := range responses {
			if response == nil || response.InetDiagMsg == nil || response.TCPInfo == nil {
				continue
			}
			socket := response.InetDiagMsg
			if _, exists := inodes[uint64(socket.INode)]; !exists {
				continue
			}
			if !state.isProxyTarget(socket) {
				continue
			}
			cookie := uint64(socket.ID.Cookie[1])<<32 | uint64(socket.ID.Cookie[0])
			if cookie == 0 || cookie == math.MaxUint64 {
				cookie = uint64(socket.INode)
			}
			seen[cookie] = struct{}{}
			addressFamily := "ipv4"
			if family == unix.AF_INET6 {
				addressFamily = "ipv6"
			}
			state.applyTCPInfo(cookie, socket.State, response.TCPInfo, now, addressFamily)
		}
	}
	state.mu.Lock()
	for cookie, flow := range state.flows {
		if _, exists := seen[cookie]; !exists && now.Sub(flow.lastSeen) > 2*time.Second {
			state.completeLocked(flow, now)
		}
	}
	state.mu.Unlock()
	return len(seen) > 0
}

func (state *daemon) applyTCPInfo(
	cookie uint64,
	tcpState uint8,
	info *netlink.TCPInfo,
	now time.Time,
	addressFamily string,
) {
	state.mu.Lock()
	defer state.mu.Unlock()
	flow := state.ensureFlowLocked(cookie, now)
	if flow.addressFamily == "" {
		flow.addressFamily = addressFamily
	}
	if pending := state.pendingBPF[cookie]; len(pending) > 0 {
		for _, event := range pending {
			state.applyEventLocked(flow, event, now)
		}
		delete(state.pendingBPF, cookie)
	}
	flow.lastSeen = now
	if tcpState == netlink.TCP_ESTABLISHED &&
		flow.state == "connecting" &&
		flow.handshakeMs == 0 {
		flow.handshakeMs = float64(now.Sub(flow.startedAt).Microseconds()) / 1000
	}
	flow.state = tcpStateName(tcpState)
	if tcpState == netlink.TCP_ESTABLISHED {
		flow.established = true
	}
	flow.rttMs = float64(info.Rtt) / 1000
	flow.sendCWND = info.Snd_cwnd
	if flow.fingerprint != "" && flow.addressFamily != "" &&
		flow.pathGeneration == state.routeGeneration {
		path := state.ensurePathLocked(flow.fingerprint, flow.addressFamily)
		if path.PMTU == 0 && info.Pmtu > 0 {
			path.PMTU = clampPMTU(int(info.Pmtu), flow.addressFamily)
		}
		if path.MSS == 0 && info.Snd_mss > 0 {
			path.MSS = int(info.Snd_mss)
		}
		flow.pmtu = int(info.Pmtu)
		if flow.mss == 0 {
			flow.mss = int(info.Snd_mss)
		}
		if path.ReprobeMSS > 0 && flow.mss == path.ReprobeMSS &&
			time.Duration(now.Sub(flow.startedAt)) >= pathValidationDuration &&
			info.Bytes_acked >= pathValidationBytes &&
			estimateLoss(info.Total_retrans, info.Segs_out) < 0.5 {
			path.MSS = path.ReprobeMSS
			path.PMTU = path.MSS + headerSize(path.AddressFamily)
			path.ReprobeAfter = now.Add(pathReprobeInterval)
			path.ReprobeMSS = nextHigherMSS(path.PMTU, path.AddressFamily)
			path.UpdatedAt = now
		}
	}
	if info.Delivery_rate > 0 {
		flow.deliveryRateBps = info.Delivery_rate * 8
	} else if !flow.lastSample.IsZero() && info.Bytes_acked >= flow.bytesAcked {
		elapsed := now.Sub(flow.lastSample).Seconds()
		if elapsed > 0 {
			flow.deliveryRateBps = uint64(float64(info.Bytes_acked-flow.bytesAcked) * 8 / elapsed)
		}
	}
	if info.Segs_out >= flow.segmentsOut && info.Total_retrans >= flow.totalRetrans {
		flow.estimatedLoss = estimateLoss(
			info.Total_retrans-flow.totalRetrans,
			info.Segs_out-flow.segmentsOut,
		)
	}
	if flow.lastSample.IsZero() {
		flow.lastAckProgress = now
		flow.stallRetransBase = info.Total_retrans
	} else if info.Bytes_acked > flow.bytesAcked {
		flow.lastAckProgress = now
		flow.stallRetransBase = info.Total_retrans
	} else if flow.established && !flow.blackholeMarked &&
		info.Bytes_sent > info.Bytes_acked &&
		info.Total_retrans >= flow.stallRetransBase+3 {
		stalledFor := now.Sub(flow.lastAckProgress)
		required := max(3*time.Duration(info.Rto)*time.Microsecond, 3*time.Second)
		if stalledFor >= required && flow.pathGeneration == state.routeGeneration &&
			flow.fingerprint != "" && flow.addressFamily != "" {
			path := state.ensurePathLocked(flow.fingerprint, flow.addressFamily)
			state.lowerPathLocked(path)
			flow.blackholeMarked = true
		}
	}
	flow.totalRetrans = info.Total_retrans
	flow.segmentsOut = info.Segs_out
	flow.bytesAcked = info.Bytes_acked
	flow.lastSample = now
}

func socketInodes(pid int) map[uint64]struct{} {
	result := make(map[uint64]struct{})
	entries, err := os.ReadDir(fmt.Sprintf("/proc/%d/fd", pid))
	if err != nil {
		return result
	}
	for _, entry := range entries {
		target, err := os.Readlink(fmt.Sprintf("/proc/%d/fd/%s", pid, entry.Name()))
		if err != nil {
			continue
		}
		match := socketInodePattern.FindStringSubmatch(target)
		if len(match) != 2 {
			continue
		}
		inode, err := strconv.ParseUint(match[1], 10, 64)
		if err == nil {
			result[inode] = struct{}{}
		}
	}
	return result
}

func (state *daemon) ensureFlowLocked(cookie uint64, now time.Time) *flowRecord {
	if flow := state.flows[cookie]; flow != nil {
		return flow
	}
	hash := sha256.Sum256(append(state.salt[:], []byte(strconv.FormatUint(cookie, 10))...))
	flow := &flowRecord{
		cookie:          cookie,
		id:              hex.EncodeToString(hash[:6]),
		startedAt:       now,
		lastSeen:        now,
		lastAckProgress: now,
		fingerprint:     state.currentFingerprint,
		pathGeneration:  state.routeGeneration,
		state:           "new",
	}
	state.flows[cookie] = flow
	return flow
}

func (state *daemon) completeLocked(flow *flowRecord, now time.Time) {
	if _, exists := state.flows[flow.cookie]; !exists {
		return
	}
	delete(state.flows, flow.cookie)
	flow.closedAt = now
	state.completed = append(state.completed, flow)
	if len(state.completed) > 1000 {
		state.completed = state.completed[len(state.completed)-1000:]
	}
}

func (state *daemon) summary() TelemetrySummary {
	now := time.Now()
	state.mu.Lock()
	state.pruneCompletedLocked(now)
	cutoff := now.Add(-windowDuration)
	all := make([]flowRecord, 0, len(state.flows)+len(state.completed))
	for _, flow := range state.completed {
		if flow.lastSeen.After(cutoff) {
			all = append(all, *flow)
		}
	}
	for _, flow := range state.flows {
		all = append(all, *flow)
	}
	loaderMode := state.loaderMode
	attachMode := state.attachMode
	eventMode := state.eventMode
	degradedReason := state.degradedReason
	corePID := state.corePID
	activeFlows := len(state.flows)
	failures := cloneMap(state.failures)
	var pinGeneration, ringBufferDropped uint64
	if state.bpf != nil {
		pinGeneration = state.bpf.generation
		ringBufferDropped = state.bpf.dropped()
	}
	state.mu.Unlock()
	rtts := make([]float64, 0, len(all))
	handshakes := make([]float64, 0, len(all))
	var delivery, retrans, segments uint64
	for _, flow := range all {
		if flow.rttMs > 0 {
			rtts = append(rtts, flow.rttMs)
		}
		if flow.handshakeMs > 0 {
			handshakes = append(handshakes, flow.handshakeMs)
		}
		delivery += flow.deliveryRateBps
		retrans += uint64(flow.totalRetrans)
		segments += uint64(flow.segmentsOut)
	}
	sort.Float64s(rtts)
	sort.Float64s(handshakes)
	sort.Slice(all, func(i, j int) bool { return all[i].lastSeen.After(all[j].lastSeen) })
	recentCount := min(len(all), 100)
	recent := make([]FlowSnapshot, 0, recentCount)
	for _, flow := range all[:recentCount] {
		recent = append(recent, snapshot(&flow, now))
	}
	return TelemetrySummary{
		Running: true, DaemonPID: os.Getpid(), CorePID: corePID,
		WindowSeconds: int(windowDuration.Seconds()),
		LoaderMode:    loaderMode, AttachMode: attachMode,
		EventMode: eventMode, PinGeneration: pinGeneration,
		DegradedReason: degradedReason,
		ActiveFlows:    activeFlows, TotalFlows: len(all),
		RTTMedianMs: percentile(rtts, 0.5), RTTP95Ms: percentile(rtts, 0.95),
		HandshakeMedianMs: percentile(handshakes, 0.5),
		HandshakeP95Ms:    percentile(handshakes, 0.95),
		DeliveryRateBps:   delivery,
		EstimatedLossRate: estimateLoss(uint32(min(retrans, math.MaxUint32)), uint32(min(segments, math.MaxUint32))),
		TotalRetrans:      retrans, Failures: failures, RingBufferDropped: ringBufferDropped,
		RecentFlows: recent,
	}
}

func (state *daemon) runtimeStatus() RuntimeStatus {
	state.mu.Lock()
	loaderMode := state.loaderMode
	attachMode := state.attachMode
	eventMode := state.eventMode
	degradedReason := state.degradedReason
	routeGeneration := state.routeGeneration
	tcpMTUProbing := state.tcpMTUProbing
	perSocketPMTU := state.perSocketPMTU
	perSocketMSS := state.perSocketMSS
	logMonitorMode := state.logMonitorMode
	tcpActiveSamples := state.tcpActiveSamples
	tcpIdleSamples := state.tcpIdleSamples
	tcpSamplingLeaseUntil := state.tcpSamplingLeaseUntil
	tcpSampleGeneration := state.tcpSampleGeneration
	var pinGeneration uint64
	if state.bpf != nil {
		pinGeneration = state.bpf.generation
	}
	state.mu.Unlock()
	resolverStats := readResolverRuntimeStats(state.pinRoot)
	resolverMarker := readReadinessValues(filepath.Join(filepath.Dir(state.socketPath), "core.ready"))
	mtuDegradedReason := ""
	if !perSocketPMTU || !perSocketMSS {
		mtuDegradedReason = "per_socket_mtu_unavailable"
	}
	monitorMode := resolverMarker["resolver_monitor_mode"]
	if monitorMode == "" {
		monitorMode = resolverStats.MonitorMode
	}
	tcpSamplingLeaseExpiresAt := int64(0)
	if !tcpSamplingLeaseUntil.IsZero() {
		tcpSamplingLeaseExpiresAt = tcpSamplingLeaseUntil.UnixMilli()
	}
	return RuntimeStatus{
		Running:    true,
		LoaderMode: loaderMode, AttachMode: attachMode,
		EventMode: eventMode, PinGeneration: pinGeneration,
		ObservabilityDegradedReason: degradedReason,
		RouteGeneration:             routeGeneration,
		TCPMTUProbing:               tcpMTUProbing, TCPMode: tcpMTUMode(tcpMTUProbing),
		PerSocketPMTU: perSocketPMTU, PerSocketMSS: perSocketMSS,
		QUICDPLPMTUD: true, MTUDegradedReason: mtuDegradedReason,
		SupervisorMode:            state.supervisorStatus().Mode,
		LogMonitorMode:            logMonitorMode,
		TCPActiveSamples:          tcpActiveSamples,
		TCPIdleSamples:            tcpIdleSamples,
		TCPSamplingMode:           "on_demand",
		TCPSamplingLeaseActive:    time.Now().Before(tcpSamplingLeaseUntil),
		TCPSamplingLeaseExpiresAt: tcpSamplingLeaseExpiresAt,
		TCPSampleGeneration:       tcpSampleGeneration,
		SystemResolverDiscovered:  resolverStats.Discovered,
		DNSPlainCaptureCount:      resolverStats.PlainDNS,
		DNSOverTLSCaptureCount:    resolverStats.DNSOverTLS,
		DNSOverHTTPSCaptureCount:  resolverStats.DNSOverHTTPS,
		ResolverProbeBypassCount:  resolverStats.ProbeBypasses,
		ResolverMonitorMode:       monitorMode,
		ResolverRediscoveries:     parseUint(resolverMarker["resolver_rediscoveries"]),
	}
}

func readReadinessValues(path string) map[string]string {
	result := make(map[string]string)
	file, err := os.Open(path)
	if err != nil {
		return result
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		key, value, found := strings.Cut(scanner.Text(), "=")
		if found {
			result[key] = value
		}
	}
	return result
}

func parseUint(value string) uint64 {
	parsed, _ := strconv.ParseUint(value, 10, 64)
	return parsed
}

func (state *daemon) pruneCompletedLocked(now time.Time) {
	cutoff := now.Add(-windowDuration)
	first := 0
	for first < len(state.completed) && state.completed[first].lastSeen.Before(cutoff) {
		first++
	}
	if first > 0 {
		state.completed = append([]*flowRecord(nil), state.completed[first:]...)
	}
}

func (state *daemon) clear() {
	state.mu.Lock()
	defer state.mu.Unlock()
	state.completed = nil
	state.failures = make(map[string]uint64)
	for _, flow := range state.flows {
		flow.totalRetrans = 0
		flow.segmentsOut = 0
		flow.estimatedLoss = 0
	}
}

func snapshot(flow *flowRecord, now time.Time) FlowSnapshot {
	end := flow.closedAt
	if end.IsZero() {
		end = now
	}
	return FlowSnapshot{
		FlowID: flow.id, Protocol: "tcp", StartedAt: flow.startedAt.UnixMilli(),
		DurationMs: end.Sub(flow.startedAt).Milliseconds(), State: flow.state,
		RTTMs: flow.rttMs, HandshakeMs: flow.handshakeMs,
		SendCWND: flow.sendCWND, DeliveryRateBps: flow.deliveryRateBps,
		TotalRetrans: flow.totalRetrans, EstimatedLossRate: flow.estimatedLoss,
		Failure: flow.failure,
	}
}

func percentile(values []float64, percentile float64) float64 {
	if len(values) == 0 {
		return 0
	}
	index := int(math.Ceil(float64(len(values))*percentile)) - 1
	if index < 0 {
		index = 0
	}
	if index >= len(values) {
		index = len(values) - 1
	}
	return values[index]
}

func estimateLoss(retrans, segments uint32) float64 {
	denominator := max(segments, 1)
	return math.Min(100, math.Max(0, float64(retrans)*100/float64(denominator)))
}

func cloneMap(input map[string]uint64) map[string]uint64 {
	result := make(map[string]uint64, len(input))
	for key, value := range input {
		result[key] = value
	}
	return result
}

func tcpStateName(state uint8) string {
	switch state {
	case netlink.TCP_ESTABLISHED:
		return "established"
	case netlink.TCP_SYN_SENT, netlink.TCP_SYN_RECV:
		return "connecting"
	case netlink.TCP_CLOSE:
		return "closed"
	default:
		return "closing"
	}
}

func readable(path string) bool {
	file, err := os.Open(path)
	if err != nil {
		return false
	}
	file.Close()
	return true
}

func safeReason(err error) string {
	if err == nil {
		return ""
	}
	message := strings.ToLower(err.Error())
	switch {
	case strings.Contains(message, "permission"):
		return "permission_denied"
	case strings.Contains(message, "not supported"), strings.Contains(message, "invalid argument"):
		return "kernel_not_supported"
	case strings.Contains(message, "btf"):
		return "btf_unavailable"
	case strings.Contains(message, "memlock"):
		return "memlock"
	default:
		return "load_failed"
	}
}

func classifyFailure(line string) string {
	lower := strings.ToLower(line)
	switch {
	case strings.Contains(lower, "timeout"), strings.Contains(lower, "deadline exceeded"):
		return "timeout"
	case strings.Contains(lower, "connection refused"):
		return "refused"
	case strings.Contains(lower, "connection reset"):
		return "reset"
	case strings.Contains(lower, "unreachable"), strings.Contains(lower, "no route"):
		return "unreachable"
	case strings.Contains(lower, "tls"):
		return "tls"
	case strings.Contains(lower, "authentication"), strings.Contains(lower, "auth"):
		return "auth"
	case strings.Contains(lower, "protocol"):
		return "protocol"
	default:
		return "other"
	}
}

func (state *daemon) watchCoreFailures() {
	var offset int64
	process := func() {
		info, err := os.Stat(state.coreLogPath)
		if err != nil {
			return
		}
		if info.Size() < offset {
			offset = 0
		}
		if info.Size() == offset {
			return
		}
		file, err := os.Open(state.coreLogPath)
		if err != nil {
			return
		}
		defer file.Close()
		if _, err = file.Seek(offset, io.SeekStart); err != nil {
			return
		}
		scanner := bufio.NewScanner(io.LimitReader(file, 1<<20))
		for scanner.Scan() {
			line := scanner.Text()
			lower := strings.ToLower(line)
			if !strings.Contains(lower, "fail") &&
				!strings.Contains(lower, "error") &&
				!strings.Contains(lower, "timeout") &&
				!strings.Contains(lower, "refused") &&
				!strings.Contains(lower, "reset") &&
				!strings.Contains(lower, "unreachable") {
				continue
			}
			category := classifyFailure(line)
			state.mu.Lock()
			state.failures[category]++
			var newest *flowRecord
			for _, flow := range state.flows {
				if newest == nil || flow.lastSeen.After(newest.lastSeen) {
					newest = flow
				}
			}
			if newest != nil {
				newest.failure = category
			}
			state.mu.Unlock()
		}
		offset, _ = file.Seek(0, io.SeekCurrent)
	}

	events, closeWatcher, ok := watchFileChanges(filepath.Dir(state.coreLogPath), state.stop)
	if ok {
		state.mu.Lock()
		state.logMonitorMode = "inotify"
		state.mu.Unlock()
		defer closeWatcher()
		process()
		watching := true
		for watching {
			select {
			case _, open := <-events:
				if open {
					process()
				} else {
					watching = false
				}
			case <-state.stop:
				return
			}
		}
		closeWatcher()
	}
	state.mu.Lock()
	state.logMonitorMode = "poll_30s"
	state.mu.Unlock()
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			process()
		case <-state.stop:
			return
		}
	}
}

func watchFileChanges(directory string, stop <-chan struct{}) (<-chan struct{}, func(), bool) {
	fd, err := unix.InotifyInit1(unix.IN_CLOEXEC | unix.IN_NONBLOCK)
	if err != nil {
		return nil, func() {}, false
	}
	if _, err = unix.InotifyAddWatch(fd, directory,
		unix.IN_MODIFY|unix.IN_CLOSE_WRITE|unix.IN_CREATE|unix.IN_MOVED_TO|unix.IN_ATTRIB); err != nil {
		_ = unix.Close(fd)
		return nil, func() {}, false
	}
	epollFD, err := unix.EpollCreate1(unix.EPOLL_CLOEXEC)
	if err != nil {
		_ = unix.Close(fd)
		return nil, func() {}, false
	}
	eventFD, err := unix.Eventfd(0, unix.EFD_CLOEXEC|unix.EFD_NONBLOCK)
	if err != nil {
		_ = unix.Close(epollFD)
		_ = unix.Close(fd)
		return nil, func() {}, false
	}
	for _, watchedFD := range []int{fd, eventFD} {
		if err = unix.EpollCtl(epollFD, unix.EPOLL_CTL_ADD, watchedFD, &unix.EpollEvent{
			Events: unix.EPOLLIN | unix.EPOLLERR | unix.EPOLLHUP, Fd: int32(watchedFD),
		}); err != nil {
			_ = unix.Close(eventFD)
			_ = unix.Close(epollFD)
			_ = unix.Close(fd)
			return nil, func() {}, false
		}
	}
	events := make(chan struct{}, 1)
	done := make(chan struct{})
	var closeOnce sync.Once
	closeWatcher := func() {
		closeOnce.Do(func() {
			close(done)
			var payload [8]byte
			binary.NativeEndian.PutUint64(payload[:], 1)
			_, _ = unix.Write(eventFD, payload[:])
		})
	}
	go func() {
		select {
		case <-stop:
			closeWatcher()
		case <-done:
		}
	}()
	go func() {
		defer close(events)
		defer unix.Close(eventFD)
		defer unix.Close(epollFD)
		defer unix.Close(fd)
		pollEvents := make([]unix.EpollEvent, 2)
		buffer := make([]byte, 4096)
		for {
			count, waitErr := unix.EpollWait(epollFD, pollEvents, -1)
			if errors.Is(waitErr, unix.EINTR) {
				continue
			}
			if waitErr != nil {
				return
			}
			for index := 0; index < count; index++ {
				if int(pollEvents[index].Fd) == eventFD {
					return
				}
				for {
					if _, readErr := unix.Read(fd, buffer); readErr != nil {
						if errors.Is(readErr, unix.EAGAIN) {
							break
						}
						return
					}
					select {
					case events <- struct{}{}:
					default:
					}
				}
			}
		}
	}()
	return events, closeWatcher, true
}
