//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"encoding/json"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
	"unsafe"

	netlink "github.com/sagernet/netlink"
	"golang.org/x/sys/unix"
)

func TestEventABI(t *testing.T) {
	if size := unsafe.Sizeof(bpfEvent{}); size != 64 {
		t.Fatalf("unexpected BPF event ABI size: %d", size)
	}
}

func TestTelemetryMath(t *testing.T) {
	values := []float64{10, 20, 30, 40, 50}
	if got := percentile(values, 0.5); got != 30 {
		t.Fatalf("median: %v", got)
	}
	if got := percentile(values, 0.95); got != 50 {
		t.Fatalf("p95: %v", got)
	}
	if got := estimateLoss(2, 100); got != 2 {
		t.Fatalf("loss estimate: %v", got)
	}
	if got := estimateLoss(200, 100); got != 100 {
		t.Fatalf("loss estimate must be clamped: %v", got)
	}
}

func TestTCPInfoSamplingLeaseLifecycle(t *testing.T) {
	state := &daemon{tcpSampleDone: make(chan struct{})}
	acquired := state.updateTCPSampling("sampling-renew", 0)
	if acquired.TCPSamplingMode != "on_demand" || !acquired.TCPSamplingLeaseActive {
		t.Fatalf("sampling lease was not acquired: %+v", acquired)
	}
	if acquired.TCPSamplingLeaseExpiresAt <= time.Now().UnixMilli() {
		t.Fatalf("sampling lease did not receive a future expiry: %+v", acquired)
	}
	released := state.updateTCPSampling("sampling-release", 0)
	if released.TCPSamplingLeaseActive {
		t.Fatalf("sampling lease was not released: %+v", released)
	}
}

func TestEffectiveNetworkPathIgnoresUnrelatedRouteEvents(t *testing.T) {
	state := &daemon{
		flows: make(map[uint64]*flowRecord), paths: make(map[string]*pathAccumulator),
		failures: make(map[string]uint64), routeGeneration: 1,
	}
	state.applyNetworkPathSnapshot("route:4:10;link:10:wlan0:1500:true:true", 1500)
	state.recordNetworkEvent("route", "oem0", 0, false, false, false, "")
	state.applyNetworkPathSnapshot("route:4:10;link:10:wlan0:1500:true:true", 1500)
	if state.routeGeneration != 1 || state.failures["network_changed"] != 0 {
		t.Fatalf("unrelated event reset path state: generation=%d failures=%v", state.routeGeneration, state.failures)
	}
	state.applyNetworkPathSnapshot("route:4:11;link:11:rmnet0:1420:true:false", 1420)
	if state.routeGeneration != 2 || state.failures["network_changed"] != 1 {
		t.Fatalf("effective path change was ignored: generation=%d failures=%v", state.routeGeneration, state.failures)
	}
}

func TestRuntimeStatusIsCompactAndAggregateOnly(t *testing.T) {
	state := &daemon{
		loaderMode:      "runtime_instruction_fallback",
		attachMode:      "legacy",
		eventMode:       "tcp_info",
		degradedReason:  "btf_unavailable",
		routeGeneration: 7,
		tcpMTUProbing:   1,
	}
	status := state.runtimeStatus()
	if !status.Running || status.LoaderMode != "runtime_instruction_fallback" ||
		status.RouteGeneration != 7 || status.TCPMode != "kernel_blackhole" {
		t.Fatalf("unexpected compact runtime status: %+v", status)
	}
	payload, err := json.Marshal(status)
	if err != nil {
		t.Fatal(err)
	}
	text := strings.ToLower(string(payload))
	for _, sensitive := range []string{"pid", "recentflows", "fingerprint", "networkevents"} {
		if strings.Contains(text, sensitive) {
			t.Fatalf("runtime status leaked %q: %s", sensitive, payload)
		}
	}
}

func TestConcurrentSnapshotReadsAndEventWrites(t *testing.T) {
	fingerprint := strings.Repeat("a", 64)
	state := &daemon{
		salt:               [32]byte{1},
		flows:              make(map[uint64]*flowRecord),
		pendingBPF:         make(map[uint64][]bpfEvent),
		paths:              make(map[string]*pathAccumulator),
		failures:           make(map[string]uint64),
		currentFingerprint: fingerprint,
		currentTargetTag:   "node-a",
	}
	var group sync.WaitGroup
	group.Add(2)
	go func() {
		defer group.Done()
		for index := 0; index < 200; index++ {
			now := time.Now()
			state.applyEvent(bpfEvent{
				ABIVersion: eventABIVersion, EventType: 2,
				SocketCookie: uint64(index % 4), SRTTUS: 10_000,
				HandshakeUS: 2_000, SegmentsOut: 10, TotalRetrans: 1,
			})
			state.applyTCPInfo(uint64(index%4), netlink.TCP_ESTABLISHED, &netlink.TCPInfo{
				Rtt: 10_000, Snd_cwnd: 10, Segs_out: 10, Total_retrans: 1,
			}, now, "ipv4")
			state.recordNetworkEvent("route", "wlan0", 1500, true, true, true, "")
		}
	}()
	go func() {
		defer group.Done()
		for index := 0; index < 200; index++ {
			state.runtimeStatus()
			state.summary()
			state.pathStatus()
			state.recentNetworkEvents(20)
		}
	}()
	group.Wait()
}

func TestFailureClassificationDoesNotExposeRawError(t *testing.T) {
	cases := map[string]string{
		"dial tcp: i/o timeout":           "timeout",
		"connect: connection refused":     "refused",
		"read: connection reset by peer":  "reset",
		"network is unreachable":          "unreachable",
		"tls handshake failed":            "tls",
		"authentication rejected":         "auth",
		"unexpected protocol response":    "protocol",
		"arbitrary implementation detail": "other",
	}
	for input, expected := range cases {
		if got := classifyFailure(input); got != expected {
			t.Fatalf("%q: got %q, want %q", input, got, expected)
		}
	}
}

func TestProxyTargetComesFromUserSelector(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "config.json")
	payload := map[string]any{
		"outbounds": []map[string]any{
			{"type": "shadowsocks", "tag": "node-a", "server": "203.0.113.10", "server_port": 443},
			{"type": "vmess", "tag": "node-b", "server": "203.0.113.11", "server_port": 8443},
			{"type": "selector", "tag": "proxy", "default": "node-a"},
		},
	}
	encoded, _ := json.Marshal(payload)
	if err := os.WriteFile(path, encoded, 0o600); err != nil {
		t.Fatal(err)
	}
	state := &daemon{configPath: path, proxyTargets: make(map[string]struct{})}
	if err := state.refreshProxyTargets("node-b"); err != nil {
		t.Fatal(err)
	}
	if !state.isProxyTarget(&netlink.Socket{ID: netlink.SocketID{
		Destination: net.ParseIP("203.0.113.11"), DestinationPort: 8443,
	}}) {
		t.Fatal("selected endpoint was not tracked")
	}
	if state.isProxyTarget(&netlink.Socket{ID: netlink.SocketID{
		Destination: net.ParseIP("203.0.113.10"), DestinationPort: 443,
	}}) {
		t.Fatal("non-selected endpoint must not be tracked")
	}
}

func TestRingEventWaitsForTCPInfoOwnership(t *testing.T) {
	state := &daemon{
		salt:       [32]byte{1},
		flows:      make(map[uint64]*flowRecord),
		pendingBPF: make(map[uint64][]bpfEvent),
	}
	state.applyEvent(bpfEvent{
		ABIVersion: eventABIVersion, EventType: 2,
		SocketCookie: 7, HandshakeUS: 12_000, SRTTUS: 50_000,
	})
	if len(state.flows) != 0 || len(state.pendingBPF[7]) != 1 {
		t.Fatal("unowned ring event became visible")
	}
	state.applyTCPInfo(7, netlink.TCP_ESTABLISHED, &netlink.TCPInfo{
		Rtt: 51_000, Snd_cwnd: 12, Segs_out: 100, Total_retrans: 1,
	}, time.Now(), "ipv4")
	flow := state.flows[7]
	if flow == nil || flow.handshakeMs != 12 || flow.rttMs != 51 {
		t.Fatalf("pending event was not merged: %#v", flow)
	}
}

func TestPassivePathObservationTracksBlackholeAndIPv6Floor(t *testing.T) {
	fingerprint := "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
	state := &daemon{
		salt: [32]byte{1}, flows: make(map[uint64]*flowRecord),
		pendingBPF: make(map[uint64][]bpfEvent), paths: make(map[string]*pathAccumulator),
		currentFingerprint: fingerprint, routeGeneration: 7, defaultRouteMTU: 1500,
	}
	now := time.Now()
	state.flows[9] = &flowRecord{
		cookie: 9, fingerprint: fingerprint, addressFamily: "ipv4",
		pathGeneration: 7, startedAt: now.Add(-10 * time.Second),
		lastSeen: now.Add(-time.Second), lastAckProgress: now.Add(-4 * time.Second),
		lastSample: now.Add(-time.Second), established: true, state: "established",
		bytesAcked: 100, stallRetransBase: 0,
	}
	state.applyTCPInfo(9, netlink.TCP_ESTABLISHED, &netlink.TCPInfo{
		Rto: 1_000_000, Pmtu: 1500, Snd_mss: 1460,
		Bytes_acked: 100, Bytes_sent: 10_000, Total_retrans: 3, Segs_out: 100,
	}, now, "ipv4")
	path := state.paths[pathKey(fingerprint, "ipv4", 7)]
	if path == nil || path.PMTU != 1492 || path.MSS != 1452 || path.BlackholeCount != 1 {
		t.Fatalf("blackhole did not lower the next-connection plateau: %#v", path)
	}
	ipv6 := &pathAccumulator{AddressFamily: "ipv6", PMTU: 1280}
	state.lowerPathLocked(ipv6)
	if ipv6.PMTU != 1280 || ipv6.MSS != 1220 {
		t.Fatalf("IPv6 path fell below RFC minimum: %#v", ipv6)
	}
}

func TestPidfdHelperRejectsReusedProcessIdentity(t *testing.T) {
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	pid := os.Getpid()
	startTime := processStartTime(pid)
	if startTime == "" || !ProcessStatus(pid, executable, startTime) {
		t.Fatal("current process identity was not recognized")
	}
	if ProcessStatus(pid, executable, startTime+"0") {
		t.Fatal("mismatched procfs start time was accepted")
	}
	if err = ProcessSignal(pid, 0, executable, startTime); err != nil {
		t.Fatalf("safe signal-0 pidfd operation failed: %v", err)
	}
	open, send, poll := probePidfd()
	if open && (!send || !poll) {
		t.Fatalf("partial pidfd probe result: open=%v send=%v poll=%v", open, send, poll)
	}
}

func TestPidfdSupervisesChildTermAndKill(t *testing.T) {
	executable, err := exec.LookPath("sleep")
	if err != nil {
		t.Skip("sleep is unavailable")
	}
	for _, signal := range []unix.Signal{unix.SIGTERM, unix.SIGKILL} {
		command := exec.Command(executable, "30")
		if err = command.Start(); err != nil {
			t.Fatal(err)
		}
		pid := command.Process.Pid
		startTime := processStartTime(pid)
		if err = ProcessSignal(pid, signal, executable, startTime); err != nil {
			_ = command.Process.Kill()
			_ = command.Wait()
			t.Fatalf("signal %d failed: %v", signal, err)
		}
		if waitErr := command.Wait(); waitErr == nil {
			t.Fatalf("signal %d did not terminate the child", signal)
		}
		if ProcessStatus(pid, executable, startTime) {
			t.Fatalf("exited child for signal %d remained alive", signal)
		}
	}
}
