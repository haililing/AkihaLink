#!/usr/bin/env bash
set -euo pipefail
trap 'printf "%s\n" "Main controller fixture failed at line $LINENO" >&2' ERR

root="$(cd "$(dirname "$0")/.." && pwd)"
ctl="$root/module/bin/akihalinkctl"
shell_bin="${AKIHALINK_TEST_SH:-sh}"
tmp="$(mktemp -d)"
latency_limit_ms=2500
stop_latency_limit_ms=2500
failed_start_latency_limit_ms=2500
reload_latency_limit_ms=6000
status_latency_limit_ms=4000
diagnostics_latency_limit_ms=4000
uid_update_latency_limit_ms=6000
case "${OSTYPE:-}" in
  msys*|cygwin*|mingw*)
    latency_limit_ms=6000
    stop_latency_limit_ms=6000
    failed_start_latency_limit_ms=8000
    reload_latency_limit_ms=12000
    status_latency_limit_ms=6000
    diagnostics_latency_limit_ms=8000
    uid_update_latency_limit_ms=9000
    ;;
esac

cleanup() {
  for name in supervisor core observability cleanup.supervisor cleanup; do
    pid_file="$tmp/data/runtime/$name.pid"
    if [ -r "$pid_file" ]; then
      kill -TERM "$(cat "$pid_file")" 2>/dev/null || true
    fi
  done
  rm -rf "$tmp"
}
trap cleanup EXIT

cat > "$tmp/mock-core" <<'EOF'
#!/usr/bin/env bash
set -u

write_starttime() {
  awk '{print $22}' "/proc/$1/stat" 2>/dev/null || printf '%s\n' fixture
}

case "${1:-}" in
  check)
    if [ -n "${MOCK_CHECK_MARKER:-}" ]; then
      printf '%s\n' check >> "$MOCK_CHECK_MARKER"
    fi
    exit 0
    ;;
  version)
    printf '%s\n' "${MOCK_CORE_VERSION:-sing-box version v1.14.0-rc.1-9-g90bb3d43-akihalink-upstream-ebpf-v16}"
    ;;
  tools)
    if [ "${2:-}" = ebpf ] && [ "${3:-}" = status ]; then
      printf '%s\n' '{"supported":true,"backend":"cilium","verifierReady":true}'
      exit 0
    fi
    exit 2
    ;;
  run)
    if [ "${MOCK_CORE_EXIT:-0}" = 1 ] ||
      { [ "${MOCK_CORE_EXIT_ONCE:-0}" = 1 ] &&
        [ ! -e "${AKIHALINK_DATA_DIR:?}/runtime/mock-core-failed-once" ]; }; then
      if [ "${MOCK_CORE_EXIT_ONCE:-0}" = 1 ]; then
        mkdir -p "${AKIHALINK_DATA_DIR:?}/runtime"
        : > "${AKIHALINK_DATA_DIR:?}/runtime/mock-core-failed-once"
      fi
      printf '%s\n' 'mock core startup failure' >&2
      exit 2
    fi
    if grep -q 'hotspot-probe-in' "${3:-/dev/null}" 2>/dev/null; then
      printf '%s\n' 'INFO eBPF shared-network TC interception ready: downstream_interfaces=[waiting for fixture probe interface]'
    fi
    printf '%s\n' 'INFO eBPF local cgroup interception ready: fixture probe'
    proc_root="${AKIHALINK_TEST_PROC_ROOT:?}"
    mkdir -p "$proc_root/$$/net"
    if [ "${MOCK_MAIN_API_READY:-1}" = 1 ]; then
      cat > "$proc_root/$$/net/tcp" <<'TABLE'
  sl  local_address rem_address   st
   0: 0100007F:2382 00000000:0000 0A
TABLE
    else
      cat > "$proc_root/$$/net/tcp" <<'TABLE'
  sl  local_address rem_address   st
TABLE
    fi
    trap 'exit 0' TERM INT
    while :; do sleep 1; done
    ;;
  akihalink-daemon)
    shift
    socket=""
    runtime=""
    core_log=""
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --socket)
          socket=$2
          shift 2
          ;;
        --runtime)
          runtime=$2
          shift 2
          ;;
        --core-log)
          core_log=$2
          shift 2
          ;;
        --core|--pin-root|--runtime-config)
          shift 2
          ;;
        --uid-policy-socket)
          shift 2
          ;;
        process)
          operation=$2
          pid=$3
          if [ "$operation" = status ]; then
			[ -z "${MOCK_PROCESS_STATUS_DELAY:-}" ] || sleep "$MOCK_PROCESS_STATUS_DELAY"
            kill -0 "$pid" 2>/dev/null
          else
            signal=${6:-15}
            kill "-$signal" "$pid" 2>/dev/null
          fi
          exit
          ;;
        client)
          operation=${2:-status}
          runtime=${runtime:-${socket%/*}}
          case "$operation" in
            status|clear)
              printf '%s\n' '{"running":true,"windowSeconds":900,"activeFlows":1,"recentFlows":[]}'
              ;;
            network-events)
              printf '%s\n' '[]'
              ;;
            path-status)
              printf '%s\n' '{"routeGeneration":1,"tcpMtuProbing":1,"tcpMode":"kernel_blackhole","paths":[]}'
              ;;
            stop|cleanup)
              pid="$(cat "$runtime/observability.pid" 2>/dev/null)"
              [ -n "$pid" ] && kill -TERM "$pid" 2>/dev/null || true
              ;;
            snapshot)
              [ -z "${MOCK_SNAPSHOT_DELAY:-}" ] || sleep "$MOCK_SNAPSHOT_DELAY"
              printf '%s\n' snapshot >> "${AKIHALINK_TEST_SNAPSHOT_MARKER:?}"
              printf '%s\n' '{"running":true,"loaderMode":"fixture","attachMode":"fixture","eventMode":"tcp_info","pinGeneration":0,"routeGeneration":1,"tcpMode":"kernel_blackhole","supervisor":{"mode":"pidfd"}}'
              ;;
            runtime-status)
              printf '%s\n' '{"running":true,"loaderMode":"fixture","attachMode":"fixture","eventMode":"tcp_info","pinGeneration":0,"routeGeneration":1,"tcpMtuProbing":1,"tcpMode":"kernel_blackhole","perSocketPmtu":false,"perSocketMss":false,"quicDplpmtud":true,"mtuDegradedReason":"per_socket_mtu_unavailable","supervisorMode":"pidfd","systemResolverDiscovered":1,"dnsPlainCaptureCount":2,"dnsOverTlsCaptureCount":3,"dnsOverHttpsCaptureCount":4,"resolverProbeBypassCount":5,"resolverMonitorMode":"pidfd_epoll","resolverRediscoveries":1,"tcpActiveSamples":6,"tcpIdleSamples":7,"tcpSamplingMode":"on_demand","tcpSamplingLeaseActive":false,"tcpSamplingLeaseExpiresAt":0,"tcpSampleGeneration":8}'
              ;;
            sampling-acquire|sampling-renew)
              printf '%s\n' '{"tcpSamplingMode":"on_demand","tcpSamplingLeaseActive":true,"tcpSamplingLeaseExpiresAt":1786293500000,"tcpSampleGeneration":9}'
              ;;
            sampling-release)
              printf '%s\n' '{"tcpSamplingMode":"on_demand","tcpSamplingLeaseActive":false,"tcpSamplingLeaseExpiresAt":0,"tcpSampleGeneration":9}'
              ;;
            probe)
              printf '%s\n' '{"btfRootReadable":true,"coreRelocation":true,"bpffs":true,"bpfLink":true,"linkUpdate":false,"ringBuffer":true,"sockOps":true,"tcpInfo":true,"rtnetlink":true,"perSocketPmtu":false,"perSocketMss":false,"quicDplpmtud":true,"pidfdOpen":true,"pidfdSendSignal":true,"pidfdPoll":true,"tcpMtuProbing":1}'
              ;;
          esac
          exit 0
          ;;
        cleanup-pins)
          printf '%s\n' cleanup >> "${AKIHALINK_TEST_CLEANUP_MARKER:?}"
          exit 0
          ;;
        cleanup-shared-network)
          exit 0
          ;;
        migrate-config|compare-config-except-uid)
          exit 0
          ;;
        apply-uid-policy)
          printf '%s\n' update >> "${MOCK_UID_POLICY_MARKER:?}"
          printf '%s\n' '{"requestedPackages":0,"stoppedPackages":0,"verifiedUids":0,"remainingUids":[]}'
          exit 0
          ;;
        update-uid-policy)
          printf '%s\n' update >> "${MOCK_UID_POLICY_MARKER:?}"
          exit 0
          ;;
        probe)
          printf '%s\n' '{"btfRootReadable":true,"coreRelocation":true,"bpffs":true,"bpfLink":true,"linkUpdate":false,"ringBuffer":true,"sockOps":true,"tcpInfo":true,"rtnetlink":true,"perSocketPmtu":false,"perSocketMss":false,"quicDplpmtud":true,"pidfdOpen":true,"pidfdSendSignal":true,"pidfdPoll":true,"tcpMtuProbing":1}'
          exit 0
          ;;
        auxiliary)
          shift
          if [ "${1:-}" = --timeout ]; then shift 2; fi
          name=$1
          config=$2
          log=$3
          sleep 30 &
          child=$!
          printf '%s\n' "$child" > "$runtime/$name.pid"
          write_starttime "$child" > "$runtime/$name.starttime"
          printf '%s\n' $$ > "$runtime/$name.supervisor.pid"
          write_starttime $$ > "$runtime/$name.supervisor.starttime"
          printf '%s\n' 'INFO eBPF local cgroup interception ready: fixture probe' > "$log"
          trap 'kill -TERM "$child" 2>/dev/null; wait "$child" 2>/dev/null; exit 0' TERM INT
          wait "$child"
          exit
          ;;
        supervise)
          mkdir -p "$runtime" "$(dirname "$core_log")"
          printf '%s\n' $$ > "$runtime/supervisor.pid"
          write_starttime $$ > "$runtime/supervisor.starttime"
          printf '%s\n' $$ > "$runtime/observability.pid"
          write_starttime $$ > "$runtime/observability.starttime"
          printf '%s\n' 'WARN fixture log deliberately has no info-level readiness marker' > "$core_log"
          "$0" run &
          child=$!
          printf '%s\n' "$child" > "$runtime/core.pid"
		  child_start="$(write_starttime "$child")"
		  printf '%s\n' "$child_start" > "$runtime/core.starttime"
          resolver_attached=0
          if [ -e "${AKIHALINK_TEST_PROC_ROOT:?}/1051/cmdline" ]; then
            resolver_attached=1
          fi
          printf 'pid=%s\nstarttime=%s\nstartup_health=healthy\nsystem_resolver_attached=%s\nresolver_discovered=1\ndns_plain_capture=2\ndns_over_tls_capture=0\ndns_over_https_capture=1\nprobe_bypass=3\n' \
		    "$child" "$child_start" "$resolver_attached" > "$runtime/core.ready"
          if [ "$resolver_attached" = 1 ] && [ "${MOCK_MAIN_API_READY:-1}" = 1 ]; then
            printf 'pid=%s\nstarttime=%s\ncontroller_authenticated=1\nsystem_resolver_attached=1\nstartup_health=healthy\n' \
              "$child" "$child_start" > "$runtime/startup.ready"
          fi
          date +%s > "$runtime/started_at"
          printf '%s\n' starting > "$runtime/actual"
          trap 'kill -TERM "$child" 2>/dev/null; wait "$child" 2>/dev/null; exit 0' TERM INT
          wait "$child"
          exit_code=$?
          rm -f "$runtime/core.pid" "$runtime/core.starttime" "$runtime/started_at"
          if [ "$(cat "$runtime/desired" 2>/dev/null)" = running ]; then
            printf 'sing-box exited with code %s\n' "$exit_code" > "$runtime/last_error"
            printf '%s\n' stopped > "$runtime/desired"
            printf '%s\n' failed > "$runtime/actual"
          fi
          exit "$exit_code"
          ;;
        *)
          shift
          ;;
      esac
    done
    exit 2
    ;;
  *)
    exit 2
    ;;
esac
EOF
chmod 0755 "$tmp/mock-core"

cat > "$tmp/mock-supervisor" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
data=${AKIHALINK_DATA_DIR:?}
runtime="$data/runtime"
exec "${AKIHALINK_CORE:?}" akihalink-daemon \
  --socket "$runtime/observability.sock" \
  --runtime "$runtime" \
  --core-log "$data/log/core.log" \
  supervise
EOF
chmod 0755 "$tmp/mock-supervisor"

mkdir -p "$tmp/mock-bin"
cat > "$tmp/mock-bin/dumpsys" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' 'Network 100:'
EOF
cat > "$tmp/mock-bin/service" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${MOCK_SERVICE_LOG:?}"
if [ "${MOCK_DNS_CACHE_FLUSH_FAIL:-0}" = 1 ]; then
	printf '%s\n' 'Exception: fixture failure'
	exit 1
fi
printf '%s\n' 'Result: Parcel(00000000)'
EOF
cat > "$tmp/mock-bin/ip" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${MOCK_IP_LOG:?}"
exit 0
EOF
cat > "$tmp/mock-bin/readlink" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  */1051/exe) printf '%s\n' /system/bin/netd ;;
  *) /usr/bin/readlink "$@" ;;
esac
EOF
chmod 0755 "$tmp/mock-bin/dumpsys" "$tmp/mock-bin/service" "$tmp/mock-bin/ip" "$tmp/mock-bin/readlink"

cat > "$tmp/valid.json" <<'EOF'
{
  "log": {"level": "warn"},
  "inbounds": [{"type":"ebpf","tag":"ebpf-in","mode":"local","network":["tcp","udp"],"local":{"dns_mode":"hijack","include_uid":[],"include_uid_range":[],"exclude_uid":[]}}],
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "experimental": {"clash_api": {"external_controller": "127.0.0.1:9090", "secret": "fixture"}}
}
EOF

cat > "$tmp/excluded.json" <<'EOF'
{
  "log": {"level": "warn"},
  "inbounds": [{"type":"ebpf","tag":"ebpf-in","mode":"local","network":["tcp","udp"],"local":{"dns_mode":"hijack","include_uid":[],"include_uid_range":[],"exclude_uid":[10123]}}],
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "experimental": {"clash_api": {"external_controller": "127.0.0.1:9090", "secret": "fixture"}}
}
EOF

cat > "$tmp/missing-controller.json" <<'EOF'
{"inbounds":[{"type":"ebpf","mode":"local","network":["tcp","udp"],"local":{"dns_mode":"hijack"}}],"outbounds":[{"type":"direct"}]}
EOF

cat > "$tmp/exclusion-targets.json" <<'EOF'
{"packages":[],"verifyUids":[]}
EOF

cat > "$tmp/legacy-system-resolver.json" <<'EOF'
{
  "inbounds": [{
    "type": "ebpf",
    "tag": "ebpf-in",
    "include_android_system_resolver": true
  }],
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "experimental": {"clash_api": {"external_controller": "127.0.0.1:9090", "secret": "fixture"}}
}
EOF

# Production subscriptions can generate compact single-line configs around
# 50 KB. Redirect comparison must stay linear rather than backtracking across
# every outbound while searching for the small eBPF inbound object.
{
  printf '%s' '{"log":{"level":"warn"},"inbounds":[{"type":"ebpf","tag":"ebpf-in","mode":"local","network":["tcp","udp"],"local":{"dns_mode":"hijack","include_uid":[],"include_uid_range":[],"exclude_uid":[]}}],"outbounds":['
  index=0
  while [ "$index" -lt 600 ]; do
    [ "$index" -eq 0 ] || printf ','
    printf '{"type":"direct","tag":"fixture-node-%s","override_address":"fixture-%s.example.invalid"}' \
      "$index" "$index"
    index=$((index + 1))
  done
  printf '%s\n' '],"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":"fixture"}}}'
} > "$tmp/large-compact.json"

run_ctl() {
  AKIHALINK_DATA_DIR="$tmp/data" \
    AKIHALINK_CORE="$tmp/mock-core" \
    AKIHALINK_SUPERVISOR="$tmp/mock-supervisor" \
    AKIHALINK_TEST_PROC_ROOT="$tmp/proc" \
    AKIHALINK_TEST_SNAPSHOT_MARKER="$tmp/observability-snapshots" \
	AKIHALINK_TEST_CLEANUP_MARKER="$tmp/cleanup-pins" \
	MOCK_UID_POLICY_MARKER="$tmp/uid-policy-updates" \
    AKIHALINK_STARTUP_WAIT_SECONDS="${AKIHALINK_STARTUP_WAIT_SECONDS:-4}" \
	MOCK_SERVICE_LOG="$tmp/service-calls" \
	MOCK_IP_LOG="$tmp/ip-calls" \
	MOCK_DNS_CACHE_FLUSH_FAIL="${MOCK_DNS_CACHE_FLUSH_FAIL:-0}" \
	MOCK_CHECK_MARKER="$tmp/check-calls" \
	MOCK_CORE_VERSION="${MOCK_CORE_VERSION:-}" \
	PATH="$tmp/mock-bin:$PATH" \
    MOCK_MAIN_API_READY="${MOCK_MAIN_API_READY:-1}" \
    MOCK_CORE_EXIT="${MOCK_CORE_EXIT:-0}" \
    "$shell_bin" "$ctl" "$@"
}

version_json="$(run_ctl version)"
printf '%s' "$version_json" | grep -q '"corePatchSet":"akihalink-upstream-ebpf-v16"'
stale_version_json="$(MOCK_CORE_VERSION='sing-box version v1.14.0-rc.1-9-g90bb3d43-akihalink-upstream-ebpf-v9' run_ctl version)"
printf '%s' "$stale_version_json" | grep -q '"corePatchSet":""'

set_system_resolver_fixture() {
  rm -rf "$tmp/proc/1051"
  [ "${1:-ready}" = ready ] || return 0
  mkdir -p "$tmp/proc/1051"
  printf '%s\n' netd > "$tmp/proc/1051/comm"
  printf 'Name:\tnetd\nUid:\t0\t0\t0\t0\n' > "$tmp/proc/1051/status"
  : > "$tmp/proc/1051/exe"
  printf 'netd\0' > "$tmp/proc/1051/cmdline"
}

set_system_resolver_fixture ready

fresh_probe="$(run_ctl probe)"
printf '%s' "$fresh_probe" | grep -q '"ok":true'
printf '%s' "$fresh_probe" | grep -q '"verifierError":null'
hotspot_probe="$(run_ctl probe-hotspot)"
printf '%s' "$hotspot_probe" | grep -q '"supported":true'

run_ctl apply "$tmp/valid.json"
rm -f "$tmp/observability-snapshots"
started="$(run_ctl start)"
printf '%s' "$started" | grep -q '"desiredState":"running"'
printf '%s' "$started" | grep -q '"actualState":"running"'
printf '%s' "$started" | grep -q '"controllerReady":true'
printf '%s' "$started" | grep -q '"ebpfAttached":true'
printf '%s' "$started" | grep -q '"systemResolverAttached":true'
printf '%s' "$started" | grep -q '"startupHealth":"healthy"'
printf '%s' "$started" | grep -q '"androidDnsCacheFlush":"ok"'
printf '%s' "$started" | grep -q '"dnsPlainCaptureCount":2'
grep -q '^call dnsresolver 11 i32 100$' "$tmp/service-calls"
printf '%s' "$started" | grep -q '"lastError":null'
grep -q '^WARN ' "$tmp/data/log/core.log"
if grep -q 'eBPF inbound attached' "$tmp/data/log/core.log"; then
  printf '%s\n' 'Main readiness still depends on the info-level eBPF log' >&2
  exit 1
fi
[ ! -e "$tmp/observability-snapshots" ]

# Protocol 16 activation validates a staged candidate once, avoids a restart
# when the verified running configuration is unchanged, and never mutates the
# live configuration for a rejected candidate.
: > "$tmp/check-calls"
activated="$(run_ctl activate "$tmp/valid.json" 0123456789abcdef)"
printf '%s' "$activated" | grep -q '"outcome":"already_running"'
[ "$(wc -l < "$tmp/check-calls")" -eq 1 ]
before_invalid="$(cksum "$tmp/data/config/current.json")"
if invalid_activation="$(run_ctl activate "$tmp/missing-controller.json" 0123456789abcdef 2>&1)"; then
  printf '%s\n' 'Invalid activation unexpectedly succeeded' >&2
  exit 1
fi
printf '%s' "$invalid_activation" | grep -q '"failureStage":"validation"'
[ "$(cksum "$tmp/data/config/current.json")" = "$before_invalid" ]

if rollback_activation="$(MOCK_CORE_EXIT_ONCE=1 run_ctl activate "$tmp/excluded.json" 0123456789abcdef 2>&1)"; then
  printf '%s\n' 'Failed activation unexpectedly succeeded' >&2
  exit 1
fi
printf '%s' "$rollback_activation" | grep -q '"rolledBack":true'
cmp -s "$tmp/data/config/current.json" "$tmp/valid.json"
printf '%s' "$rollback_activation" | grep -q '"actualState":"running"'

fast_status="$(run_ctl status-fast)"
printf '%s' "$fast_status" | grep -q '"actualState":"running"'
printf '%s' "$fast_status" | grep -q '"daemonState":"running"'
[ ! -e "$tmp/observability-snapshots" ]

health_status="$(run_ctl status-health)"
printf '%s' "$health_status" | grep -q '"actualState":"running"'
printf '%s' "$health_status" | grep -q '"controllerReady":true'

cp "$tmp/data/runtime/core.ready" "$tmp/core.ready.saved"
rm -f "$tmp/data/runtime/core.ready"
markerless_fast_status="$(run_ctl status-fast)"
printf '%s' "$markerless_fast_status" | grep -q '"actualState":"running"'
markerless_health_status="$(run_ctl status-health)"
printf '%s' "$markerless_health_status" | grep -q '"actualState":"running"'
printf '%s' "$markerless_health_status" | grep -q '"controllerReady":true'
printf '%s' "$markerless_health_status" | grep -q '"systemResolverAttached":true'
mv "$tmp/core.ready.saved" "$tmp/data/runtime/core.ready"

marker_fast_started_at="$(date +%s%3N)"
marker_fast_status="$(MOCK_PROCESS_STATUS_DELAY=10 run_ctl status-fast)"
marker_fast_elapsed=$(( $(date +%s%3N) - marker_fast_started_at ))
printf '%s' "$marker_fast_status" | grep -q '"actualState":"running"'
printf '%s' "$marker_fast_status" | grep -q '"systemResolverAttached":true'
if [ "$marker_fast_elapsed" -ge "$latency_limit_ms" ]; then
  printf '%s\n' "Marker-backed fast status took ${marker_fast_elapsed}ms" >&2
  exit 1
fi

fast_started_at="$(date +%s%3N)"
slow_snapshot_fast_status="$(MOCK_SNAPSHOT_DELAY=5 run_ctl status-fast)"
fast_elapsed=$(( $(date +%s%3N) - fast_started_at ))
printf '%s' "$slow_snapshot_fast_status" | grep -q '"actualState":"running"'
if [ "$fast_elapsed" -ge 4000 ]; then
  printf '%s\n' "Fast status took ${fast_elapsed}ms" >&2
  exit 1
fi
[ ! -e "$tmp/observability-snapshots" ]

restarted="$(run_ctl restart)"
printf '%s' "$restarted" | grep -q '"actualState":"running"'
[ ! -e "$tmp/observability-snapshots" ]

# A normal config reload rebuilds the FD-owned cilium backend without launching
# the legacy disposable cleanup core.
reload_started_at="$(date +%s%3N)"
run_ctl apply "$tmp/valid.json"
reload_elapsed=$(( $(date +%s%3N) - reload_started_at ))
if [ "$reload_elapsed" -ge "$reload_latency_limit_ms" ]; then
  printf '%s\n' "Cilium eBPF reload took ${reload_elapsed}ms" >&2
  exit 1
fi
if [ -e "$tmp/data/runtime/cleanup.pid" ] || [ -e "$tmp/data/runtime/cleanup.supervisor.pid" ]; then
  printf '%s\n' 'Cilium eBPF reload unexpectedly launched cleanup core' >&2
  exit 1
fi

large_reload_started_at="$(date +%s%3N)"
run_ctl apply "$tmp/large-compact.json"
large_reload_elapsed=$(( $(date +%s%3N) - large_reload_started_at ))
if [ "$large_reload_elapsed" -ge "$reload_latency_limit_ms" ]; then
  printf '%s\n' "Large compact config reload took ${large_reload_elapsed}ms" >&2
  exit 1
fi
if [ -e "$tmp/data/runtime/cleanup.pid" ] || [ -e "$tmp/data/runtime/cleanup.supervisor.pid" ]; then
  printf '%s\n' 'Large compact config unexpectedly launched cleanup core' >&2
  exit 1
fi
if [ "${AKIHALINK_TEST_LARGE_CONFIG_ONLY:-0}" = 1 ]; then
  printf '%s\n' "Large compact config reloaded in ${large_reload_elapsed}ms"
  exit 0
fi
run_ctl apply "$tmp/valid.json"

# App exclusion updates must keep the active core and update only its eBPF UID
# map. The fixture marks the private request instead of stopping the supervisor.
core_before_uid_update="$(cat "$tmp/data/runtime/core.pid")"
uid_update_started_at="$(date +%s%3N)"
run_ctl apply-exclusions "$tmp/excluded.json" "$tmp/exclusion-targets.json"
uid_update_elapsed=$(( $(date +%s%3N) - uid_update_started_at ))
if [ "$uid_update_elapsed" -ge "$uid_update_latency_limit_ms" ]; then
  printf '%s\n' "Live UID policy update took ${uid_update_elapsed}ms" >&2
  exit 1
fi
[ "$(cat "$tmp/data/runtime/core.pid")" = "$core_before_uid_update" ]
grep -q '^update$' "$tmp/uid-policy-updates"

degraded="$(MOCK_DNS_CACHE_FLUSH_FAIL=1 run_ctl restart)"
printf '%s' "$degraded" | grep -q '"actualState":"running"'
printf '%s' "$degraded" | grep -q '"startupHealth":"degraded"'
printf '%s' "$degraded" | grep -q '"androidDnsCacheFlush":"degraded"'

status_started_at="$(date +%s%3N)"
status="$(MOCK_SNAPSHOT_DELAY=5 run_ctl status)"
status_elapsed=$(( $(date +%s%3N) - status_started_at ))
printf '%s' "$status" | grep -q '"actualState":"running"'
if [ "$status_elapsed" -ge "$status_latency_limit_ms" ]; then
  printf '%s\n' "Compact status took ${status_elapsed}ms" >&2
  exit 1
fi
[ ! -e "$tmp/observability-snapshots" ]
diagnostics_started_at="$(date +%s%3N)"
diagnostics="$(MOCK_SNAPSHOT_DELAY=5 run_ctl diagnostics)"
diagnostics_elapsed=$(( $(date +%s%3N) - diagnostics_started_at ))
printf '%s' "$diagnostics" | grep -q '"systemResolverAttached":true'
if [ "$diagnostics_elapsed" -ge "$diagnostics_latency_limit_ms" ]; then
  printf '%s\n' "Compact diagnostics took ${diagnostics_elapsed}ms" >&2
  exit 1
fi
[ ! -e "$tmp/observability-snapshots" ]
if printf '%s' "$diagnostics" | grep -Eq '"(pid|daemonPid|corePid|managedPid|port)":[0-9]'; then
	printf '%s\n' 'Diagnostics exposed a process identifier or port' >&2
	exit 1
fi
probe="$(run_ctl probe)"
printf '%s' "$probe" | grep -q '"ok":true'
printf '%s' "$probe" | grep -q '"btfRootReadable":true'
printf '%s' "$probe" | grep -q '"bpfLinkUpdate":false'
printf '%s' "$probe" | grep -q '"tcpMtuProbing":1'
sampling="$(run_ctl observability-sampling acquire)"
printf '%s' "$sampling" | grep -q '"tcpSamplingLeaseActive":true'
printf '%s' "$sampling" | grep -q '"tcpSampleGeneration":9'
run_ctl observability-sampling renew | grep -q '"tcpSamplingMode":"on_demand"'
run_ctl observability-sampling release | grep -q '"tcpSamplingLeaseActive":false'
run_ctl telemetry-status | grep -q '"activeFlows":1'
run_ctl telemetry-clear | grep -q '"running":true'
[ "$(run_ctl network-events 25)" = '[]' ]
run_ctl path-status | grep -q '"routeGeneration":1'
snapshot="$(run_ctl observability-snapshot 25)"
printf '%s' "$snapshot" | grep -q '"routeGeneration":1'
if run_ctl network-events 0 >/dev/null 2>&1; then
  printf '%s\n' 'network-events accepted an out-of-range limit' >&2
  exit 1
fi
if run_ctl observability-sampling invalid >/dev/null 2>&1; then
  printf '%s\n' 'observability-sampling accepted an invalid action' >&2
  exit 1
fi

rm -f "$tmp/observability-snapshots"
core_before_stop="$(cat "$tmp/data/runtime/core.pid")"
supervisor_before_stop="$(cat "$tmp/data/runtime/supervisor.pid")"
stop_started_at="$(date +%s%3N)"
run_ctl stop >/dev/null
stop_elapsed=$(( $(date +%s%3N) - stop_started_at ))
if [ "$stop_elapsed" -ge "$stop_latency_limit_ms" ]; then
  printf '%s\n' "Main core stop took ${stop_elapsed}ms" >&2
  exit 1
fi
[ ! -e "$tmp/observability-snapshots" ]
[ ! -e "$tmp/data/runtime/core.pid" ]
[ ! -e "$tmp/data/runtime/supervisor.pid" ]
! kill -0 "$core_before_stop" 2>/dev/null
! kill -0 "$supervisor_before_stop" 2>/dev/null

set_system_resolver_fixture absent
if failure="$(AKIHALINK_STARTUP_WAIT_SECONDS=1 run_ctl start 2>&1)"; then
  printf '%s\n' 'Main core without a verified Android system resolver unexpectedly became ready' >&2
  exit 1
fi
printf '%s' "$failure" | grep -q 'critical readiness checks passed'
[ "$(cat "$tmp/data/runtime/desired")" = stopped ]
[ "$(cat "$tmp/data/runtime/actual")" = failed ]
[ ! -e "$tmp/data/runtime/core.pid" ]
[ ! -e "$tmp/data/runtime/supervisor.pid" ]
set_system_resolver_fixture ready

if failure="$(MOCK_MAIN_API_READY=0 run_ctl start 2>&1)"; then
  printf '%s\n' 'Main core without a local controller unexpectedly became ready' >&2
  exit 1
fi
printf '%s' "$failure" | grep -q '"desiredState":"stopped"'
printf '%s' "$failure" | grep -q '"actualState":"failed"'
printf '%s' "$failure" | grep -q 'critical readiness checks passed'
[ "$(cat "$tmp/data/runtime/desired")" = stopped ]
[ "$(cat "$tmp/data/runtime/actual")" = failed ]
[ ! -e "$tmp/data/runtime/core.pid" ]
[ ! -e "$tmp/data/runtime/supervisor.pid" ]

printf '%s\n' running > "$tmp/data/runtime/desired"
MOCK_MAIN_API_READY=0 run_ctl boot
[ "$(cat "$tmp/data/runtime/desired")" = stopped ]
[ "$(cat "$tmp/data/runtime/actual")" = failed ]
[ ! -e "$tmp/data/runtime/core.pid" ]
[ ! -e "$tmp/data/runtime/supervisor.pid" ]

failed_start_at="$(date +%s%3N)"
if failure="$(MOCK_CORE_EXIT=1 run_ctl start 2>&1)"; then
  printf '%s\n' 'Exiting main core unexpectedly became ready' >&2
  exit 1
fi
failed_start_elapsed=$(( $(date +%s%3N) - failed_start_at ))
if [ "$failed_start_elapsed" -ge "$failed_start_latency_limit_ms" ]; then
  printf '%s\n' "Initial core failure took ${failed_start_elapsed}ms" >&2
  exit 1
fi
printf '%s' "$failure" | grep -q '"desiredState":"stopped"'
printf '%s' "$failure" | grep -q '"actualState":"failed"'
printf '%s' "$failure" | grep -q '"lastError":"'
[ ! -e "$tmp/data/runtime/core.pid" ]
[ ! -e "$tmp/data/runtime/supervisor.pid" ]

if run_ctl apply "$tmp/missing-controller.json" >/dev/null 2>&1; then
  printf '%s\n' 'Main configuration without the private controller was accepted' >&2
  exit 1
fi

if legacy_apply="$(run_ctl apply "$tmp/legacy-system-resolver.json" 2>&1)"; then
  printf '%s\n' 'Legacy Android system resolver configuration was accepted' >&2
  exit 1
fi
printf '%s' "$legacy_apply" | grep -Fq 'Saved configuration uses removed include_android_system_resolver'

cp "$tmp/legacy-system-resolver.json" "$tmp/data/config/current.json"
if legacy_start="$(run_ctl start 2>&1)"; then
  printf '%s\n' 'Legacy saved configuration was accepted during startup' >&2
  exit 1
fi
printf '%s' "$legacy_start" | grep -Fq \
  '"lastError":"Saved configuration uses removed include_android_system_resolver; open AkihaLink and apply the selected node again"'

printf '%s\n' 'json: unknown field "include_android_system_resolver" at C:\legacy' \
  > "$tmp/data/runtime/last_error"
escaped_status="$(run_ctl status-fast)"
printf '%s' "$escaped_status" | grep -Fq \
  '"lastError":"json: unknown field \"include_android_system_resolver\" at C:\\legacy"'

printf '%s\n' 'Main core readiness controller fixtures passed'
