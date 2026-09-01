#!/system/bin/sh

umask 077
MODDIR=${0%/*}
DATA=${AKIHALINK_DATA_DIR:-/data/adb/akihalink}
RUNTIME="$DATA/runtime"
LOGDIR="$DATA/log"
CORE=${AKIHALINK_CORE:-$MODDIR/sing-box}
CONFIG="$DATA/config/current.json"

mkdir -p "$RUNTIME" "$LOGDIR"
printf '%s\n' $$ > "$RUNTIME/supervisor.pid"
awk '{print $22}' "/proc/$$/stat" > "$RUNTIME/supervisor.starttime" 2>/dev/null
chmod 0600 "$RUNTIME/supervisor.pid" "$RUNTIME/supervisor.starttime"

export AKIHALINK_RUNTIME="$RUNTIME"
trace_id="$(sed -n '1p' "$RUNTIME/trace-context" 2>/dev/null)"
case "$trace_id" in
  [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f])
    export AKIHALINK_TRACE_ID="$trace_id"
    ;;
esac
printf '%s\n' $$ > "$RUNTIME/observability.pid"
cp "$RUNTIME/supervisor.starttime" "$RUNTIME/observability.starttime" 2>/dev/null || true
chmod 0600 "$RUNTIME/observability.pid" "$RUNTIME/observability.starttime"
: > "$LOGDIR/observability.log"
chmod 0600 "$LOGDIR/observability.log"
exec "$CORE" akihalink-daemon \
  --socket "$RUNTIME/observability.sock" \
  --pin-root "/sys/fs/bpf/akihalink/generic/v1" \
  --runtime-config "$CONFIG" \
  --core-log "$LOGDIR/core.log" \
  --core "$CORE" \
  --runtime "$RUNTIME" \
  supervise >> "$LOGDIR/observability.log" 2>&1
