#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
ctl="$root/module/bin/akihalinkctl"
shell_bin="${AKIHALINK_TEST_SH:-sh}"
tmp="$(mktemp -d)"

cleanup() {
  AKIHALINK_DATA_DIR="$tmp/data" AKIHALINK_CORE="$tmp/mock-core" "$shell_bin" "$ctl" speedtest-stop >/dev/null 2>&1 || true
  rm -rf "$tmp"
}
trap cleanup EXIT

cat > "$tmp/mock-core" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  akihalink-daemon)
    shift
    runtime=""
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --core) shift 2 ;;
        --runtime) runtime=$2; shift 2 ;;
        process)
          operation=$2
          pid=$3
          if [ "$operation" = status ]; then
            if kill -0 "$pid" 2>/dev/null; then
              exit 0
            fi
            rm -rf "${AKIHALINK_TEST_PROC_ROOT:-}/$pid"
            exit 1
          else
            signal=${6:-15}
            kill "-$signal" "$pid" 2>/dev/null
          fi
          exit
          ;;
        auxiliary)
          shift
          if [ "${1:-}" = --timeout ]; then shift 2; fi
          name=$1
          config=$2
          log=$3
          "$0" run -c "$config" >> "$log" 2>&1 &
          child=$!
          printf '%s\n' "$child" > "$runtime/$name.pid"
          awk '{print $22}' "/proc/$child/stat" > "$runtime/$name.starttime"
          printf '%s\n' $$ > "$runtime/$name.supervisor.pid"
          awk '{print $22}' "/proc/$$/stat" > "$runtime/$name.supervisor.starttime"
          trap 'kill -TERM "$child" 2>/dev/null; wait "$child" 2>/dev/null; exit 0' TERM INT
          wait "$child"
          exit
          ;;
        *) shift ;;
      esac
    done
    exit 2
    ;;
  check) exit 0 ;;
  version) printf '%s\n' 'sing-box test core' ;;
  run)
    proc_root="${AKIHALINK_TEST_PROC_ROOT:-}"
    if [ -n "$proc_root" ] && [ "${MOCK_DISABLE_PROC_TABLE:-0}" != 1 ]; then
      mkdir -p "$proc_root/$$/net"
      if [ "${MOCK_SPEEDTEST_API_READY:-1}" = 1 ]; then
        cat > "$proc_root/$$/net/tcp" <<'TABLE'
  sl  local_address rem_address   st
   0: 0100007F:4A92 00000000:0000 0A
TABLE
      else
        cat > "$proc_root/$$/net/tcp" <<'TABLE'
  sl  local_address rem_address   st
TABLE
      fi
    fi
    if [ "${MOCK_SPEEDTEST_IGNORE_TERM:-0}" = 1 ]; then
      trap '' TERM
      trap 'exit 0' INT
    else
      trap 'exit 0' TERM INT
    fi
    while :; do sleep 1; done
    ;;
  *) exit 2 ;;
esac
EOF
chmod 0755 "$tmp/mock-core"

mkdir -p "$tmp/data/runtime" "$tmp/data/config" "$tmp/data/log"
printf '%s\n' stopped > "$tmp/data/runtime/desired"
printf '%s\n' stopped > "$tmp/data/runtime/actual"
printf '%s\n' rule > "$tmp/data/runtime/mode"
printf '%s\n' '{"unchanged":true}' > "$tmp/data/config/current.json"

cat > "$tmp/valid.json" <<'EOF'
{
  "dns": {"servers": [{"type": "https", "tag": "local-dns", "server": "223.5.5.5", "tls": {"enabled": true, "server_name": "dns.alidns.com"}}]},
  "outbounds": [{"type": "shadowsocks", "tag": "node-test", "server": "node.example", "server_port": 443, "method": "aes-128-gcm", "password": "test"}],
  "experimental": {"clash_api": {"external_controller": "127.0.0.1:19090", "secret": "test"}}
}
EOF

run_ctl() {
  AKIHALINK_DATA_DIR="$tmp/data" AKIHALINK_CORE="$tmp/mock-core" \
    AKIHALINK_TEST_PROC_ROOT="${MOCK_PROC_ROOT:-$tmp/proc}" \
    AKIHALINK_DISABLE_WATCHDOG="${MOCK_DISABLE_WATCHDOG:-1}" \
    MOCK_DISABLE_PROC_TABLE="${MOCK_DISABLE_PROC_TABLE:-0}" \
    MOCK_SPEEDTEST_API_READY="${MOCK_SPEEDTEST_API_READY:-1}" \
    MOCK_SPEEDTEST_IGNORE_TERM="${MOCK_SPEEDTEST_IGNORE_TERM:-0}" \
    "$shell_bin" "$ctl" "$@"
}

started="$(run_ctl speedtest-start "$tmp/valid.json")"
printf '%s' "$started" | grep -q '"running":true'
printf '%s' "$started" | grep -q '"port":19090'
printf '%s' "$started" | grep -q '"readiness":"ready"'
! grep -q 'restful api listening' "$tmp/data/log/speedtest.log"
[ "$(cat "$tmp/data/runtime/desired")" = stopped ]
[ "$(cat "$tmp/data/runtime/actual")" = stopped ]
cmp -s "$tmp/data/config/current.json" <(printf '%s\n' '{"unchanged":true}')

status="$(run_ctl speedtest-status)"
printf '%s' "$status" | grep -q '"running":true'
run_ctl speedtest-stop >/dev/null

# A ROM can hide per-process socket tables even from the module shell. The
# controller must keep a live auxiliary core and let the App verify HTTP readiness.
started_without_proc="$(MOCK_DISABLE_PROC_TABLE=1 run_ctl speedtest-start "$tmp/valid.json")"
printf '%s' "$started_without_proc" | grep -q '"running":true'
printf '%s' "$started_without_proc" | grep -q '"readiness":"unverified"'
run_ctl speedtest-stop >/dev/null

set +e
failed_listener="$(MOCK_SPEEDTEST_API_READY=0 run_ctl speedtest-start "$tmp/valid.json" 2>/dev/null)"
failed_listener_code=$?
set -e
[ "$failed_listener_code" -ne 0 ]
printf '%s' "$failed_listener" | grep -q '"readiness":"failed"'
[ ! -e "$tmp/data/runtime/speedtest.pid" ]
[ ! -e "$tmp/data/runtime/speedtest.supervisor.pid" ]

ignored_term="$(MOCK_SPEEDTEST_IGNORE_TERM=1 run_ctl speedtest-start "$tmp/valid.json")"
printf '%s' "$ignored_term" | grep -q '"readiness":"ready"'
ignored_pid="$(cat "$tmp/data/runtime/speedtest.pid")"
run_ctl speedtest-stop >/dev/null
! kill -0 "$ignored_pid" 2>/dev/null
[ ! -e "$tmp/data/runtime/speedtest.pid" ]
[ ! -e "$tmp/data/runtime/speedtest.starttime" ]
[ ! -e "$tmp/data/runtime/speedtest.supervisor.pid" ]
[ ! -e "$tmp/data/runtime/speedtest.supervisor.starttime" ]

cat > "$tmp/invalid.json" <<'EOF'
{"inbounds":[],"experimental":{"clash_api":{"external_controller":"127.0.0.1:19090"}}}
EOF
if run_ctl speedtest-start "$tmp/invalid.json" >/dev/null 2>&1; then
  printf '%s\n' 'speedtest-start accepted an inbound' >&2
  exit 1
fi

cat > "$tmp/nonlocal.json" <<'EOF'
{"outbounds":[],"experimental":{"clash_api":{"external_controller":"0.0.0.0:19090"}}}
EOF
if run_ctl speedtest-start "$tmp/nonlocal.json" >/dev/null 2>&1; then
  printf '%s\n' 'speedtest-start accepted a non-local controller' >&2
  exit 1
fi

stopped="$(run_ctl speedtest-stop)"
printf '%s' "$stopped" | grep -q '"running":false'
run_ctl speedtest-stop >/dev/null
[ ! -e "$tmp/data/runtime/speedtest.pid" ]
printf '%s\n' 'Auxiliary speed-test controller fixtures passed'
