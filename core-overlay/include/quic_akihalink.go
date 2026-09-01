//go:build with_quic && with_akihalink_minimal_registry

package include

import (
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing-box/protocol/tuic"
	_ "github.com/sagernet/sing-box/transport/v2rayquic"
)

func registerQUICInbounds(*inbound.Registry) {}

func registerQUICOutbounds(registry *outbound.Registry) {
	hysteria2.RegisterOutbound(registry)
	tuic.RegisterOutbound(registry)
}

func registerQUICTransports(*dns.TransportRegistry) {}

func registerQUICServices(*service.Registry) {}
