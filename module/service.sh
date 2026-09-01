#!/system/bin/sh

MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done

sleep 3
"$MODDIR/bin/akihalinkctl" boot >/dev/null 2>&1 &
