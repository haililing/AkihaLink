//go:build linux || android

package listener

import (
	"net"
	"net/netip"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

var testIPv4Loopback = net.IPv4(127, 0, 0, 1)

func TestMMsgUnavailableErrors(t *testing.T) {
	for _, errno := range []syscall.Errno{syscall.ENOSYS, syscall.EOPNOTSUPP, syscall.EPERM} {
		if !isMMsgUnavailable(errno) {
			t.Fatalf("%v must select the packet fallback", errno)
		}
	}
	if isMMsgUnavailable(syscall.EINVAL) {
		t.Fatal("data errors must not be hidden by the packet fallback")
	}
}

func TestWriteMsgUDPAddrPortBatchPreservesOrder(t *testing.T) {
	receiver, err := net.ListenUDP("udp4", &net.UDPAddr{IP: testIPv4Loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer receiver.Close()
	sender, err := net.ListenUDP("udp4", &net.UDPAddr{IP: testIPv4Loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	destination := receiver.LocalAddr().(*net.UDPAddr).AddrPort()
	payloads := [][]byte{[]byte("one"), []byte("two"), []byte("three")}
	if sent, unsupported, writeErr := writeMsgUDPAddrPortBatch(
		sender,
		payloads,
		make([][]byte, len(payloads)),
		[]netip.AddrPort{destination, destination, destination},
	); writeErr != nil || unsupported || sent != len(payloads) {
		t.Fatalf("batch write sent=%d unsupported=%v err=%v", sent, unsupported, writeErr)
	}
	if err = receiver.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 32)
	for index, expected := range payloads {
		count, _, readErr := receiver.ReadFromUDPAddrPort(buffer)
		if readErr != nil {
			t.Fatal(readErr)
		}
		if string(buffer[:count]) != string(expected) {
			t.Fatalf("datagram %d reordered: %q", index, buffer[:count])
		}
	}
}

func TestWriteMsgUDPAddrPortBatchHandlesPartialAndInterruptedSends(t *testing.T) {
	original := sendMessages
	defer func() { sendMessages = original }()
	calls := 0
	sendMessages = func(_ int, messages []oobMMsgHdr) (int, syscall.Errno) {
		calls++
		if calls == 1 {
			return 0, syscall.EINTR
		}
		return min(1, len(messages)), 0
	}
	connection, err := net.ListenUDP("udp4", &net.UDPAddr{IP: testIPv4Loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	destination := netip.MustParseAddrPort("127.0.0.1:9")
	sent, unsupported, err := writeMsgUDPAddrPortBatch(
		connection,
		[][]byte{[]byte("a"), []byte("b"), []byte("c")},
		[][]byte{nil, nil, nil},
		[]netip.AddrPort{destination, destination, destination},
	)
	if err != nil || unsupported || sent != 3 || calls != 4 {
		t.Fatalf("partial send sent=%d unsupported=%v calls=%d err=%v", sent, unsupported, calls, err)
	}
}

func TestWriteMsgUDPAddrPortBatchPermanentlyFallsBack(t *testing.T) {
	original := sendMessages
	defer func() { sendMessages = original }()
	calls := 0
	sendMessages = func(_ int, _ []oobMMsgHdr) (int, syscall.Errno) {
		calls++
		return 0, syscall.EPERM
	}
	receiver, err := net.ListenUDP("udp4", &net.UDPAddr{IP: testIPv4Loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer receiver.Close()
	sender, err := net.ListenUDP("udp4", &net.UDPAddr{IP: testIPv4Loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	listener := &Listener{udpConn: sender}
	destination := receiver.LocalAddr().(*net.UDPAddr).AddrPort()
	for _, payload := range []string{"first", "second"} {
		if err = listener.WriteMsgUDPAddrPortBatch(
			[][]byte{[]byte(payload)}, [][]byte{nil}, []netip.AddrPort{destination},
		); err != nil {
			t.Fatal(err)
		}
	}
	if calls != 1 || !listener.udpOOBBatchDisabled.Load() {
		t.Fatalf("fallback was not permanent: calls=%d disabled=%v", calls, listener.udpOOBBatchDisabled.Load())
	}
}

func TestOOBBatchReaderReportsUnavailableSyscall(t *testing.T) {
	original := receiveMessages
	defer func() { receiveMessages = original }()
	receiveMessages = func(_ int, _ []oobMMsgHdr) (int, syscall.Errno) {
		return 0, syscall.ENOSYS
	}
	connection, err := net.ListenUDP("udp4", &net.UDPAddr{IP: testIPv4Loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	rawConn, err := connection.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	buffers, _, _, unsupported, err := (&oobBatchReader{rawConn: rawConn}).read()
	if err != nil || !unsupported || len(buffers) != 0 {
		t.Fatalf("recvmmsg fallback buffers=%d unsupported=%v err=%v", len(buffers), unsupported, err)
	}
}

func TestWriteMsgUDPAddrPortBatchReportsClosedSocket(t *testing.T) {
	connection, err := net.ListenUDP("udp4", &net.UDPAddr{IP: testIPv4Loopback})
	if err != nil {
		t.Fatal(err)
	}
	if err = connection.Close(); err != nil {
		t.Fatal(err)
	}
	_, _, err = writeMsgUDPAddrPortBatch(
		connection,
		[][]byte{[]byte("closed")},
		[][]byte{nil},
		[]netip.AddrPort{netip.MustParseAddrPort("127.0.0.1:9")},
	)
	if err == nil {
		t.Fatal("closed socket write unexpectedly succeeded")
	}
}

func TestOOBBatchReaderReceivesIPv4PacketInfo(t *testing.T) {
	receiver, err := net.ListenUDP("udp4", &net.UDPAddr{IP: testIPv4Loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer receiver.Close()
	rawConn, err := receiver.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	var controlErr error
	if err = rawConn.Control(func(fd uintptr) {
		controlErr = unix.SetsockoptInt(int(fd), unix.IPPROTO_IP, unix.IP_PKTINFO, 1)
	}); err != nil {
		t.Fatal(err)
	}
	if controlErr != nil {
		t.Fatal(controlErr)
	}
	sender, err := net.DialUDP("udp4", nil, receiver.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	if _, err = sender.Write([]byte("pktinfo")); err != nil {
		t.Fatal(err)
	}
	reader := &oobBatchReader{rawConn: rawConn}
	buffers, oobs, sources, unsupported, err := reader.read()
	if err != nil || unsupported || len(buffers) != 1 || len(oobs) != 1 || len(sources) != 1 {
		t.Fatalf("read buffers=%d oobs=%d sources=%d unsupported=%v err=%v", len(buffers), len(oobs), len(sources), unsupported, err)
	}
	defer buffers[0].Release()
	if string(buffers[0].Bytes()) != "pktinfo" || len(oobs[0]) == 0 || !sources[0].Addr.Is4() {
		t.Fatalf("invalid packet-info batch: payload=%q oob=%d source=%v", buffers[0].Bytes(), len(oobs[0]), sources[0])
	}
}

func TestOOBBatchReaderReceivesIPv6PacketInfo(t *testing.T) {
	receiver, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6loopback})
	if err != nil {
		t.Skipf("IPv6 loopback is unavailable: %v", err)
	}
	defer receiver.Close()
	rawConn, err := receiver.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	var controlErr error
	if err = rawConn.Control(func(fd uintptr) {
		controlErr = unix.SetsockoptInt(int(fd), unix.IPPROTO_IPV6, unix.IPV6_RECVPKTINFO, 1)
	}); err != nil {
		t.Fatal(err)
	}
	if controlErr != nil {
		t.Fatal(controlErr)
	}
	sender, err := net.DialUDP("udp6", nil, receiver.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	if _, err = sender.Write([]byte("pktinfo6")); err != nil {
		t.Fatal(err)
	}
	reader := &oobBatchReader{rawConn: rawConn}
	buffers, oobs, sources, unsupported, err := reader.read()
	if err != nil || unsupported || len(buffers) != 1 || len(oobs) != 1 || len(sources) != 1 {
		t.Fatalf("read buffers=%d oobs=%d sources=%d unsupported=%v err=%v", len(buffers), len(oobs), len(sources), unsupported, err)
	}
	defer buffers[0].Release()
	if string(buffers[0].Bytes()) != "pktinfo6" || len(oobs[0]) == 0 || !sources[0].Addr.Is6() {
		t.Fatalf("invalid IPv6 packet-info batch: payload=%q oob=%d source=%v", buffers[0].Bytes(), len(oobs[0]), sources[0])
	}
}
