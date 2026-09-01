//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

type SupervisorOptions struct {
	CorePath   string
	ConfigPath string
	LogPath    string
	RuntimeDir string
	SocketPath string
}

const (
	coreReadinessTimeout    = 8 * time.Second
	coreStopTimeout         = 2 * time.Second
	controllerCheckTimeout  = 700 * time.Millisecond
	controllerCheckInterval = 150 * time.Millisecond
)

type processSupervisor struct {
	mu              sync.Mutex
	options         SupervisorOptions
	command         *exec.Cmd
	pidfd           int
	mode            string
	pidfdOpen       bool
	pidfdSendSignal bool
	pidfdPoll       bool
	stopping        bool
}

func RunSupervised(
	socketPath, pinRoot, configPath, coreLogPath string,
	options SupervisorOptions,
) error {
	if options.CorePath == "" || options.ConfigPath == "" ||
		options.LogPath == "" || options.RuntimeDir == "" {
		return errors.New("incomplete supervisor options")
	}
	options.SocketPath = socketPath
	supervisor := &processSupervisor{
		options: options,
		pidfd:   -1,
		mode:    "child_wait_fallback",
	}
	pid := os.Getpid()
	startTime := processStartTime(pid)
	_ = writePrivate(filepath.Join(options.RuntimeDir, "supervisor.pid"), []byte(strconv.Itoa(pid)+"\n"))
	_ = writePrivate(filepath.Join(options.RuntimeDir, "supervisor.starttime"), []byte(startTime+"\n"))
	_ = writePrivate(filepath.Join(options.RuntimeDir, "observability.pid"), []byte(strconv.Itoa(pid)+"\n"))
	_ = writePrivate(filepath.Join(options.RuntimeDir, "observability.starttime"), []byte(startTime+"\n"))
	err := runDaemon(socketPath, pinRoot, configPath, coreLogPath, supervisor)
	for _, name := range []string{
		"supervisor.pid", "supervisor.starttime", "observability.pid", "observability.starttime",
	} {
		_ = os.Remove(filepath.Join(options.RuntimeDir, name))
	}
	return err
}

func RunAuxiliary(
	corePath, configPath, logPath, runtimeDir, name string,
	timeout time.Duration,
) error {
	if corePath == "" || configPath == "" || logPath == "" || runtimeDir == "" ||
		name == "" || strings.ContainsAny(name, `/\`) {
		return errors.New("invalid auxiliary supervisor options")
	}
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	defer logFile.Close()
	command := exec.Command(corePath, "run", "-c", configPath)
	command.Stdout = logFile
	command.Stderr = logFile
	command.Env = os.Environ()
	if err = command.Start(); err != nil {
		return err
	}
	pid := command.Process.Pid
	startTime := processStartTime(pid)
	pidPath := filepath.Join(runtimeDir, name+".pid")
	startPath := filepath.Join(runtimeDir, name+".starttime")
	supervisorPIDPath := filepath.Join(runtimeDir, name+".supervisor.pid")
	supervisorStartPath := filepath.Join(runtimeDir, name+".supervisor.starttime")
	_ = writePrivate(pidPath, []byte(strconv.Itoa(pid)+"\n"))
	_ = writePrivate(startPath, []byte(startTime+"\n"))
	_ = writePrivate(supervisorPIDPath, []byte(strconv.Itoa(os.Getpid())+"\n"))
	_ = writePrivate(supervisorStartPath, []byte(processStartTime(os.Getpid())+"\n"))
	defer func() {
		_ = os.Remove(pidPath)
		_ = os.Remove(startPath)
		_ = os.Remove(supervisorPIDPath)
		_ = os.Remove(supervisorStartPath)
	}()

	pidfd, pidfdErr := unix.PidfdOpen(pid, unix.PIDFD_NONBLOCK)
	if pidfdErr == nil {
		defer unix.Close(pidfd)
	}
	waitResult := make(chan error, 1)
	go func() { waitResult <- command.Wait() }()
	var exitReady <-chan error = waitResult
	if pidfdErr == nil {
		pollResult := make(chan error, 1)
		go func() {
			descriptors := []unix.PollFd{{Fd: int32(pidfd), Events: unix.POLLIN | unix.POLLHUP}}
			_, pollErr := unix.Poll(descriptors, -1)
			pollResult <- pollErr
		}()
		exitReady = pollResult
	}
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	defer signal.Stop(signals)
	var timeoutChannel <-chan time.Time
	var timer *time.Timer
	if timeout > 0 {
		timer = time.NewTimer(timeout)
		timeoutChannel = timer.C
		defer timer.Stop()
	}
	select {
	case exitErr := <-exitReady:
		if pidfdErr == nil {
			return <-waitResult
		}
		return exitErr
	case <-signals:
	case <-timeoutChannel:
	}
	signalChild(command, pidfd, syscall.SIGTERM)
	select {
	case err = <-waitResult:
		return err
	case <-time.After(5 * time.Second):
		signalChild(command, pidfd, syscall.SIGKILL)
		return <-waitResult
	}
}

func signalChild(command *exec.Cmd, pidfd int, signal syscall.Signal) {
	if pidfd >= 0 && unix.PidfdSendSignal(pidfd, unix.Signal(signal), nil, 0) == nil {
		return
	}
	if command != nil && command.Process != nil {
		_ = command.Process.Signal(signal)
	}
}

func (supervisor *processSupervisor) run(state *daemon) {
	var crashes []time.Time
	cleanupOnExit := false
	hasReachedReadiness := false
	for desiredRunning(supervisor.options.RuntimeDir) {
		startedAt := time.Now()
		exitCode, err, ready := supervisor.runCore(state)
		// A crashed core cannot run its normal TC detach path. Restore the
		// exact name+ifindex ownership journal before any replacement core can
		// observe route_localnet=1 and mistake it for the original value.
		_ = CleanupSharedNetwork(supervisor.options.RuntimeDir)
		_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "hotspot.state"))
		hasReachedReadiness = hasReachedReadiness || ready
		now := time.Now()
		if now.Sub(startedAt) >= 10*time.Minute {
			crashes = nil
		}
		if !desiredRunning(supervisor.options.RuntimeDir) || supervisor.isStopping() {
			break
		}
		cutoff := now.Add(-5 * time.Minute)
		kept := crashes[:0]
		for _, crash := range crashes {
			if crash.After(cutoff) {
				kept = append(kept, crash)
			}
		}
		crashes = append(kept, now)
		message := fmt.Sprintf("sing-box exited with code %d (crash %d/3)", exitCode, len(crashes))
		if err != nil {
			message += ": " + safeProcessError(err)
		} else if category := safeCoreFailureCategory(supervisor.options.LogPath); category != "" {
			message += ": " + category
		}
		_ = writePrivate(filepath.Join(supervisor.options.RuntimeDir, "last_error"), []byte(message+"\n"))
		if !shouldRetryCoreExit(hasReachedReadiness, len(crashes)) {
			_ = writePrivate(filepath.Join(supervisor.options.RuntimeDir, "desired"), []byte("stopped\n"))
			_ = writePrivate(filepath.Join(supervisor.options.RuntimeDir, "actual"), []byte("failed\n"))
			_ = cleanupPersistentRedirect()
			cleanupOnExit = true
			break
		}
		delay := restartBackoff(len(crashes))
		select {
		case <-time.After(delay):
		case <-state.stop:
			return
		}
	}
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "core.pid"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "core.starttime"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "started_at"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "core.ready"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "startup.ready"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "android_dns_cache_flush"))
	state.shutdown(cleanupOnExit)
}

func shouldRetryCoreExit(hasReachedReadiness bool, crashes int) bool {
	return hasReachedReadiness && crashes < 3
}

func restartBackoff(attempt int) time.Duration {
	switch attempt {
	case 1:
		return time.Second
	case 2:
		return 2 * time.Second
	default:
		return 5 * time.Second
	}
}

func (supervisor *processSupervisor) runCore(state *daemon) (int, error, bool) {
	traceID := currentTraceID(supervisor.options.RuntimeDir)
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "core.ready"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "startup.ready"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "android_dns_cache_flush"))
	logFile, err := os.OpenFile(
		supervisor.options.LogPath,
		os.O_CREATE|os.O_WRONLY|os.O_TRUNC,
		0o600,
	)
	if err != nil {
		return -1, err, false
	}
	command := exec.Command(supervisor.options.CorePath, "run", "-c", supervisor.options.ConfigPath)
	command.Stdout = logFile
	command.Stderr = logFile
	command.Env = append(
		os.Environ(),
		"AKIHALINK_PERSISTENT_EBPF=1",
		"AKIHALINK_OBSERVABILITY_SOCKET="+supervisor.options.SocketPath,
		"AKIHALINK_UID_POLICY_SOCKET="+filepath.Join(supervisor.options.RuntimeDir, "uid-policy.sock"),
		"AKIHALINK_DNS_PREWARM=1",
		"AKIHALINK_RUNTIME="+supervisor.options.RuntimeDir,
		"AKIHALINK_TRACE_ID="+traceID,
	)
	traceBegin("AKL/core_spawn", traceID)
	if err = command.Start(); err != nil {
		traceEnd(traceID)
		logFile.Close()
		return -1, err, false
	}
	traceEnd(traceID)
	pid := command.Process.Pid
	startTime := processStartTime(pid)
	_ = writePrivate(filepath.Join(supervisor.options.RuntimeDir, "core.pid"), []byte(strconv.Itoa(pid)+"\n"))
	_ = writePrivate(filepath.Join(supervisor.options.RuntimeDir, "core.starttime"), []byte(startTime+"\n"))
	_ = writePrivate(
		filepath.Join(supervisor.options.RuntimeDir, "started_at"),
		[]byte(strconv.FormatInt(time.Now().Unix(), 10)+"\n"),
	)
	_ = writePrivate(filepath.Join(supervisor.options.RuntimeDir, "actual"), []byte("starting\n"))

	pidfd, pidfdErr := unix.PidfdOpen(pid, unix.PIDFD_NONBLOCK)
	supervisor.mu.Lock()
	supervisor.command = command
	supervisor.pidfd = pidfd
	supervisor.pidfdOpen = pidfdErr == nil
	if pidfdErr == nil {
		supervisor.pidfdSendSignal = unix.PidfdSendSignal(pidfd, 0, nil, 0) == nil
		descriptors := []unix.PollFd{{Fd: int32(pidfd), Events: unix.POLLIN | unix.POLLHUP}}
		_, pollErr := unix.Poll(descriptors, 0)
		supervisor.pidfdPoll = pollErr == nil
		switch {
		case supervisor.pidfdSendSignal && supervisor.pidfdPoll:
			supervisor.mode = "pidfd"
		case supervisor.pidfdPoll:
			supervisor.mode = "pidfd_poll_signal_fallback"
		default:
			supervisor.mode = "child_wait_identity_fallback"
		}
	}
	supervisor.mu.Unlock()

	state.mu.Lock()
	state.corePID = pid
	state.mu.Unlock()
	if state.bpf != nil {
		_ = state.bpf.setCorePID(pid)
	}
	_ = state.refreshProxyTargets()

	waitResult := make(chan error, 1)
	go func() { waitResult <- command.Wait() }()
	startupErr := waitForCoreReadiness(
		supervisor.options.ConfigPath,
		supervisor.options.RuntimeDir,
		pid,
		startTime,
		coreReadinessTimeout,
	)
	var waitErr error
	if startupErr != nil {
		signalChild(command, pidfd, syscall.SIGTERM)
		select {
		case waitErr = <-waitResult:
		case <-time.After(coreStopTimeout):
			signalChild(command, pidfd, syscall.SIGKILL)
			waitErr = <-waitResult
		}
	} else {
		_ = writePrivate(filepath.Join(supervisor.options.RuntimeDir, "actual"), []byte("running\n"))
		state.requestTCPSample()
	}
	if startupErr == nil && pidfdErr == nil {
		pollResult := make(chan error, 1)
		go func() {
			descriptors := []unix.PollFd{{Fd: int32(pidfd), Events: unix.POLLIN | unix.POLLHUP}}
			_, pollErr := unix.Poll(descriptors, -1)
			pollResult <- pollErr
		}()
		select {
		case pollErr := <-pollResult:
			supervisor.mu.Lock()
			supervisor.pidfdPoll = pollErr == nil
			supervisor.mu.Unlock()
		case <-state.stop:
			supervisor.stop()
		}
	}
	if startupErr == nil {
		waitErr = <-waitResult
	}
	logFile.Close()
	supervisor.mu.Lock()
	if supervisor.pidfd >= 0 {
		_ = unix.Close(supervisor.pidfd)
	}
	supervisor.pidfd = -1
	supervisor.command = nil
	supervisor.mu.Unlock()
	state.mu.Lock()
	state.corePID = 0
	state.mu.Unlock()
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "core.pid"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "core.starttime"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "started_at"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "core.ready"))
	_ = os.Remove(filepath.Join(supervisor.options.RuntimeDir, "startup.ready"))
	if startupErr != nil {
		return -1, startupErr, false
	}
	if waitErr == nil {
		return 0, nil, true
	}
	var exitError *exec.ExitError
	if errors.As(waitErr, &exitError) {
		return exitError.ExitCode(), nil, true
	}
	return -1, waitErr, true
}

type startupClashConfig struct {
	Experimental struct {
		ClashAPI struct {
			ExternalController string `json:"external_controller"`
			Secret             string `json:"secret"`
		} `json:"clash_api"`
	} `json:"experimental"`
}

func readStartupClashConfig(path string) (string, string, error) {
	payload, err := os.ReadFile(path)
	if err != nil {
		return "", "", errors.New("startup configuration could not be read")
	}
	var config startupClashConfig
	if err = json.Unmarshal(payload, &config); err != nil {
		return "", "", errors.New("startup configuration could not be parsed")
	}
	if config.Experimental.ClashAPI.ExternalController != "127.0.0.1:9090" ||
		config.Experimental.ClashAPI.Secret == "" {
		return "", "", errors.New("startup controller configuration is invalid")
	}
	return config.Experimental.ClashAPI.ExternalController, config.Experimental.ClashAPI.Secret, nil
}

func startupMarkerReady(runtimeDir string, pid int, startTime string) bool {
	values := readReadinessValues(filepath.Join(runtimeDir, "core.ready"))
	return values["pid"] == strconv.Itoa(pid) &&
		values["starttime"] == startTime &&
		values["startup_health"] == "healthy" &&
		values["system_resolver_attached"] == "1"
}

func writeStartupReady(runtimeDir string, pid int, startTime string) error {
	content := fmt.Sprintf(
		"pid=%d\nstarttime=%s\ncontroller_authenticated=1\nsystem_resolver_attached=1\nstartup_health=healthy\n",
		pid,
		startTime,
	)
	return atomicPrivateWrite(filepath.Join(runtimeDir, "startup.ready"), []byte(content))
}

func atomicPrivateWrite(path string, content []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".startup-ready-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err = temporary.Chmod(0o600); err == nil {
		_, err = temporary.Write(content)
	}
	if closeErr := temporary.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}

func waitForCoreReadiness(configPath, runtimeDir string, pid int, startTime string, timeout time.Duration) error {
	endpoint, secret, err := readStartupClashConfig(configPath)
	if err != nil {
		return err
	}
	return waitForAuthenticatedController(runtimeDir, pid, startTime, endpoint, secret, timeout, nil)
}

func waitForAuthenticatedController(
	runtimeDir string,
	pid int,
	startTime string,
	endpoint string,
	secret string,
	timeout time.Duration,
	client *http.Client,
) error {
	if endpoint == "" || secret == "" {
		return errors.New("startup controller configuration is invalid")
	}
	if client == nil {
		client = &http.Client{
			Timeout:   controllerCheckTimeout,
			Transport: &http.Transport{Proxy: nil},
		}
		defer client.CloseIdleConnections()
	}
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(controllerCheckInterval)
	defer ticker.Stop()
	consecutive := 0
	controllerFailed := false
	resolverPending := false
	check := func() bool {
		if !processAlive(pid) {
			return false
		}
		if !startupMarkerReady(runtimeDir, pid, startTime) {
			resolverPending = true
			return false
		}
		request, requestErr := http.NewRequest(http.MethodGet, "http://"+endpoint+"/version", nil)
		if requestErr != nil {
			return false
		}
		request.Header.Set("Authorization", "Bearer "+secret)
		response, requestErr := client.Do(request)
		if requestErr != nil {
			return false
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			controllerFailed = response.StatusCode == http.StatusUnauthorized || controllerFailed
			return false
		}
		return true
	}
	for {
		if check() {
			consecutive++
			if consecutive >= 2 {
				if err := writeStartupReady(runtimeDir, pid, startTime); err != nil {
					return errors.New("startup readiness record could not be written")
				}
				return nil
			}
		} else {
			consecutive = 0
			if !processAlive(pid) {
				return errors.New("core exited before startup health gate")
			}
		}
		select {
		case <-ticker.C:
		case <-deadline.C:
			switch {
			case controllerFailed:
				return errors.New("core startup health gate rejected Clash API authentication")
			case resolverPending:
				return errors.New("core startup health gate timed out while waiting for the system resolver")
			default:
				return errors.New("core startup health gate timed out while waiting for the Clash API")
			}
		}
	}
}

func (supervisor *processSupervisor) stop() {
	supervisor.mu.Lock()
	if supervisor.stopping {
		supervisor.mu.Unlock()
		return
	}
	supervisor.stopping = true
	command := supervisor.command
	pidfd := supervisor.pidfd
	supervisor.mu.Unlock()
	if command == nil || command.Process == nil {
		return
	}
	if pidfd >= 0 {
		if unix.PidfdSendSignal(pidfd, unix.SIGTERM, nil, 0) == nil {
			supervisor.mu.Lock()
			supervisor.pidfdSendSignal = true
			supervisor.mu.Unlock()
		} else {
			_ = command.Process.Signal(syscall.SIGTERM)
		}
	} else {
		_ = command.Process.Signal(syscall.SIGTERM)
	}
	deadline := time.Now().Add(coreStopTimeout)
	for time.Now().Before(deadline) {
		if !processAlive(command.Process.Pid) {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	if pidfd >= 0 {
		if unix.PidfdSendSignal(pidfd, unix.SIGKILL, nil, 0) != nil {
			_ = command.Process.Kill()
		}
	} else {
		_ = command.Process.Kill()
	}
}

func (supervisor *processSupervisor) isStopping() bool {
	supervisor.mu.Lock()
	defer supervisor.mu.Unlock()
	return supervisor.stopping
}

func (state *daemon) supervisorStatus() ProcessSupervisorStatus {
	if state.supervisor == nil {
		open, send, poll := probePidfd()
		return ProcessSupervisorStatus{
			Mode: "external", PidfdOpen: open, PidfdSendSignal: send, PidfdPoll: poll,
		}
	}
	state.supervisor.mu.Lock()
	defer state.supervisor.mu.Unlock()
	pid := 0
	if state.supervisor.command != nil && state.supervisor.command.Process != nil {
		pid = state.supervisor.command.Process.Pid
	}
	return ProcessSupervisorStatus{
		Mode: state.supervisor.mode, PidfdOpen: state.supervisor.pidfdOpen,
		PidfdSendSignal: state.supervisor.pidfdSendSignal,
		PidfdPoll:       state.supervisor.pidfdPoll, ManagedPID: pid,
	}
}

func probePidfd() (bool, bool, bool) {
	fd, err := unix.PidfdOpen(os.Getpid(), unix.PIDFD_NONBLOCK)
	if err != nil {
		return false, false, false
	}
	defer unix.Close(fd)
	send := unix.PidfdSendSignal(fd, 0, nil, 0) == nil
	descriptors := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN | unix.POLLHUP}}
	_, pollErr := unix.Poll(descriptors, 0)
	return true, send, pollErr == nil
}

func processAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	fd, err := unix.PidfdOpen(pid, unix.PIDFD_NONBLOCK)
	if err != nil {
		return syscall.Kill(pid, 0) == nil
	}
	defer unix.Close(fd)
	descriptors := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN | unix.POLLHUP}}
	ready, err := unix.Poll(descriptors, 0)
	return err == nil && ready == 0
}

func ProcessStatus(pid int, expected, expectedStart string) bool {
	if !processAlive(pid) {
		return false
	}
	if expectedStart != "" && processStartTime(pid) != expectedStart {
		return false
	}
	executable, _ := os.Readlink(fmt.Sprintf("/proc/%d/exe", pid))
	if executable == expected {
		return true
	}
	commandLine, _ := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	return expected != "" && strings.Contains(string(commandLine), expected)
}

func ProcessSignal(pid int, signal unix.Signal, expected, expectedStart string) error {
	if !ProcessStatus(pid, expected, expectedStart) {
		return errors.New("process identity mismatch")
	}
	fd, err := unix.PidfdOpen(pid, unix.PIDFD_NONBLOCK)
	if err != nil {
		if !ProcessStatus(pid, expected, expectedStart) {
			return errors.New("process identity mismatch")
		}
		return syscall.Kill(pid, syscall.Signal(signal))
	}
	defer unix.Close(fd)
	if !ProcessStatus(pid, expected, expectedStart) {
		return errors.New("process identity mismatch")
	}
	if err = unix.PidfdSendSignal(fd, signal, nil, 0); err == nil {
		return nil
	}
	if !ProcessStatus(pid, expected, expectedStart) {
		return errors.New("process identity mismatch")
	}
	return syscall.Kill(pid, syscall.Signal(signal))
}

func processStartTime(pid int) string {
	payload, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return ""
	}
	text := string(payload)
	closeName := strings.LastIndex(text, ")")
	if closeName < 0 {
		return ""
	}
	fields := strings.Fields(text[closeName+1:])
	if len(fields) <= 19 {
		return ""
	}
	return fields[19]
}

func desiredRunning(runtimeDir string) bool {
	payload, _ := os.ReadFile(filepath.Join(runtimeDir, "desired"))
	return strings.TrimSpace(string(payload)) == "running"
}

func writePrivate(path string, payload []byte) error {
	if err := os.WriteFile(path, payload, 0o600); err != nil {
		return err
	}
	return os.Chmod(path, 0o600)
}

func safeProcessError(err error) string {
	if err == nil {
		return ""
	}
	switch {
	case errors.Is(err, os.ErrPermission):
		return "permission_denied"
	case errors.Is(err, os.ErrNotExist):
		return "not_found"
	default:
		return "process_failed"
	}
}

func safeCoreFailureCategory(logPath string) string {
	payload, err := os.ReadFile(logPath)
	if err != nil {
		return ""
	}
	const maximumTail = 64 << 10
	if len(payload) > maximumTail {
		payload = payload[len(payload)-maximumTail:]
	}
	text := strings.ToLower(string(payload))
	switch {
	case strings.Contains(text, "panic: cause on an nil error") &&
		strings.Contains(text, "/protocol/ebpf."):
		return "ebpf_startup_panic"
	case strings.Contains(text, "panic:"):
		return "core_startup_panic"
	case strings.Contains(text, "permission denied") ||
		strings.Contains(text, "operation not permitted"):
		return "permission_denied"
	case strings.Contains(text, "operation not supported") ||
		strings.Contains(text, "not supported"):
		return "kernel_capability_unavailable"
	case strings.Contains(text, "address already in use"):
		return "listener_address_in_use"
	case strings.Contains(text, "no such file"):
		return "required_file_missing"
	default:
		return ""
	}
}
