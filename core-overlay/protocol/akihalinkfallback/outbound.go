package akihalinkfallback

import (
	"context"
	"errors"
	"fmt"
	"net"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const Type = "akihalink-fallback"

type Options struct {
	Primary        string             `json:"primary" reference:"outbound"`
	Fallback       string             `json:"fallback" reference:"outbound"`
	ConnectTimeout badoption.Duration `json:"connect_timeout"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[Options](registry, Type, NewOutbound)
}

type Outbound struct {
	outbound.Adapter
	outboundManager adapter.OutboundManager
	primaryTag      string
	fallbackTag     string
	connectTimeout  time.Duration
	primary         adapter.Outbound
	fallback        adapter.Outbound
}

func NewOutbound(
	ctx context.Context,
	_ adapter.Router,
	_ log.ContextLogger,
	tag string,
	options Options,
) (adapter.Outbound, error) {
	if options.Primary == "" {
		return nil, errors.New("missing primary outbound")
	}
	if options.Fallback == "" {
		return nil, errors.New("missing fallback outbound")
	}
	if options.Primary == options.Fallback {
		return nil, errors.New("primary and fallback outbounds must differ")
	}
	connectTimeout := time.Duration(options.ConnectTimeout)
	if connectTimeout <= 0 {
		return nil, errors.New("connect_timeout must be positive")
	}
	return &Outbound{
		Adapter:         outbound.NewAdapter(Type, tag, []string{N.NetworkTCP, N.NetworkUDP}, []string{options.Primary, options.Fallback}),
		outboundManager: service.FromContext[adapter.OutboundManager](ctx),
		primaryTag:      options.Primary,
		fallbackTag:     options.Fallback,
		connectTimeout:  connectTimeout,
	}, nil
}

func (o *Outbound) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	primary, loaded := o.outboundManager.Outbound(o.primaryTag)
	if !loaded {
		return fmt.Errorf("primary outbound not found: %s", o.primaryTag)
	}
	fallback, loaded := o.outboundManager.Outbound(o.fallbackTag)
	if !loaded {
		return fmt.Errorf("fallback outbound not found: %s", o.fallbackTag)
	}
	o.primary = primary
	o.fallback = fallback
	return nil
}

func (o *Outbound) Close() error {
	return nil
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if N.NetworkName(network) != N.NetworkTCP {
		return o.primary.DialContext(ctx, network, destination)
	}
	directContext, cancelDirect := context.WithTimeout(ctx, o.connectTimeout)
	connection, directErr := o.primary.DialContext(directContext, network, destination)
	cancelDirect()
	if directErr == nil {
		return connection, nil
	}
	if connection != nil {
		_ = connection.Close()
	}
	if ctx.Err() != nil {
		return nil, directErr
	}
	connection, fallbackErr := o.fallback.DialContext(ctx, network, destination)
	if fallbackErr == nil {
		return connection, nil
	}
	if connection != nil {
		_ = connection.Close()
	}
	return nil, errors.Join(
		fmt.Errorf("primary outbound failed: %w", directErr),
		fmt.Errorf("fallback outbound failed: %w", fallbackErr),
	)
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return o.primary.ListenPacket(ctx, destination)
}
