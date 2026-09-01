package akihalinkfallback

import (
	"context"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

var testDestination = M.ParseSocksaddr("203.0.113.1:443")

func TestNewOutboundValidatesOptions(t *testing.T) {
	ctx := service.ContextWith[adapter.OutboundManager](context.Background(), &testOutboundManager{})
	for name, options := range map[string]Options{
		"missing primary":  {Fallback: "proxy", ConnectTimeout: badoption.Duration(time.Second)},
		"missing fallback": {Primary: "direct", ConnectTimeout: badoption.Duration(time.Second)},
		"same outbound":    {Primary: "direct", Fallback: "direct", ConnectTimeout: badoption.Duration(time.Second)},
		"zero timeout":     {Primary: "direct", Fallback: "proxy"},
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := NewOutbound(ctx, nil, log.NewNOPFactory().Logger(), "fallback", options); err == nil {
				t.Fatal("expected validation error")
			}
		})
	}
}

func TestDirectSuccessSkipsFallback(t *testing.T) {
	primary := &testOutbound{tag: "direct"}
	fallback := &testOutbound{tag: "proxy"}
	o := newStartedOutbound(t, primary, fallback, time.Second)

	connection, err := o.DialContext(context.Background(), N.NetworkTCP, testDestination)
	if err != nil {
		t.Fatal(err)
	}
	_ = connection.Close()
	if primary.dialCount() != 1 || fallback.dialCount() != 0 {
		t.Fatalf("unexpected attempts: primary=%d fallback=%d", primary.dialCount(), fallback.dialCount())
	}
}

func TestDirectErrorFallsBack(t *testing.T) {
	primary := &testOutbound{tag: "direct", dialErr: errors.New("direct failed")}
	fallback := &testOutbound{tag: "proxy"}
	o := newStartedOutbound(t, primary, fallback, time.Second)

	connection, err := o.DialContext(context.Background(), N.NetworkTCP, testDestination)
	if err != nil {
		t.Fatal(err)
	}
	_ = connection.Close()
	if primary.dialCount() != 1 || fallback.dialCount() != 1 {
		t.Fatalf("unexpected attempts: primary=%d fallback=%d", primary.dialCount(), fallback.dialCount())
	}
}

func TestDirectTimeoutFallsBack(t *testing.T) {
	primary := &testOutbound{
		tag: "direct",
		dial: func(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
			<-ctx.Done()
			return nil, ctx.Err()
		},
	}
	fallback := &testOutbound{tag: "proxy"}
	o := newStartedOutbound(t, primary, fallback, 20*time.Millisecond)

	started := time.Now()
	connection, err := o.DialContext(context.Background(), N.NetworkTCP, testDestination)
	if err != nil {
		t.Fatal(err)
	}
	_ = connection.Close()
	if elapsed := time.Since(started); elapsed < 15*time.Millisecond || elapsed > time.Second {
		t.Fatalf("unexpected fallback delay: %s", elapsed)
	}
	if fallback.dialCount() != 1 {
		t.Fatalf("fallback attempts=%d", fallback.dialCount())
	}
}

func TestParentCancellationDoesNotFallback(t *testing.T) {
	primary := &testOutbound{
		tag: "direct",
		dial: func(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
			<-ctx.Done()
			return nil, ctx.Err()
		},
	}
	fallback := &testOutbound{tag: "proxy"}
	o := newStartedOutbound(t, primary, fallback, time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := o.DialContext(ctx, N.NetworkTCP, testDestination)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected cancellation, got %v", err)
	}
	if fallback.dialCount() != 0 {
		t.Fatalf("fallback attempts=%d", fallback.dialCount())
	}
}

func TestBothErrorsAreReturned(t *testing.T) {
	directErr := errors.New("direct failed")
	fallbackErr := errors.New("proxy failed")
	o := newStartedOutbound(
		t,
		&testOutbound{tag: "direct", dialErr: directErr},
		&testOutbound{tag: "proxy", dialErr: fallbackErr},
		time.Second,
	)

	_, err := o.DialContext(context.Background(), N.NetworkTCP, testDestination)
	if !errors.Is(err, directErr) || !errors.Is(err, fallbackErr) {
		t.Fatalf("combined error does not preserve both causes: %v", err)
	}
}

func TestFallbackUsesCurrentSelectorChoice(t *testing.T) {
	primary := &testOutbound{tag: "direct", dialErr: errors.New("direct failed")}
	nodeA := &testOutbound{tag: "node-a"}
	nodeB := &testOutbound{tag: "node-b"}
	selector := &testSelector{tag: "proxy", selected: nodeA}
	o := newStartedOutbound(t, primary, selector, time.Second)

	connection, err := o.DialContext(context.Background(), N.NetworkTCP, testDestination)
	if err != nil {
		t.Fatal(err)
	}
	_ = connection.Close()
	selector.selectOutbound(nodeB)
	connection, err = o.DialContext(context.Background(), N.NetworkTCP, testDestination)
	if err != nil {
		t.Fatal(err)
	}
	_ = connection.Close()
	if nodeA.dialCount() != 1 || nodeB.dialCount() != 1 {
		t.Fatalf("selector did not update: node-a=%d node-b=%d", nodeA.dialCount(), nodeB.dialCount())
	}
}

func TestUDPAlwaysUsesPrimary(t *testing.T) {
	primary := &testOutbound{tag: "direct", packetConn: &testPacketConn{}}
	fallback := &testOutbound{tag: "proxy", dialErr: errors.New("must not be used")}
	o := newStartedOutbound(t, primary, fallback, time.Second)

	connection, err := o.ListenPacket(context.Background(), testDestination)
	if err != nil {
		t.Fatal(err)
	}
	_ = connection.Close()
	if primary.packetCount() != 1 || fallback.packetCount() != 0 {
		t.Fatalf("unexpected packet attempts: primary=%d fallback=%d", primary.packetCount(), fallback.packetCount())
	}
}

func TestFailuresAreNotCached(t *testing.T) {
	primary := &testOutbound{tag: "direct", dialErr: errors.New("direct failed")}
	fallback := &testOutbound{tag: "proxy"}
	o := newStartedOutbound(t, primary, fallback, time.Second)

	for range 2 {
		connection, err := o.DialContext(context.Background(), N.NetworkTCP, testDestination)
		if err != nil {
			t.Fatal(err)
		}
		_ = connection.Close()
	}
	if primary.dialCount() != 2 || fallback.dialCount() != 2 {
		t.Fatalf("attempts were cached: primary=%d fallback=%d", primary.dialCount(), fallback.dialCount())
	}
}

func newStartedOutbound(t *testing.T, primary adapter.Outbound, fallback adapter.Outbound, timeout time.Duration) *Outbound {
	t.Helper()
	manager := &testOutboundManager{outbounds: map[string]adapter.Outbound{
		primary.Tag():  primary,
		fallback.Tag(): fallback,
	}}
	ctx := service.ContextWith[adapter.OutboundManager](context.Background(), manager)
	created, err := NewOutbound(ctx, nil, log.NewNOPFactory().Logger(), "fallback", Options{
		Primary:        primary.Tag(),
		Fallback:       fallback.Tag(),
		ConnectTimeout: badoption.Duration(timeout),
	})
	if err != nil {
		t.Fatal(err)
	}
	o := created.(*Outbound)
	if err = o.Start(adapter.StartStateStart); err != nil {
		t.Fatal(err)
	}
	return o
}

type testOutboundManager struct {
	adapter.OutboundManager
	outbounds map[string]adapter.Outbound
}

func (m *testOutboundManager) Outbound(tag string) (adapter.Outbound, bool) {
	outbound, loaded := m.outbounds[tag]
	return outbound, loaded
}

type testOutbound struct {
	mu         sync.Mutex
	tag        string
	dial       func(context.Context, string, M.Socksaddr) (net.Conn, error)
	dialErr    error
	packetConn net.PacketConn
	dials      int
	packets    int
}

func (o *testOutbound) Type() string           { return "test" }
func (o *testOutbound) Tag() string            { return o.tag }
func (o *testOutbound) Network() []string      { return []string{N.NetworkTCP, N.NetworkUDP} }
func (o *testOutbound) Dependencies() []string { return nil }

func (o *testOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	o.mu.Lock()
	o.dials++
	o.mu.Unlock()
	if o.dial != nil {
		return o.dial(ctx, network, destination)
	}
	if o.dialErr != nil {
		return nil, o.dialErr
	}
	left, right := net.Pipe()
	_ = right.Close()
	return left, nil
}

func (o *testOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	o.mu.Lock()
	o.packets++
	o.mu.Unlock()
	if o.packetConn != nil {
		return o.packetConn, nil
	}
	return nil, o.dialErr
}

func (o *testOutbound) dialCount() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.dials
}

func (o *testOutbound) packetCount() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.packets
}

type testSelector struct {
	mu       sync.Mutex
	tag      string
	selected adapter.Outbound
}

func (s *testSelector) Type() string           { return "selector" }
func (s *testSelector) Tag() string            { return s.tag }
func (s *testSelector) Network() []string      { return []string{N.NetworkTCP, N.NetworkUDP} }
func (s *testSelector) Dependencies() []string { return nil }

func (s *testSelector) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	s.mu.Lock()
	selected := s.selected
	s.mu.Unlock()
	return selected.DialContext(ctx, network, destination)
}

func (s *testSelector) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	s.mu.Lock()
	selected := s.selected
	s.mu.Unlock()
	return selected.ListenPacket(ctx, destination)
}

func (s *testSelector) selectOutbound(outbound adapter.Outbound) {
	s.mu.Lock()
	s.selected = outbound
	s.mu.Unlock()
}

type testPacketConn struct{}

func (*testPacketConn) ReadFrom([]byte) (int, net.Addr, error) { return 0, nil, net.ErrClosed }
func (*testPacketConn) WriteTo(buffer []byte, _ net.Addr) (int, error) {
	return len(buffer), nil
}
func (*testPacketConn) Close() error                     { return nil }
func (*testPacketConn) LocalAddr() net.Addr              { return &net.UDPAddr{} }
func (*testPacketConn) SetDeadline(time.Time) error      { return nil }
func (*testPacketConn) SetReadDeadline(time.Time) error  { return nil }
func (*testPacketConn) SetWriteDeadline(time.Time) error { return nil }
