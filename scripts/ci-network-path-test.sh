#!/usr/bin/env bash
set -euo pipefail

suffix="$$"
client="akiha-c-$suffix"
router="akiha-r-$suffix"
server="akiha-s-$suffix"

cleanup() {
  ip netns del "$client" 2>/dev/null || true
  ip netns del "$router" 2>/dev/null || true
  ip netns del "$server" 2>/dev/null || true
}
trap cleanup EXIT

ip netns add "$client"
ip netns add "$router"
ip netns add "$server"
ip link add "ac$suffix" type veth peer name "ar0$suffix"
ip link add "ar1$suffix" type veth peer name "as$suffix"
ip link set "ac$suffix" netns "$client"
ip link set "ar0$suffix" netns "$router"
ip link set "ar1$suffix" netns "$router"
ip link set "as$suffix" netns "$server"

ip -n "$client" link set lo up
ip -n "$router" link set lo up
ip -n "$server" link set lo up
ip -n "$client" addr add 10.210.1.1/24 dev "ac$suffix"
ip -n "$router" addr add 10.210.1.254/24 dev "ar0$suffix"
ip -n "$router" addr add 10.210.2.254/24 dev "ar1$suffix"
ip -n "$server" addr add 10.210.2.1/24 dev "as$suffix"
ip -n "$client" -6 addr add 2001:db8:210:1::1/64 dev "ac$suffix" nodad
ip -n "$router" -6 addr add 2001:db8:210:1::fe/64 dev "ar0$suffix" nodad
ip -n "$router" -6 addr add 2001:db8:210:2::fe/64 dev "ar1$suffix" nodad
ip -n "$server" -6 addr add 2001:db8:210:2::1/64 dev "as$suffix" nodad
for pair in \
  "$client ac$suffix" "$router ar0$suffix" \
  "$router ar1$suffix" "$server as$suffix"; do
  set -- $pair
  ip -n "$1" link set "$2" up
done
ip -n "$router" link set "ar1$suffix" mtu 1280
ip -n "$server" link set "as$suffix" mtu 1280

ip netns exec "$router" sysctl -q -w net.ipv4.ip_forward=1
ip netns exec "$router" sysctl -q -w net.ipv6.conf.all.forwarding=1
ip -n "$client" route add default via 10.210.1.254
ip -n "$server" route add default via 10.210.2.254
ip -n "$client" -6 route add default via 2001:db8:210:1::fe
ip -n "$server" -6 route add default via 2001:db8:210:2::fe

# A packet below the constrained path MTU succeeds, while an oversized DF
# packet receives PTB/fragmentation-needed and fails.
ip netns exec "$client" ping -c 1 -W 2 -M do -s 1200 10.210.2.1
if ip netns exec "$client" ping -c 1 -W 2 -M do -s 1400 10.210.2.1; then
  echo "oversized IPv4 packet unexpectedly crossed the 1280-byte path" >&2
  exit 1
fi
ip netns exec "$client" ping -6 -c 1 -W 2 -s 1180 2001:db8:210:2::1

# Drop only PTB feedback inside the disposable client namespace to reproduce
# a black hole. Product code never creates this firewall rule.
ip netns exec "$client" iptables -I INPUT -p icmp --icmp-type fragmentation-needed -j DROP
if ip netns exec "$client" ping -c 1 -W 2 -M do -s 1400 10.210.2.1; then
  echo "black-hole fixture unexpectedly delivered an oversized packet" >&2
  exit 1
fi

echo "Network namespace MTU/PTB fixtures passed"
