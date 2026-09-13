# AkihaLink

AkihaLink is an Android 16+ arm64 root proxy consisting of a Compose control
app and a KernelSU-family module. The data plane is the CHIZI-0618 sing-box
`type: "ebpf"` inbound pinned at
`10e9a4258e44536ef30cefc3e603e39439ebc02c`
(`v1.15.0-alpha.2-10e9a425`).

It does not request Android VPN permission and does not create a TUN device or
install iptables, nftables, or TProxy rules. TC is used only when the optional
Wi-Fi hotspot proxy is enabled, and only on the Wi-Fi downstream interface
reported as `TetheredState` by Android.

The “管理 → 网络策略 → Wi-Fi 热点代理” switch is off by default. When enabled,
hotspot clients share the currently selected node and rule/global mode,
including TCP, UDP, DNS, IPv4, and IPv6; private destinations remain direct.
The home proxy switch remains the master switch. A missing hotspot is a normal
waiting state. Discovery, kernel, or attach failures fail open, silently turn
the hotspot setting back off, and leave the phone's local proxy available.

The v17 overlay adapts the complete AkihaLink suite to the upstream 1.15
preview branch while retaining control protocol 16. This is an alpha core,
not an upstream stable release. Local traffic explicitly uses
`local.enabled: true` and `local.data_plane: "cgroup"`; optional Wi-Fi
hotspot traffic uses `shared.data_plane: "packet_rewrite"`.
The build regenerates six BPF object families (`tc`, `cgroup`,
`cgroup_coarse`, `cgroup_storage`, `shared_network`, and `fakeip_icmp`) for
both byte orders. The overlay ports live UID transactions, verified Android
netd discovery, atomic readiness, UDP OOB batching, and dynamic Wi-Fi tethering
to the new policy and lifecycle interfaces. Main programs retain upstream
FD ownership; only the independent observability SockOps program keeps
AkihaLink pins. See [the upgrade notes](docs/core-upgrade-1.2.0.md).

AkihaLink 1.2.0 always uses the node selected by the user. Outbound transport
parameters, including subscription-provided multiplexing and AnyTLS session
settings, pass through without app-level BBR, keepalive, TCP Fast Open, or Mux
overrides. The node list supports sorting by name or saved latency. Batch
latency testing uses 20 concurrent, two-sample sing-box URL tests and does not
change the selected node or the main proxy state.

AkihaLink also has a capability-gated observability path. A CO-RE SockOps
program is compiled with NDK r29, relocated from the device's
`/sys/kernel/btf/vmlinux`, and delivers ordered anonymous socket events through
a 1 MiB BPF ring buffer. A root-only Unix-socket daemon pins AkihaLink-owned
maps, programs, and links below `/sys/fs/bpf/akihalink/generic/v1`, samples
targeted `TCP_INFO` only while diagnostics holds a 15-second lease, and
subscribes to rtnetlink. The App renews the lease every 10 seconds and releases
it immediately when the page closes; an abandoned lease expires by itself. Missing
BTF, BPF link, ring buffer, or SockOps support degrades independently; the
proxy can still start with its runtime-generated eBPF and user-space
`TCP_INFO` paths.

Telemetry covers only the selected node's real TCP transport sockets. It keeps
15 minutes in memory and returns randomized flow IDs, RTT, congestion window,
delivery rate, handshake time, retransmissions, and a TCP retransmission-based
estimated loss rate. It never returns a destination domain, IP, port, or raw
error. QUIC, pure UDP, direct traffic, and local DNS are excluded.

TCP connections use the upstream sing-box resolver and dialer. Passive path
observation exposes RTT, delivery rate, retransmissions, network events, and
observed PMTU. AkihaLink does not add a custom HTTP/3 selector or UDP/443
fallback policy: upstream sing-box behavior is preserved. Native QUIC support,
including Hysteria2 and TUIC node protocols, remains enabled.

AkihaLink 1.2.0 batches up to 64 eBPF UDP datagrams together even when each
datagram carries IPv4/IPv6 packet information. Return traffic uses `sendmmsg`;
unsupported or policy-blocked sockets permanently return to the proven
per-packet path. This uses only Android/Bionic APIs and does not require ipset,
BBR, TFO, kTLS, MPTCP, io_uring, or a vendor kernel feature.

The core uses a minimal registry containing the eBPF inbound, direct/selector,
Shadowsocks, VMess, VLESS, Trojan, Hysteria2, TUIC, AnyTLS, current V2Ray
transports, DoH, Clash API, cache, and rule support. AkihaLink 1.2.0 also
includes a Baseline/Startup Profile, an isolated
Macrobenchmark fixture, and anonymous cross-process Perfetto markers spanning
the App, root controller, supervisor, core spawn, and eBPF readiness.

## Outputs

- `AkihaLink-1.2.0.apk`: signed installable APK.
- `AkihaLink-1.2.0-unsigned.apk`: reproducible APK for third-party rebuild comparison.
- `AkihaLink-KSU-1.2.0.zip`: reproducible control protocol 16 module.
- `sing-box-1.2.0-android-arm64`: reproducible pinned core.
- `AkihaLink-1.2.0-source.tar.gz`, `SHA256SUMS`, `reproducibility.json`, and
  the Sigstore/provenance bundles: trusted release material.

The APK and module version must match. Direct mode stops sing-box and detaches
eBPF instead of routing through a direct outbound.

On the first 1.2.0 start, the root core atomically migrates recognized v14
and v16 eBPF configurations to the current `enabled`/`data_plane` schema,
keeping the original bytes in `current.json.pre-v17.bak`. The migration is
idempotent. Unknown legacy fields or unsupported overrides leave the saved
configuration unchanged and require the App to reapply it. Nodes,
subscriptions, rules, exclusions, and UI settings are preserved.

Application exclusions are bound to the Android user, package name, and signing
certificate instead of trusting a stored UID. UIDs are resolved again whenever
a configuration is generated and while the proxy is running after package
install/remove broadcasts. Shared-UID packages are presented and persisted as
one policy group. Legacy UID-only exclusions are quarantined during the database
migration and must be selected again.

## Build

Prerequisites are JDK 21, Android SDK API 36, NDK r29 (29.0.14206865), and
Go 1.26.7. The native core uses r29's upstream-matching
`aarch64-linux-android35-clang` ABI wrapper.

```powershell
git submodule sync --recursive
git submodule update --init --recursive
powershell -ExecutionPolicy Bypass -File scripts/verify-rules.ps1
powershell -ExecutionPolicy Bypass -File scripts/test-module.ps1
& "C:\Program Files\Git\bin\bash.exe" scripts/test-speedtest-control.sh
powershell -ExecutionPolicy Bypass -File scripts/build-core.ps1
powershell -ExecutionPolicy Bypass -File scripts/test-minimal-core.ps1
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
.\gradlew.bat :baselineprofile:assemble
powershell -ExecutionPolicy Bypass -File scripts/build-artifacts.ps1 -Configuration Release
```

The trusted release workflow builds an unsigned APK twice in an immutable
toolchain container, compares all reproducible outputs, then runs fixed Build
Tools 36.0.0 `zipalign` and `apksigner`. Signing material stays in GitHub
secrets and the public certificate SHA-256 is recorded in
`release/signing-certificate.sha256`. See `docs/reproducible-builds.md`.

## Module control protocol

The root controller is `/data/adb/modules/akihalink/bin/akihalinkctl`:

```text
akihalinkctl version
akihalinkctl status
akihalinkctl probe
akihalinkctl probe-hotspot
akihalinkctl activate <staged-config> [trace-id]
akihalinkctl apply <staged-config> [trace-id]
akihalinkctl start [trace-id]
akihalinkctl stop
akihalinkctl restart [trace-id]
akihalinkctl speedtest-start <staged-config>
akihalinkctl speedtest-status
akihalinkctl speedtest-stop
akihalinkctl telemetry-status
akihalinkctl telemetry-clear
akihalinkctl network-events [limit]
akihalinkctl observability-snapshot [limit]
akihalinkctl observability-sampling acquire|renew|release
akihalinkctl path-status
akihalinkctl diagnostics
akihalinkctl cleanup
```

Persistent configuration is stored under `/data/adb/akihalink` with mode
`0600`; module upgrades do not replace it. Fixed rule sets are locked by
commit and SHA-256 in `rules.lock.json` and never update in the background.
Diagnostics omit raw core logs, subscription data, destinations, and
credentials. The core is built from an isolated copy of the pinned submodule
with the `akihalink-upstream-ebpf-v17` overlay; the submodule itself remains
unchanged.

Core builds explicitly disable Go PGO. This keeps local, CI, and release builds
on the same reproducible optimization path; performance claims come from paired
device benchmarks rather than a machine-specific compiler profile.

## Device validation

Building and JVM testing do not prove kernel verifier or vendor SELinux
compatibility. A release must pass the Android 16 arm64 matrix for four KSU
variants, IPv4/IPv6, TCP/UDP/QUIC, DNS, work profiles, exclusions, reboot,
network changes, sleep, and forced core termination. The step-by-step checklist
is in `docs/device-acceptance.md`.
Previous host measurements in `docs/performance-1.1.md` and
`docs/performance-0.16.0.md` are historical baselines. They do not validate
the 1.2.0 core; compilation, tests, and device acceptance for this upgrade
remain pending.

## License

GPL-3.0-only. See `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`.
