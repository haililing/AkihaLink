#!/system/bin/sh

MODDIR=${0%/*}
"$MODDIR/bin/akihalinkctl" stop >/dev/null 2>&1
"$MODDIR/bin/akihalinkctl" cleanup >/dev/null 2>&1
# /data/adb/akihalink is intentionally retained for a later reinstall.
