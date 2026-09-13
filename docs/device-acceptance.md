# AkihaLink 1.2.0 Device Acceptance

Run this checklist on Android 16 arm64 hardware. Automated tests cannot prove
kernel verifier, vendor SELinux, radio, and real-node compatibility.

## Preconditions

- Test a matching 1.2.0 APK/module pair built from the same revision.
- Keep a matching 1.1 pair (core `90bb3d43`, patch set v16) for upgrade, comparison, and rollback tests; retain 0.16.0 for legacy migration coverage.
- Retain the complete `0b6988e5` / `akihalink-upstream-ebpf-v12` release
  artifacts so the core, module, and App can be rolled back together.
- Repeat the core workflow on KernelSU, KernelSU-Next, SukiSU Ultra, and
  ReSukiSU where hardware is available.
- Record baseline `ip link`, `iptables-save`, `ip6tables-save`, default qdisc,
  and TCP congestion-control state.

## Version And Compatibility

- `akihalinkctl version` reports module `1.2.0`, protocol `16`, core commit
  `10e9a4258e44536ef30cefc3e603e39439ebc02c`, and patch set
  `akihalink-upstream-ebpf-v17`.
- The feature set includes `ebpf_core_relocation`, `upstream_cgroup_ebpf`,
  `flow_telemetry`, `rtnetlink_observer`, `passive_path_observation`,
  `pidfd_supervision`, `perfetto_trace_markers`, `atomic_config_activation`,
  `authenticated_startup_gate`, `udp_oob_batch`, `minimal_core_registry`,
  `on_demand_tcp_info`, `wifi_hotspot_proxy`, `cilium_ebpf_backend`, and
  `ebpf_kernel_probe`.
- The feature set does not include removed scoring, content-quality, or custom
  HTTP/3-policy features.
- Pair 1.2.0 App with a 1.1 module, then 1.1 App with a 1.2.0 module.
  Repeat with the older 0.16.0 pair.
  Both combinations must report a version or protocol incompatibility and must
  not be treated as operational pairs.

## eBPF, Routing, And Proxy Modes

- Confirm `actualState=running`, `ebpfAttached=true`,
  `controllerReady=true`, and the private controller listener on
  `127.0.0.1:9090`.
- Verify TCP, UDP, IPv4, IPv6, DNS takeover, LAN bypass, automatic/rule mode,
  global mode, direct mode, app exclusion, and work-profile UID handling.
- Confirm app exclusion covers the application UID and derived SDK sandbox UID
  without bypassing isolated/App Zygote or system DNS traffic.
- Change only the exclusion list while running and confirm the cilium UID LPM
  Map updates without a core restart. A capacity or injected update failure
  must leave the old policy intact; an injected rollback failure must force a
  backend rebuild before more traffic is accepted.
- While the proxy is running, uninstall an excluded package and confirm its UID
  is removed from the live policy before another package can inherit it. A
  different package or signing certificate must never inherit the exclusion.
- Update an excluded package through a valid signing-certificate lineage and
  confirm the exclusion follows its newly resolved UID. For a shared UID, the
  UI must show the group and toggling it must persist every package identity.
- Switch nodes while running and confirm the eBPF program is not detached and
  reattached.
- Fill and drain TCP/UDP redirect maps under churn, then confirm stale TCP
  redirects are swept, reservation failures recover, pressure clears, and map
  usage returns to a stable level without restarting the core.
- Exercise connected UDP sockets against multiple destinations and confirm
  original-target binding remains correct for both IPv4 and IPv6.
- On GKI 5.10+ and each vendor SELinux policy, restart `netd` and verify its
  identity is rediscovered. Confirm validated resolver traffic on ports 53,
  853, and 443 remains proxied with no DNS leak and that an unverified process
  cannot obtain resolver treatment.
- On Linux 6.6.0 through 6.6.46 without the LPM trie fix, confirm high-risk LPM
  policy loading is rejected with a clear compatibility error. Confirm a fixed
  vendor kernel or a newer supported kernel loads the same policy normally.
- Reboot once with the last desired state running and once with it stopped;
  each state must be restored correctly.
- Compare system networking against the baseline after start, stop, crash
  recovery, and reboot. No TUN, VPN permission, iptables, TProxy, nftables, or
  global congestion-control change is allowed. With hotspot proxy disabled,
  no AkihaLink TC filter or qdisc change is allowed.

## Wi-Fi Hotspot Proxy

- Confirm the setting defaults to off, survives App restart, and is preserved
  while the home proxy switch is off or the mode is Direct.
- Run `akihalinkctl probe-hotspot` with the hotspot both off and on. It must use
  only the nonexistent probe interface and must not add a filter to a real
  interface.
- Enable the setting before starting the proxy, after starting it, before
  enabling the system hotspot, and after enabling the system hotspot. Confirm
  `hotspotProxyState` transitions only among `disabled`, `waiting`, `attached`,
  and `failed`.
- Verify Android's Wi-Fi tethering downstream interface is the only interface
  with AkihaLink TCX links or clsact fallback filters at TC priority 1.
  Wi-Fi upstream, USB, Bluetooth, Ethernet, P2P, and local-only hotspot
  interfaces must never be selected.
- From a hotspot client, verify rule and global modes, TCP, UDP, DNS, QUIC,
  IPv4, IPv6, fragmented packets, replies, and private-network access. Turning
  off the home proxy must immediately restore ordinary Android hotspot routing.
- Recreate the hotspot interface with the same name and a new ifindex, switch
  the phone's Wi-Fi upstream, and force a Tethering-state read
  failure. Confirm old filters are removed and the failure silently
  disables the setting while the phone proxy keeps running.
- Delete one owned TC filter while the hotspot is active and wait for the next
  30-second health pass. Confirm the filter is restored. Repeat with a
  same-handle or same-name third-party filter and confirm it is never replaced.
- Exercise partial ingress/egress attach failure, normal stop, forced core
  termination, reboot, module upgrade, cleanup, and uninstall. AkihaLink must
  remove only its owned TCX links or exact fallback filter handles, restore the
  recorded `route_localnet` value, and remove `clsact` only when AkihaLink
  created it and no filter remains. A same-name interface with a different
  ifindex and every third-party filter must remain untouched.
- On a kernel or vendor offload path that cannot be intercepted, confirm the
  hotspot silently uses direct Android routing and the local proxy remains
  usable.

## Node Protocols And HTTP/3 Boundary

- Test VMess, VLESS, Trojan, Shadowsocks, and AnyTLS nodes in rule and global
  modes.
- Test Hysteria2 and TUIC nodes over IPv4, IPv6, Wi-Fi, and mobile data.
- Confirm ordinary upstream HTTP/3/QUIC traffic is not proactively blocked.
- Inspect generated normal and speed-test configurations. Their selectors must
  not contain `udp_443_fallback_outbounds` or another AkihaLink-specific
  UDP/443 fallback field.
- Confirm removing the custom HTTP/3 policy does not alter DNS, routing rules,
  selected-node behavior, subscription transport fields, or app exclusions.

## UDP OOB Batching And Minimal Core

- Exercise IPv4 and IPv6 UDP through the eBPF inbound with different original
  destinations. Confirm packet order, original-target lookup, UID exclusion,
  NAT expiry, reply source address, and buffer accounting remain correct.
- Verify ordinary UDP, webpage QUIC, Hysteria2, and TUIC under sustained load.
  No UDP GSO support may be required for correctness.
- Force `recvmmsg` and `sendmmsg` to return `ENOSYS`, `EOPNOTSUPP`, and `EPERM`
  in the syscall tests. Confirm each socket permanently uses the per-packet
  path, while `EINTR`, `EAGAIN`, partial sends, close, and data errors follow
  their explicit paths.
- Generate and check configurations for Shadowsocks, VMess, VLESS, Trojan,
  Hysteria2, TUIC, and AnyTLS with http/ws/quic/grpc/httpupgrade transports.
  Unsupported core protocols must be rejected rather than silently accepted.
- Confirm `with_gvisor`, `with_dhcp`, and `with_provider` are absent,
  `grpcnotrace` is present, and the stripped core is at least 10% smaller than
  the 0.15.0 core.

## Batch Latency Testing And Sorting

- Test more than 20 nodes and confirm no more than 20 requests run
  concurrently and the selected node does not change.
- Repeat with the main proxy stopped. The auxiliary core must listen only on
  `127.0.0.1:19090`, have no inbound, and not attach cgroup BPF.
- Cancel a running batch and background the App during another batch. Completed
  results must remain visible and all auxiliary process state must be cleaned.
- Confirm available, unstable, and unavailable latency results survive App
  restart, while deleted nodes are pruned.
- Confirm a completed batch switches the node list to latency sorting.
- Confirm the only sort choices are name and latency, and both produce stable
  expected ordering.

## Anonymous Diagnostics And Path Observation

- Use TCP traffic and confirm telemetry exposes bounded anonymous RTT,
  congestion window, delivery rate, handshake duration, retransmission, and
  estimated-loss data.
- Confirm diagnostics contain no node name, domain, destination address, port,
  raw socket cookie, raw error, subscription data, or credentials.
- Confirm direct, local DNS, QUIC, and pure UDP sockets are absent from the TCP
  flow list.
- Trigger link, address, default-route, rule, and MTU changes. Bounded network
  events and path status must continue to parse and refresh.
- Open diagnostics and confirm the 15-second TCP sampling lease is acquired,
  an immediate generation is produced, and the App renews it every 10 seconds.
  Leave diagnostics and confirm the lease is released and full-machine
  socket-diag scans stop. Kill the App and confirm the lease expires by itself.
- Export diagnostics with the page closed and confirm it waits no more than two
  seconds for a newer generation, then releases its temporary lease.
- Confirm the diagnostics page has no content-quality, first-byte, real
  downlink, content-sample, content-queue-drop, or clear-quality-history UI.
- Confirm passive PMTU observation remains keyed by anonymous node fingerprint
  and route generation. Native QUIC DPLPMTUD remains available where upstream
  enables it.

## Upgrade From 1.1

- Save local-only and Wi-Fi-hotspot configurations on 1.1, including exclusions.
- Upgrade the App and module together to 1.2.0 and start the proxy.
- Confirm `current.json.pre-v17.bak` contains the original configuration;
  the current file has no `mode`, `tcp_splice`, or `shared.advanced` fields.
- Confirm local `enabled=true` / `data_plane=cgroup`, and when configured,
  shared `enabled=true` / `data_plane=packet_rewrite` with root `tc_priority=1`.
- Restart twice and confirm migration does not alter the backup or rewrite an
  already-current configuration. Verify node credentials, subscription data,
  rules, exclusions, selected node, and hotspot preference are preserved.
- Exercise adding the first UID exclusion and removing the last one without
  restarting the core, then verify SDK sandbox UID handling and netd DNS.
- To roll back, restore the 1.1 App/module pair together and use the saved
  pre-v17 configuration or regenerate it with the old App.

## Upgrade From 0.16.0

- On 0.16.0, select a node, import subscriptions, configure app exclusions,
  and save latency results. If this installation was previously upgraded from
  0.14.0, retain representative legacy quality and HTTP/3 settings/files.
- Create legacy `state/adaptive-scores-v1.json`,
  `state/adaptive-scores-v2.json`, and their `.tmp` variants.
- Install the matching 1.2.0 module and App, then reboot.
- Confirm the selected node, subscriptions, exclusions, proxy mode, and saved
  latency results are preserved.
- Confirm no removed quality sorting or HTTP/3 policy setting returns.
- Confirm all legacy adaptive-score files and temporary score files are
  removed during module upgrade.
- Confirm no scoring command is accepted and observability snapshots contain
  telemetry, path status, and network events without a score field.

## Startup, Recovery, And Long Run

- Confirm startup is gated by authenticated private-controller readiness and
  does not depend on info-level core logs.
- Force-kill the main and auxiliary cores, test invalid configuration rollback,
  and verify stale processes, PID files, eBPF objects, and redirect routes are
  cleaned safely.
- Confirm status remains responsive with a large observability history and
  diagnostic export provides a bounded fallback on collection timeout.
- Alternate 1.1 and 1.2.0 on the same Android 16+ device, node, stable
  network, and controlled device temperature. Run each workload at least seven
  times for at least 30 seconds.
- Record TCP upload/download at one and four flows, 1200-byte UDP PPS/loss,
  Hysteria2/TUIC representative throughput, core CPU/bit, RSS, connection
  latency, BPF maps, file descriptors, and goroutine count.
- Record throughput and CPU changes against 1.1; this core upgrade makes no
  measured performance-improvement claim. No throughput may regress over
  3%, first-connect p95 over 5%, or UDP loss over 0.1 percentage point.
- Record the core size change; confirm fallback correctness is unchanged and
  RSS has no sustained growth.
- Run the final candidate continuously for 24 hours and reject unbounded map,
  FD, or goroutine growth and unexpected core exits.

## Release Gate

- Do not publish while any required device item is failing or unverified.
- Confirm release and verification cores are built with the same pinned
  toolchain and the reproducible unprofiled compiler configuration.
- Final status must report `actualState=running`, `ebpfAttached=true`,
  `controllerReady=true`, `systemResolverAttached=true`, and
  `startupHealth=healthy` on every release device.
- Set `AKIHALINK_DEVICE_MATRIX_ATTESTATION` to
  `10e9a4258e44536ef30cefc3e603e39439ebc02c:akihalink-upstream-ebpf-v17:1.2.0`.
  Any core, patch-set, or App version change invalidates it.
