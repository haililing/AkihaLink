//go:build !linux && !android

package listener

import (
	"net"
	"net/netip"

	"github.com/sagernet/sing-box/adapter"
)

func (l *Listener) loopUDPInOOBBatch(adapter.OOBPacketBatchHandler) bool {
	return false
}

func writeMsgUDPAddrPortBatch(
	*net.UDPConn,
	[][]byte,
	[][]byte,
	[]netip.AddrPort,
) (int, bool, error) {
	return 0, true, nil
}
