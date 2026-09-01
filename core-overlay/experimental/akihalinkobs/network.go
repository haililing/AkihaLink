//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"errors"
	"fmt"
	"sort"
	"strings"
	"syscall"
	"time"

	netlink "github.com/sagernet/netlink"
	"github.com/sagernet/netlink/nl"
	"golang.org/x/sys/unix"
)

func (state *daemon) watchNetwork() {
	var linkUpdates chan netlink.LinkUpdate
	var addressUpdates chan netlink.AddrUpdate
	var routeUpdates chan netlink.RouteUpdate
	done := make(chan struct{})
	resync := make(chan string, 8)
	errorCallback := func(err error) {
		if errors.Is(err, syscall.ENOBUFS) || strings.Contains(strings.ToLower(err.Error()), "no buffer") {
			select {
			case resync <- "enobufs":
			default:
			}
		}
	}
	subscribeLink := func() {
		channel := make(chan netlink.LinkUpdate, 32)
		if err := netlink.LinkSubscribeWithOptions(channel, done, netlink.LinkSubscribeOptions{
			ErrorCallback: errorCallback, ListExisting: true,
		}); err != nil {
			state.recordNetworkEvent("observer_error", "", 0, false, false, false, "link")
			return
		}
		linkUpdates = channel
	}
	subscribeAddress := func() {
		channel := make(chan netlink.AddrUpdate, 32)
		if err := netlink.AddrSubscribeWithOptions(channel, done, netlink.AddrSubscribeOptions{
			ErrorCallback: errorCallback, ListExisting: true,
		}); err != nil {
			state.recordNetworkEvent("observer_error", "", 0, false, false, false, "address")
			return
		}
		addressUpdates = channel
	}
	subscribeRoute := func() {
		channel := make(chan netlink.RouteUpdate, 32)
		if err := netlink.RouteSubscribeWithOptions(channel, done, netlink.RouteSubscribeOptions{
			ErrorCallback: errorCallback, ListExisting: true,
		}); err != nil {
			state.recordNetworkEvent("observer_error", "", 0, false, false, false, "route")
			return
		}
		routeUpdates = channel
	}
	subscribeLink()
	subscribeAddress()
	subscribeRoute()
	go state.watchRuleGroups(done, resync)
	state.fullNetworkDump("initial")

	var restartTimer *time.Timer
	var restartChannel <-chan time.Time
	restartDelay := time.Second
	scheduleRestart := func() {
		if restartChannel != nil {
			return
		}
		if restartTimer == nil {
			restartTimer = time.NewTimer(restartDelay)
		} else {
			restartTimer.Reset(restartDelay)
		}
		restartChannel = restartTimer.C
	}
	if linkUpdates == nil || addressUpdates == nil || routeUpdates == nil {
		scheduleRestart()
	}
	var debounce *time.Timer
	var debounceChannel <-chan time.Time
	pending := ""
	trigger := func(kind string) {
		pending = kind
		if debounce == nil {
			debounce = time.NewTimer(200 * time.Millisecond)
		} else {
			if !debounce.Stop() {
				select {
				case <-debounce.C:
				default:
				}
			}
			debounce.Reset(200 * time.Millisecond)
		}
		debounceChannel = debounce.C
	}
	for {
		select {
		case update, ok := <-linkUpdates:
			if !ok {
				linkUpdates = nil
				trigger("link_subscription_restart")
				scheduleRestart()
			} else {
				name := ""
				mtu := 0
				if update.Link != nil && update.Link.Attrs() != nil {
					name = update.Link.Attrs().Name
					mtu = update.Link.Attrs().MTU
				}
				state.recordNetworkEvent("link", name, mtu, false, false, false, "")
				trigger("link")
			}
		case update, ok := <-addressUpdates:
			if !ok {
				addressUpdates = nil
				trigger("address_subscription_restart")
				scheduleRestart()
			} else {
				name := linkName(int(update.LinkIndex))
				ipv4 := update.LinkAddress.IP.To4() != nil
				state.recordNetworkEvent("address", name, 0, ipv4, !ipv4, false, "")
				trigger("address")
			}
		case update, ok := <-routeUpdates:
			if !ok {
				routeUpdates = nil
				trigger("route_subscription_restart")
				scheduleRestart()
			} else {
				name := linkName(update.LinkIndex)
				defaultRoute := update.Dst == nil
				state.recordNetworkEvent("route", name, 0, false, false, defaultRoute, "")
				trigger("route")
			}
		case kind := <-resync:
			state.fullNetworkDump(kind)
		case <-debounceChannel:
			state.fullNetworkDump(pending)
			debounceChannel = nil
		case <-restartChannel:
			restartChannel = nil
			if linkUpdates == nil {
				subscribeLink()
			}
			if addressUpdates == nil {
				subscribeAddress()
			}
			if routeUpdates == nil {
				subscribeRoute()
			}
			if linkUpdates == nil || addressUpdates == nil || routeUpdates == nil {
				restartDelay = min(restartDelay*2, time.Minute)
				scheduleRestart()
			} else {
				restartDelay = time.Second
			}
		case <-state.stop:
			close(done)
			if debounce != nil {
				debounce.Stop()
			}
			if restartTimer != nil {
				restartTimer.Stop()
			}
			return
		}
	}
}

func (state *daemon) watchRuleGroups(done <-chan struct{}, resync chan<- string) {
	retryDelay := time.Second
	for {
		select {
		case <-done:
			return
		default:
		}
		socket, err := nl.Subscribe(
			unix.NETLINK_ROUTE,
			unix.RTNLGRP_IPV4_RULE,
			unix.RTNLGRP_IPV6_RULE,
		)
		if err != nil {
			select {
			case <-time.After(retryDelay):
				retryDelay = min(retryDelay*2, time.Minute)
				continue
			case <-done:
				return
			}
		}
		subscribedAt := time.Now()
		socketDone := make(chan struct{})
		go func() {
			select {
			case <-done:
				socket.Close()
			case <-socketDone:
			}
		}()
		for {
			messages, _, receiveErr := socket.Receive()
			if receiveErr != nil {
				if time.Since(subscribedAt) >= time.Minute {
					retryDelay = time.Second
				}
				if errors.Is(receiveErr, syscall.ENOBUFS) {
					select {
					case resync <- "enobufs":
					default:
					}
				}
				close(socketDone)
				socket.Close()
				break
			}
			retryDelay = time.Second
			for _, message := range messages {
				if message.Header.Type == unix.RTM_NEWRULE || message.Header.Type == unix.RTM_DELRULE {
					state.recordNetworkEvent("rule", "", 0, false, false, false, "")
					select {
					case resync <- "rule":
					default:
					}
				}
			}
		}
		select {
		case resync <- "rule_subscription_restart":
		default:
		}
		select {
		case <-time.After(retryDelay):
			retryDelay = min(retryDelay*2, time.Minute)
		case <-done:
			return
		}
	}
}

func (state *daemon) fullNetworkDump(reason string) {
	links, linkErr := netlink.LinkList()
	routes4, route4Err := netlink.RouteList(nil, netlink.FAMILY_V4)
	routes6, route6Err := netlink.RouteList(nil, netlink.FAMILY_V6)
	rules4, rule4Err := netlink.RuleList(netlink.FAMILY_V4)
	rules6, rule6Err := netlink.RuleList(netlink.FAMILY_V6)
	if linkErr != nil || route4Err != nil || route6Err != nil || rule4Err != nil || rule6Err != nil {
		state.recordNetworkEvent("resync_error", "", 0, false, false, false, reason)
		return
	}
	defaults := make(map[int]bool)
	signatureParts := make([]string, 0, len(routes4)+len(routes6)+len(links))
	appendDefaultRoutes := func(family int, routes []netlink.Route) {
		for _, route := range routes {
			if route.Dst != nil {
				continue
			}
			defaults[route.LinkIndex] = true
			signatureParts = append(signatureParts, fmt.Sprintf(
				"route:%d:%d:%s:%s:%d:%d",
				family, route.LinkIndex, route.Gw.String(), route.Src.String(), route.Table, route.Priority,
			))
		}
	}
	appendDefaultRoutes(4, routes4)
	appendDefaultRoutes(6, routes6)
	defaultMTU := 0
	for _, route := range append(routes4, routes6...) {
		if route.Dst == nil {
			defaults[route.LinkIndex] = true
		}
	}
	for _, link := range links {
		if link == nil || link.Attrs() == nil {
			continue
		}
		addresses, _ := netlink.AddrList(link, netlink.FAMILY_ALL)
		ipv4, ipv6 := false, false
		for _, address := range addresses {
			if address.IP.To4() != nil {
				ipv4 = true
			} else {
				ipv6 = true
			}
		}
		state.recordNetworkEvent(
			"snapshot", link.Attrs().Name, link.Attrs().MTU, ipv4, ipv6,
			defaults[link.Attrs().Index],
			fmt.Sprintf("%s;rules=%d", reason, len(rules4)+len(rules6)),
		)
		if defaults[link.Attrs().Index] && link.Attrs().MTU > 0 {
			if defaultMTU == 0 || link.Attrs().MTU < defaultMTU {
				defaultMTU = link.Attrs().MTU
			}
			signatureParts = append(signatureParts, fmt.Sprintf(
				"link:%d:%s:%d:%t:%t",
				link.Attrs().Index, link.Attrs().Name, link.Attrs().MTU, ipv4, ipv6,
			))
		}
	}
	sort.Strings(signatureParts)
	state.applyNetworkPathSnapshot(strings.Join(signatureParts, ";"), defaultMTU)
}

func (state *daemon) applyNetworkPathSnapshot(signature string, defaultMTU int) {
	state.mu.Lock()
	changed := state.networkPathSignature != "" && state.networkPathSignature != signature
	state.networkPathSignature = signature
	state.defaultRouteMTU = defaultMTU
	if changed {
		state.routeGeneration++
		state.paths = make(map[string]*pathAccumulator)
		for _, flow := range state.flows {
			flow.failure = "network_changed"
		}
		state.failures["network_changed"]++
	}
	state.mu.Unlock()
	if changed {
		state.requestTCPSample()
	}
}

func (state *daemon) recordNetworkEvent(
	eventType, interfaceName string,
	mtu int,
	ipv4, ipv6, defaultRoute bool,
	detail string,
) {
	state.mu.Lock()
	defer state.mu.Unlock()
	state.networkSequence++
	state.networkEvents = append(state.networkEvents, NetworkEvent{
		Sequence: state.networkSequence, Timestamp: time.Now().UnixMilli(),
		RouteGeneration: state.routeGeneration,
		Type:            eventType, Interface: interfaceName, MTU: mtu,
		IPv4: ipv4, IPv6: ipv6, DefaultRoute: defaultRoute, Detail: detail,
	})
	if len(state.networkEvents) > 500 {
		state.networkEvents = state.networkEvents[len(state.networkEvents)-500:]
	}
}

func (state *daemon) recentNetworkEvents(limit int) []NetworkEvent {
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	start := max(0, len(state.networkEvents)-limit)
	result := append([]NetworkEvent(nil), state.networkEvents[start:]...)
	for left, right := 0, len(result)-1; left < right; left, right = left+1, right-1 {
		result[left], result[right] = result[right], result[left]
	}
	return result
}

func linkName(index int) string {
	if index <= 0 {
		return ""
	}
	link, err := netlink.LinkByIndex(index)
	if err != nil || link == nil || link.Attrs() == nil {
		return ""
	}
	return link.Attrs().Name
}
