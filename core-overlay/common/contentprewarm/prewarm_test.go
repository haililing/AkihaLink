package contentprewarm

import (
	"context"
	"errors"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

type recordingDNSRouter struct {
	adapter.DNSRouter
	mu        sync.Mutex
	responses [][]netip.Addr
	errors    []error
	queries   []adapter.DNSQueryOptions
}

func TestDefaultStartupTimeoutIsFiveSeconds(t *testing.T) {
	if startupTimeout != 5*time.Second {
		t.Fatalf("startup timeout=%s, want 5s", startupTimeout)
	}
}

func (r *recordingDNSRouter) Lookup(
	_ context.Context,
	_ string,
	options adapter.DNSQueryOptions,
) ([]netip.Addr, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.queries = append(r.queries, options)
	index := len(r.queries) - 1
	var addresses []netip.Addr
	var err error
	if index < len(r.responses) {
		addresses = r.responses[index]
	}
	if index < len(r.errors) {
		err = r.errors[index]
	}
	return addresses, err
}

func TestRunRequiresTwoConsecutiveUncachedAResponses(t *testing.T) {
	router := &recordingDNSRouter{
		responses: [][]netip.Addr{
			{netip.MustParseAddr("203.0.113.1")},
			nil,
			{netip.MustParseAddr("203.0.113.2")},
			{netip.MustParseAddr("203.0.113.3")},
		},
		errors: []error{nil, errors.New("temporary"), nil, nil},
	}
	oldTimeout, oldDelay := startupTimeout, retryDelay
	startupTimeout, retryDelay = time.Second, time.Millisecond
	defer func() { startupTimeout, retryDelay = oldTimeout, oldDelay }()

	if err := run(context.Background(), router); err != nil {
		t.Fatal(err)
	}
	if len(router.queries) != 4 {
		t.Fatalf("queries=%d, want 4", len(router.queries))
	}
	for _, query := range router.queries {
		if !query.DisableCache || !query.DisableOptimisticCache {
			t.Fatalf("startup query used cache: %#v", query)
		}
	}
}

func TestRunTimesOutWithoutValidAResponse(t *testing.T) {
	router := &recordingDNSRouter{}
	oldTimeout, oldDelay := startupTimeout, retryDelay
	startupTimeout, retryDelay = 20*time.Millisecond, time.Millisecond
	defer func() { startupTimeout, retryDelay = oldTimeout, oldDelay }()

	if err := run(context.Background(), router); err == nil {
		t.Fatal("expected startup health check failure")
	}
}

func TestValidAResponseRejectsProbeAndNonIPv4Addresses(t *testing.T) {
	if validAResponse([]netip.Addr{
		netip.IPv4Unspecified(),
		netip.MustParseAddr("127.0.0.1"),
		netip.MustParseAddr("ff02::1"),
		netip.MustParseAddr("2001:db8::1"),
	}) {
		t.Fatal("invalid response accepted")
	}
}
