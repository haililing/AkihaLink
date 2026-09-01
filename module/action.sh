#!/system/bin/sh

MODDIR=${0%/*}
CTL="$MODDIR/bin/akihalinkctl"

if [ "$(cat /data/adb/akihalink/runtime/desired 2>/dev/null)" = "running" ]; then
  "$CTL" stop
else
  "$CTL" start
fi
