package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"

	box "github.com/sagernet/sing-box"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/deprecated"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: configcheck <config>...")
		os.Exit(2)
	}
	C.Version = "v1.15.0-alpha.2-10e9a425-akihalink-upstream-ebpf-v17"
	ctx := include.Context(service.ContextWith(context.Background(), deprecated.NewStderrManager(log.StdLogger())))
	for _, path := range os.Args[1:] {
		content, err := os.ReadFile(path)
		if err != nil {
			fatal(path, err)
		}
		options, err := json.UnmarshalExtendedContext[option.Options](ctx, content)
		if err != nil {
			fatal(path, err)
		}
		ebpfInboundCount, err := validateEBPFInbounds(path, options.Inbounds)
		if err != nil {
			fatal(path, err)
		}
		// Host validation cannot construct the Android-only eBPF inbound or load device paths.
		// Keep DNS, routing defaults, and outbounds so box.New still performs dialer semantics.
		options.Inbounds = nil
		options.Experimental = nil
		if options.Route != nil {
			options.Route.Rules = nil
			options.Route.RuleSet = nil
		}
		if options.DNS != nil {
			options.DNS.Rules = nil
		}
		instance, err := box.New(box.Options{Context: ctx, Options: options})
		if err != nil {
			fatal(path, err)
		}
		instance.Close()
		fmt.Printf("valid %s ebpf_inbounds=%d\n", path, ebpfInboundCount)
	}
}

func validateEBPFInbounds(path string, inbounds []option.Inbound) (int, error) {
	count := 0
	for _, inbound := range inbounds {
		if inbound.Type != C.TypeEBPF {
			continue
		}
		options, loaded := inbound.Options.(*option.EBPFInboundOptions)
		if !loaded || options == nil {
			return 0, fmt.Errorf("eBPF inbound options were not decoded by the pinned core")
		}
		localEnabled, sharedEnabled := options.EffectiveEnablement()
		if !localEnabled || options.Local.DataPlane != "cgroup" {
			return 0, fmt.Errorf("AkihaLink requires local.enabled and local.data_plane=cgroup")
		}
		if options.FakeIPICMP != "" && options.FakeIPICMP != "off" {
			return 0, fmt.Errorf("FakeIP ICMP replies are not enabled by AkihaLink")
		}
		networks := options.Network.Build()
		if len(networks) != 2 || networks[0] != "tcp" || networks[1] != "udp" {
			return 0, fmt.Errorf("eBPF network must be [tcp, udp]")
		}
		if options.Local.DNSMode != "hijack" {
			return 0, fmt.Errorf("local.dns_mode must be hijack")
		}
		if len(options.Local.IncludeUID) != 0 || len(options.Local.IncludeUIDRange) != 0 {
			return 0, fmt.Errorf("AkihaLink local include UID lists must remain empty")
		}
		if !sharedEnabled {
			if options.Shared.DNSMode != "" || options.Shared.AndroidTethering != "" ||
				len(options.Shared.Interface) != 0 || options.Shared.DataPlane != "" {
				return 0, fmt.Errorf("local mode must not contain shared options")
			}
		} else {
			if options.Shared.DNSMode != "hijack" || options.Shared.AndroidTethering != "wifi" {
				return 0, fmt.Errorf("shared interception requires shared DNS hijack and Android Wi-Fi tethering discovery")
			}
			if len(options.Shared.Interface) != 0 {
				return 0, fmt.Errorf("dynamic Android tethering must not pin shared.interface")
			}
			if options.TCPriority != 1 || options.Shared.DataPlane != "packet_rewrite" {
				return 0, fmt.Errorf("shared interception requires tc_priority 1 and packet_rewrite data plane")
			}
		}
		count++
	}
	if count == 0 && filepath.Base(path) != "speedtest.json" {
		return 0, fmt.Errorf("configuration does not contain a decoded eBPF inbound")
	}
	return count, nil
}

func fatal(path string, err error) {
	fmt.Fprintln(os.Stderr, path+":", err)
	os.Exit(1)
}
