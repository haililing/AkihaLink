//go:build with_ebpf && (linux || android)

package ebpf

import (
	"errors"
	"strings"
	"testing"
)

func TestCombineStartFailureDoesNotWrapNilCleanup(t *testing.T) {
	primary := errors.New("attach rejected")
	result := combineStartError(primary, nil)
	if !errors.Is(result, primary) {
		t.Fatalf("primary error was lost: %v", result)
	}
}

func TestCombineStartFailureIncludesCleanupError(t *testing.T) {
	primary := errors.New("attach rejected")
	cleanup := errors.New("listener close failed")
	result := combineStartError(primary, cleanup)
	if result == nil ||
		!strings.Contains(result.Error(), primary.Error()) ||
		!strings.Contains(result.Error(), cleanup.Error()) {
		t.Fatalf("combined error is incomplete: %v", result)
	}
}
