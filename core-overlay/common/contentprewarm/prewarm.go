package contentprewarm

import (
	"context"
	"errors"
	"net/netip"
	"os"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
)

const startupProbeName = "www.gstatic.com"

var (
	startupTimeout = 5 * time.Second
	retryDelay     = 250 * time.Millisecond
)

func Start(ctx context.Context, router adapter.DNSRouter) error {
	if os.Getenv("AKIHALINK_DNS_PREWARM") != "1" || router == nil {
		return nil
	}
	return run(ctx, router)
}

func run(parent context.Context, router adapter.DNSRouter) error {
	ctx, cancel := context.WithTimeout(parent, startupTimeout)
	defer cancel()
	consecutive := 0
	for {
		addresses, err := router.Lookup(ctx, startupProbeName, adapter.DNSQueryOptions{
			Strategy:               C.DomainStrategyIPv4Only,
			DisableCache:           true,
			DisableOptimisticCache: true,
		})
		if err == nil && validAResponse(addresses) {
			consecutive++
			if consecutive >= 2 {
				return nil
			}
			continue
		}
		consecutive = 0
		timer := time.NewTimer(retryDelay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return errors.New("proxy DNS startup health check did not produce two consecutive valid A responses")
		case <-timer.C:
		}
	}
}

func validAResponse(addresses []netip.Addr) bool {
	for _, address := range addresses {
		if address.Is4() && !address.IsUnspecified() && !address.IsLoopback() && !address.IsMulticast() {
			return true
		}
	}
	return false
}
