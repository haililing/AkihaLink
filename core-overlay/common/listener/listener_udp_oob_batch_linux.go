//go:build linux || android

package listener

import (
	"io"
	"net"
	"net/netip"
	"os"
	"syscall"
	"unsafe"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/common/buf"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"

	"golang.org/x/sys/unix"
)

const (
	udpOOBBatchSize = 64
	udpOOBSize      = 128
)

type oobMMsgHdr struct {
	msgHdr unix.Msghdr
	msgLen uint32
}

func (l *Listener) loopUDPInOOBBatch(handler adapter.OOBPacketBatchHandler) bool {
	rawConn, err := l.udpConn.SyscallConn()
	if err != nil {
		return false
	}
	reader := &oobBatchReader{rawConn: rawConn}
	for {
		buffers, oobs, sources, unsupported, readErr := reader.read()
		if unsupported {
			buf.ReleaseMulti(buffers)
			return false
		}
		if readErr != nil {
			buf.ReleaseMulti(buffers)
			if l.shutdown.Load() && E.IsClosed(readErr) {
				return true
			}
			_ = l.udpConn.Close()
			l.logger.Error("udp listener closed: ", readErr)
			return true
		}
		handler.NewPacketBatch(buffers, oobs, sources)
	}
}

type oobBatchReader struct {
	rawConn syscall.RawConn
}

func (r *oobBatchReader) read() (
	buffers []*buf.Buffer,
	oobs [][]byte,
	sources []M.Socksaddr,
	unsupported bool,
	err error,
) {
	allBuffers := make([]*buf.Buffer, udpOOBBatchSize)
	names := make([]unix.RawSockaddrAny, udpOOBBatchSize)
	iovecs := make([]unix.Iovec, udpOOBBatchSize)
	controls := make([][udpOOBSize]byte, udpOOBBatchSize)
	messages := make([]oobMMsgHdr, udpOOBBatchSize)
	for index := range messages {
		packet := buf.NewPacket()
		allBuffers[index] = packet
		iovecs[index] = packet.Iovec(packet.FreeLen())
		messages[index].msgHdr.Name = (*byte)(unsafe.Pointer(&names[index]))
		messages[index].msgHdr.Namelen = unix.SizeofSockaddrAny
		messages[index].msgHdr.Iov = &iovecs[index]
		messages[index].msgHdr.SetIovlen(1)
		messages[index].msgHdr.Control = &controls[index][0]
		messages[index].msgHdr.SetControllen(udpOOBSize)
	}
	readCount := 0
	var syscallErr syscall.Errno
	rawErr := r.rawConn.Read(func(fd uintptr) bool {
		for {
			readCount, syscallErr = receiveMessages(int(fd), messages)
			switch syscallErr {
			case 0:
				return true
			case syscall.EINTR:
				continue
			case syscall.EAGAIN:
				return false
			default:
				return true
			}
		}
	})
	if rawErr != nil {
		buf.ReleaseMulti(allBuffers)
		return nil, nil, nil, false, rawErr
	}
	if isMMsgUnavailable(syscallErr) {
		buf.ReleaseMulti(allBuffers)
		return nil, nil, nil, true, nil
	}
	if syscallErr != 0 {
		buf.ReleaseMulti(allBuffers)
		return nil, nil, nil, false, os.NewSyscallError("recvmmsg", syscallErr)
	}
	if readCount == 0 {
		buf.ReleaseMulti(allBuffers)
		return nil, nil, nil, false, io.EOF
	}
	buffers = allBuffers[:readCount]
	buf.ReleaseMulti(allBuffers[readCount:])
	oobs = make([][]byte, readCount)
	sources = make([]M.Socksaddr, readCount)
	for index := 0; index < readCount; index++ {
		message := &messages[index]
		if message.msgHdr.Flags&unix.MSG_TRUNC != 0 || message.msgHdr.Flags&unix.MSG_CTRUNC != 0 {
			buf.ReleaseMulti(buffers)
			return nil, nil, nil, false, os.NewSyscallError("recvmmsg", syscall.EMSGSIZE)
		}
		buffers[index].Truncate(int(message.msgLen))
		oobs[index] = controls[index][:message.msgHdr.Controllen]
		sources[index] = M.SocksaddrFromRawSockaddrAny(&names[index]).Unwrap()
	}
	return buffers, oobs, sources, false, nil
}

func writeMsgUDPAddrPortBatch(
	connection *net.UDPConn,
	payloads [][]byte,
	oobs [][]byte,
	destinations []netip.AddrPort,
) (sent int, unsupported bool, err error) {
	rawConn, err := connection.SyscallConn()
	if err != nil {
		return 0, false, err
	}
	names := make([]unix.RawSockaddrAny, len(payloads))
	iovecs := make([]unix.Iovec, len(payloads))
	messages := make([]oobMMsgHdr, len(payloads))
	for index, payload := range payloads {
		messages[index].msgHdr.Name = (*byte)(unsafe.Pointer(&names[index]))
		messages[index].msgHdr.Namelen = M.AddrPortToRawSockaddrAny(
			&names[index], destinations[index], destinations[index].Addr().Is6(),
		)
		if len(payload) > 0 {
			iovecs[index].Base = unsafe.SliceData(payload)
			iovecs[index].SetLen(len(payload))
			messages[index].msgHdr.Iov = &iovecs[index]
			messages[index].msgHdr.SetIovlen(1)
		}
		if len(oobs[index]) > 0 {
			messages[index].msgHdr.Control = unsafe.SliceData(oobs[index])
			messages[index].msgHdr.SetControllen(len(oobs[index]))
		}
	}
	remaining := messages
	var syscallErr syscall.Errno
	rawErr := rawConn.Write(func(fd uintptr) bool {
		for len(remaining) > 0 {
			var count int
			count, syscallErr = sendMessages(int(fd), remaining)
			switch syscallErr {
			case 0:
			case syscall.EINTR:
				continue
			case syscall.EAGAIN:
				return false
			default:
				return true
			}
			if count == 0 {
				syscallErr = syscall.EIO
				return true
			}
			sent += count
			remaining = remaining[count:]
		}
		return true
	})
	if rawErr != nil {
		return sent, false, rawErr
	}
	if isMMsgUnavailable(syscallErr) {
		return sent, true, nil
	}
	if syscallErr != 0 {
		return sent, false, os.NewSyscallError("sendmmsg", syscallErr)
	}
	return sent, false, nil
}

var receiveMessages = func(fd int, messages []oobMMsgHdr) (int, syscall.Errno) {
	return mmsgSyscall(unix.SYS_RECVMMSG, fd, messages)
}

var sendMessages = func(fd int, messages []oobMMsgHdr) (int, syscall.Errno) {
	return mmsgSyscall(unix.SYS_SENDMMSG, fd, messages)
}

func mmsgSyscall(trap uintptr, fd int, messages []oobMMsgHdr) (int, syscall.Errno) {
	result, _, errno := unix.Syscall6(
		trap,
		uintptr(fd),
		uintptr(unsafe.Pointer(&messages[0])),
		uintptr(len(messages)),
		0,
		0,
		0,
	)
	if errno != 0 {
		return 0, errno
	}
	return int(result), 0
}

func isMMsgUnavailable(errno syscall.Errno) bool {
	return errno == syscall.ENOSYS || errno == syscall.EOPNOTSUPP || errno == syscall.EPERM
}
