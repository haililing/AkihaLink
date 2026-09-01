//go:build with_akihalink_observability

package akihalinkobs

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTraceMarkersContainOnlyStageAndAnonymousID(t *testing.T) {
	marker := filepath.Join(t.TempDir(), "trace_marker")
	if err := os.WriteFile(marker, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("AKIHALINK_TRACE_MARKER", marker)

	traceBegin("AKL/ebpf_load", "0123456789abcdef")
	traceInstant("AKL/ebpf_attach", "0123456789abcdef")
	traceEnd("0123456789abcdef")

	value, err := os.ReadFile(marker)
	if err != nil {
		t.Fatal(err)
	}
	output := string(value)
	for _, expected := range []string{
		"AKL/ebpf_load/0123456789abcdef",
		"AKL/ebpf_attach/0123456789abcdef",
	} {
		if !strings.Contains(output, expected) {
			t.Fatalf("missing trace marker %q in %q", expected, output)
		}
	}
	for _, forbidden := range []string{"example.com", "192.0.2.1", `"outbounds"`} {
		if strings.Contains(output, forbidden) {
			t.Fatalf("trace marker leaked %q", forbidden)
		}
	}
}

func TestTraceRejectsUntrustedContext(t *testing.T) {
	runtimeDir := t.TempDir()
	if err := os.WriteFile(
		filepath.Join(runtimeDir, "trace-context"),
		[]byte("node.example/unsafe\n"),
		0o600,
	); err != nil {
		t.Fatal(err)
	}
	t.Setenv("AKIHALINK_TRACE_ID", "")
	if traceID := currentTraceID(runtimeDir); traceID != "" {
		t.Fatalf("accepted invalid trace ID %q", traceID)
	}
}

func TestTraceMarkerUnavailableIsNoOp(t *testing.T) {
	t.Setenv("AKIHALINK_TRACE_MARKER", filepath.Join(t.TempDir(), "missing", "trace_marker"))
	traceBegin("AKL/core_spawn", "0123456789abcdef")
	traceEnd("0123456789abcdef")
}
