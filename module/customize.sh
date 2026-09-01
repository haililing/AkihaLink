#!/system/bin/sh

umask 077
SKIPUNZIP=0

fail() {
  ui_print "! $1"
  abort "$1"
}

API="$(getprop ro.build.version.sdk)"
ARCH="$(getprop ro.product.cpu.abi)"
[ "$API" -ge 36 ] 2>/dev/null || fail "AkihaLink requires Android 16 (API 36) or newer"
[ "$ARCH" = "arm64-v8a" ] || fail "AkihaLink only supports arm64-v8a"
[ "${KSU:-false}" = "true" ] || fail "Install with KernelSU, KernelSU-Next, SukiSU Ultra, or ReSukiSU"
grep -q ' - cgroup2 ' /proc/self/mountinfo || fail "AkihaLink requires a mounted cgroup2 hierarchy"
[ -d /sys/fs/bpf ] || fail "AkihaLink requires a mounted BPF filesystem"
[ -f "$MODPATH/bin/sing-box" ] || fail "Module package is missing the sing-box binary"

DATA=/data/adb/akihalink
PREVIOUS_DESIRED="$(cat "$DATA/runtime/desired" 2>/dev/null)"
INSTALLED_CONTROL=/data/adb/modules/akihalink/bin/akihalinkctl
if [ -x "$INSTALLED_CONTROL" ]; then
  "$INSTALLED_CONTROL" stop >/dev/null 2>&1 || fail "Unable to stop the installed AkihaLink core"
  "$INSTALLED_CONTROL" cleanup-stale >/dev/null 2>&1 || fail "Unable to clean the installed AkihaLink eBPF links"
fi
rm -f "$DATA/runtime/core.ready" "$DATA/runtime/startup.ready" \
  "$DATA/runtime/uid-policy.sock" "$DATA/runtime/core.pid" \
  "$DATA/runtime/core.starttime" "$DATA/runtime/supervisor.pid" \
  "$DATA/runtime/supervisor.starttime"
rm -f "$DATA/state/adaptive-scores-v1.json" "$DATA/state/adaptive-scores-v1.json.tmp" \
  "$DATA/state/adaptive-scores-v2.json" "$DATA/state/adaptive-scores-v2.json.tmp"

mkdir -p "$DATA" "$DATA/config" "$DATA/runtime" "$DATA/log" "$DATA/rules"
chmod 0700 "$DATA" "$DATA/config" "$DATA/runtime" "$DATA/log" "$DATA/rules"

for RULE in "$MODPATH"/rules/*.srs; do
  [ -f "$RULE" ] || continue
  cp -f "$RULE" "$DATA/rules/$(basename "$RULE")"
  chmod 0600 "$DATA/rules/$(basename "$RULE")"
done

[ -f "$DATA/runtime/desired" ] || printf '%s\n' stopped > "$DATA/runtime/desired"
[ "$PREVIOUS_DESIRED" != running ] || printf '%s\n' running > "$DATA/runtime/desired"
[ -f "$DATA/runtime/mode" ] || printf '%s\n' rule > "$DATA/runtime/mode"
chmod 0600 "$DATA/runtime/desired" "$DATA/runtime/mode"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/sing-box" 0 0 0755
set_perm "$MODPATH/bin/akihalinkctl" 0 0 0755
set_perm "$MODPATH/bin/supervisor.sh" 0 0 0755
set_perm "$MODPATH/bin/kernel-capabilities.sh" 0 0 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- Android API: $API"
ui_print "- Architecture: $ARCH"
ui_print "- Persistent data: $DATA"
ui_print "- Control protocol: 16"
ui_print "- Reboot after installing or updating the module"
