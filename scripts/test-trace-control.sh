#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

DATA="$WORK/data"
MARKER="$WORK/trace_marker"
CORE="$WORK/fake-core"
VALID="$WORK/valid.json"
INVALID="$WORK/invalid.json"
TRACE_ID=0123456789abcdef

cat >"$CORE" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  check)
    config="${3:-}"
    if grep -q '"fail_check":true' "$config"; then
      printf '%s\n' 'safe check failure' >&2
      exit 1
    fi
    ;;
  version)
    printf '%s\n' 'sing-box benchmark'
    ;;
  akihalink-daemon)
    exit 1
    ;;
  run)
    exit 0
    ;;
esac
EOF
chmod 0755 "$CORE"

printf '%s\n' '{"inbounds":[{"type":"ebpf"}],"outbounds":[],"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090"}}}' >"$VALID"
printf '%s\n' '{"fail_check":true,"inbounds":[{"type":"ebpf"}],"outbounds":[],"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090"}}}' >"$INVALID"
: >"$MARKER"

run_ctl() {
  AKIHALINK_DATA_DIR="$DATA" \
    AKIHALINK_CORE="$CORE" \
    AKIHALINK_TRACE_MARKER="$MARKER" \
    bash "$ROOT/module/bin/akihalinkctl" "$@"
}

run_ctl apply "$VALID" "$TRACE_ID"
test ! -e "$DATA/runtime/trace-context"
mapfile -t success_markers <"$MARKER"
test "${#success_markers[@]}" -eq 4
[[ "${success_markers[0]}" == *"AKL/config_check/$TRACE_ID" ]]
[[ "${success_markers[1]}" == E\|* ]]
[[ "${success_markers[2]}" == *"AKL/config_commit/$TRACE_ID" ]]
[[ "${success_markers[3]}" == E\|* ]]

: >"$MARKER"
if run_ctl apply "$INVALID" "$TRACE_ID" >/dev/null 2>&1; then
  echo "invalid configuration unexpectedly succeeded" >&2
  exit 1
fi
test ! -e "$DATA/runtime/trace-context"
grep -Fq "AKL/config_check/$TRACE_ID" "$MARKER"
if grep -Fq 'safe check failure' "$MARKER"; then
  echo "raw errors leaked into trace markers" >&2
  exit 1
fi

run_ctl apply "$VALID"
printf '%s\n' "$TRACE_ID" >"$DATA/runtime/trace-context"
run_ctl cleanup >/dev/null
test ! -e "$DATA/runtime/trace-context"

echo "Perfetto trace controller fixtures passed"
