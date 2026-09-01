//go:build with_akihalink_observability

package akihalinkobs

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var traceIDPattern = regexp.MustCompile(`^[0-9a-f]{16}$`)

func currentTraceID(runtimeDir string) string {
	if traceID := strings.TrimSpace(os.Getenv("AKIHALINK_TRACE_ID")); traceIDPattern.MatchString(traceID) {
		return traceID
	}
	if runtimeDir == "" {
		runtimeDir = strings.TrimSpace(os.Getenv("AKIHALINK_RUNTIME"))
	}
	if runtimeDir == "" {
		return ""
	}
	value, err := os.ReadFile(filepath.Join(runtimeDir, "trace-context"))
	if err != nil {
		return ""
	}
	traceID := strings.TrimSpace(string(value))
	if !traceIDPattern.MatchString(traceID) {
		return ""
	}
	return traceID
}

func traceMarkerPath() string {
	candidates := []string{
		strings.TrimSpace(os.Getenv("AKIHALINK_TRACE_MARKER")),
		"/sys/kernel/tracing/trace_marker",
		"/sys/kernel/debug/tracing/trace_marker",
	}
	for _, candidate := range candidates {
		if candidate == "" {
			continue
		}
		file, err := os.OpenFile(candidate, os.O_WRONLY|os.O_APPEND, 0)
		if err == nil {
			_ = file.Close()
			return candidate
		}
	}
	return ""
}

func writeTraceMarker(record string) {
	path := traceMarkerPath()
	if path == "" {
		return
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		return
	}
	_, _ = fmt.Fprintln(file, record)
	_ = file.Close()
}

func traceBegin(stage, traceID string) {
	if !traceIDPattern.MatchString(traceID) {
		return
	}
	writeTraceMarker(fmt.Sprintf("B|%d|%s/%s", os.Getpid(), stage, traceID))
}

func traceEnd(traceID string) {
	if !traceIDPattern.MatchString(traceID) {
		return
	}
	writeTraceMarker(fmt.Sprintf("E|%d", os.Getpid()))
}

func traceInstant(stage, traceID string) {
	if !traceIDPattern.MatchString(traceID) {
		return
	}
	writeTraceMarker(fmt.Sprintf("I|%d|%s/%s|p", os.Getpid(), stage, traceID))
}
