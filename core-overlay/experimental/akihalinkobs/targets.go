//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"os"
	"strconv"
	"time"

	netlink "github.com/sagernet/netlink"
)

type outboundEndpoint struct {
	Type       string `json:"type"`
	Tag        string `json:"tag"`
	Default    string `json:"default"`
	Server     string `json:"server"`
	ServerPort uint16 `json:"server_port"`
}

func (state *daemon) refreshProxyTargets(overrides ...string) error {
	payload, err := os.ReadFile(state.configPath)
	if err != nil {
		return err
	}
	var config struct {
		Outbounds []outboundEndpoint `json:"outbounds"`
	}
	if err = json.Unmarshal(payload, &config); err != nil {
		return err
	}
	selectedTag := ""
	byTag := make(map[string]outboundEndpoint, len(config.Outbounds))
	for _, outbound := range config.Outbounds {
		byTag[outbound.Tag] = outbound
		if outbound.Type == "selector" &&
			(outbound.Tag == "AkihaLink" || outbound.Tag == "proxy") {
			selectedTag = outbound.Default
		}
	}
	if len(overrides) > 0 && overrides[0] != "" {
		selectedTag = overrides[0]
	}
	selected, exists := byTag[selectedTag]
	if !exists || selected.Server == "" || selected.ServerPort == 0 {
		return errors.New("selected proxy endpoint unavailable")
	}

	addresses := make([]netip.Addr, 0, 4)
	if address, parseErr := netip.ParseAddr(selected.Server); parseErr == nil {
		addresses = append(addresses, address.Unmap())
	} else {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		resolved, resolveErr := net.DefaultResolver.LookupNetIP(ctx, "ip", selected.Server)
		if resolveErr != nil {
			return resolveErr
		}
		for _, address := range resolved {
			addresses = append(addresses, address.Unmap())
		}
	}
	if len(addresses) == 0 {
		return errors.New("selected proxy endpoint did not resolve")
	}
	targets := make(map[string]struct{}, len(addresses))
	for _, address := range addresses {
		targets[targetKey(address, selected.ServerPort)] = struct{}{}
	}
	state.mu.Lock()
	state.proxyTargets = targets
	fingerprint := ""
	if len(overrides) > 1 {
		fingerprint = overrides[1]
	}
	if validFingerprint(fingerprint) {
		state.currentFingerprint = fingerprint
	}
	state.currentTargetTag = selectedTag
	if len(overrides) > 0 && overrides[0] != "" {
		state.flows = make(map[uint64]*flowRecord)
		state.pendingBPF = make(map[uint64][]bpfEvent)
		state.completed = nil
		state.failures = make(map[string]uint64)
	}
	if state.degradedReason == "proxy_target_unresolved" {
		state.degradedReason = ""
	}
	state.mu.Unlock()
	return nil
}

func (state *daemon) isProxyTarget(socket *netlink.Socket) bool {
	address, ok := netip.AddrFromSlice(socket.ID.Destination)
	if !ok {
		return false
	}
	key := targetKey(address.Unmap(), socket.ID.DestinationPort)
	state.mu.Lock()
	defer state.mu.Unlock()
	_, exists := state.proxyTargets[key]
	return exists
}

func targetKey(address netip.Addr, port uint16) string {
	return address.String() + ":" + strconv.FormatUint(uint64(port), 10)
}
