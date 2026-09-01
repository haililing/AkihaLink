//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRootNetdIdentityAcceptsVendorBinderThreadName(t *testing.T) {
	processRoot := filepath.Join(t.TempDir(), "1480")
	if err := os.MkdirAll(processRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	for name, content := range map[string][]byte{
		"comm":    []byte("binder:1480_4\n"),
		"status":  []byte("Name:\tnetd\nUid:\t0\t0\t0\t0\n"),
		"cmdline": []byte("/system/bin/netd\x00"),
	} {
		if err := os.WriteFile(filepath.Join(processRoot, name), content, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Symlink("/system/bin/netd", filepath.Join(processRoot, "exe")); err != nil {
		t.Fatal(err)
	}
	if !isRootNetdProcess(processRoot) {
		t.Fatal("verified vendor netd identity was rejected")
	}
	if err := os.WriteFile(filepath.Join(processRoot, "comm"), []byte("binder:1499_4\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if isRootNetdProcess(processRoot) {
		t.Fatal("binder thread name for a different process was accepted")
	}
}

func TestRootNetdExecutableFallback(t *testing.T) {
	for _, testCase := range []struct {
		name          string
		command       string
		executable    string
		executableErr error
		accepted      bool
	}{
		{"readable system executable", "/system/bin/netd", "/system/bin/netd", nil, true},
		{"permission denied system command", "/system/bin/netd", "", os.ErrPermission, true},
		{"permission denied APEX command", "/apex/com.android.tethering/bin/netd", "", os.ErrPermission, true},
		{"permission denied data command", "/data/local/tmp/netd", "", os.ErrPermission, false},
		{"missing system executable", "/system/bin/netd", "", os.ErrNotExist, false},
		{"wrong command", "/system/bin/not-netd", "/system/bin/netd", nil, false},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			actual := isRootNetdExecutable(testCase.command, testCase.executable, testCase.executableErr)
			if actual != testCase.accepted {
				t.Fatalf("accepted=%t, want %t", actual, testCase.accepted)
			}
		})
	}
}

func TestRestartBackoffSequence(t *testing.T) {
	for attempt, expected := range []time.Duration{time.Second, 2 * time.Second, 5 * time.Second, 5 * time.Second} {
		if actual := restartBackoff(attempt + 1); actual != expected {
			t.Fatalf("attempt %d delay=%s, want %s", attempt+1, actual, expected)
		}
	}
}

func TestStartupFailureDoesNotRetryBeforeReadiness(t *testing.T) {
	if shouldRetryCoreExit(false, 1) {
		t.Fatal("initial startup failure must not be retried")
	}
	if !shouldRetryCoreExit(true, 1) {
		t.Fatal("a core that was ready should retain crash recovery")
	}
	if shouldRetryCoreExit(true, 3) {
		t.Fatal("crash recovery must stop after the retry budget is exhausted")
	}
}

func TestSupervisorLatencyLimits(t *testing.T) {
	if coreReadinessTimeout != 8*time.Second {
		t.Fatalf("readiness timeout=%s, want 8s", coreReadinessTimeout)
	}
	if coreStopTimeout != 2*time.Second {
		t.Fatalf("stop timeout=%s, want 2s", coreStopTimeout)
	}
}

func writeCoreReadinessFixture(t *testing.T, runtimeDir string, pid int, startTime string, attached bool) {
	t.Helper()
	value := 0
	if attached {
		value = 1
	}
	payload := fmt.Sprintf(
		"pid=%d\nstarttime=%s\nstartup_health=healthy\nsystem_resolver_attached=%d\n",
		pid,
		startTime,
		value,
	)
	if err := os.WriteFile(filepath.Join(runtimeDir, "core.ready"), []byte(payload), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestAuthenticatedStartupGateRequiresTwoAuthorizedChecks(t *testing.T) {
	runtimeDir := t.TempDir()
	pid := os.Getpid()
	startTime := processStartTime(pid)
	writeCoreReadinessFixture(t, runtimeDir, pid, startTime, true)
	checks := 0
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "Bearer fixture-secret" {
			writer.WriteHeader(http.StatusUnauthorized)
			return
		}
		checks++
		writer.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	endpoint := strings.TrimPrefix(server.URL, "http://")

	if err := waitForAuthenticatedController(
		runtimeDir,
		pid,
		startTime,
		endpoint,
		"fixture-secret",
		500*time.Millisecond,
		server.Client(),
	); err != nil {
		t.Fatal(err)
	}
	if checks < 2 {
		t.Fatalf("authenticated startup gate made %d checks, want at least 2", checks)
	}
	ready, err := os.ReadFile(filepath.Join(runtimeDir, "startup.ready"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(ready), "controller_authenticated=1") {
		t.Fatalf("startup readiness did not record authenticated controller: %s", ready)
	}
}

func TestAuthenticatedStartupGateRejectsUnauthorizedController(t *testing.T) {
	runtimeDir := t.TempDir()
	pid := os.Getpid()
	startTime := processStartTime(pid)
	writeCoreReadinessFixture(t, runtimeDir, pid, startTime, true)
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()
	endpoint := strings.TrimPrefix(server.URL, "http://")

	err := waitForAuthenticatedController(
		runtimeDir,
		pid,
		startTime,
		endpoint,
		"wrong-secret",
		250*time.Millisecond,
		server.Client(),
	)
	if err == nil || !strings.Contains(err.Error(), "authentication") {
		t.Fatalf("unauthorized controller error=%v", err)
	}
}

func TestAuthenticatedStartupGateRequiresConsecutiveSuccesses(t *testing.T) {
	runtimeDir := t.TempDir()
	pid := os.Getpid()
	startTime := processStartTime(pid)
	writeCoreReadinessFixture(t, runtimeDir, pid, startTime, true)
	checks := 0
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		checks++
		if checks == 1 {
			writer.WriteHeader(http.StatusOK)
			return
		}
		writer.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()
	endpoint := strings.TrimPrefix(server.URL, "http://")

	err := waitForAuthenticatedController(
		runtimeDir, pid, startTime, endpoint, "fixture-secret", 250*time.Millisecond, server.Client(),
	)
	if err == nil || !strings.Contains(err.Error(), "authentication") {
		t.Fatalf("single successful check was accepted: checks=%d error=%v", checks, err)
	}
	if _, err := os.Stat(filepath.Join(runtimeDir, "startup.ready")); !os.IsNotExist(err) {
		t.Fatalf("startup marker was written after one successful check: %v", err)
	}
}

func TestAuthenticatedStartupGateRejectsPIDStartTimeMismatch(t *testing.T) {
	runtimeDir := t.TempDir()
	pid := os.Getpid()
	writeCoreReadinessFixture(t, runtimeDir, pid, "mismatched", true)
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	endpoint := strings.TrimPrefix(server.URL, "http://")

	err := waitForAuthenticatedController(
		runtimeDir, pid, processStartTime(pid), endpoint, "fixture-secret", 250*time.Millisecond, server.Client(),
	)
	if err == nil || !strings.Contains(err.Error(), "system resolver") {
		t.Fatalf("mismatched core marker was accepted: %v", err)
	}
}

func TestAuthenticatedStartupGateRequiresResolverMarker(t *testing.T) {
	runtimeDir := t.TempDir()
	pid := os.Getpid()
	startTime := processStartTime(pid)
	writeCoreReadinessFixture(t, runtimeDir, pid, startTime, false)
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	endpoint := strings.TrimPrefix(server.URL, "http://")

	err := waitForAuthenticatedController(
		runtimeDir,
		pid,
		startTime,
		endpoint,
		"fixture-secret",
		250*time.Millisecond,
		server.Client(),
	)
	if err == nil || !strings.Contains(err.Error(), "system resolver") {
		t.Fatalf("resolver gate error=%v", err)
	}
}

func TestSafeCoreFailureCategoryClassifiesEbpfPanicWithoutLeakingLog(t *testing.T) {
	logPath := filepath.Join(t.TempDir(), "core.log")
	payload := "node=private.example:443\npanic: cause on an nil error\n" +
		"github.com/sagernet/sing-box/protocol/ebpf.(*Inbound).Start\n"
	if err := os.WriteFile(logPath, []byte(payload), 0o600); err != nil {
		t.Fatal(err)
	}

	if category := safeCoreFailureCategory(logPath); category != "ebpf_startup_panic" {
		t.Fatalf("unexpected category: %q", category)
	}
}

func TestSafeCoreFailureCategoryDoesNotReturnArbitraryLogText(t *testing.T) {
	logPath := filepath.Join(t.TempDir(), "core.log")
	if err := os.WriteFile(
		logPath,
		[]byte("dial private.example:443 failed with secret-token\n"),
		0o600,
	); err != nil {
		t.Fatal(err)
	}

	if category := safeCoreFailureCategory(logPath); category != "" {
		t.Fatalf("arbitrary log text was exposed as %q", category)
	}
}
