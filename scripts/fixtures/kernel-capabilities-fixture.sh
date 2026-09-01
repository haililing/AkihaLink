#!/usr/bin/env bash

set -u

FIXTURE_ROOT=$1
HELPER=$2
export AKIHALINK_PROC_ROOT="$FIXTURE_ROOT/proc"
export AKIHALINK_SYS_ROOT="$FIXTURE_ROOT/sys"
export AKIHALINK_RUNTIME_ROOT="$FIXTURE_ROOT/runtime"
if [ -r "$FIXTURE_ROOT/config" ]; then
  export AKIHALINK_CONFIG_FILE="$FIXTURE_ROOT/config"
else
  unset AKIHALINK_CONFIG_FILE || true
fi
if [ -e "$FIXTURE_ROOT/cgroup-bpf-probed" ]; then
  export AKIHALINK_CGROUP_BPF_PROBED=true
fi

json_escape() {
  printf '%s' "$1" | sed ':a;N;$!ba;s/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/\r/\\r/g; s/\n/\\n/g'
}

. "$HELPER"
printf '{"kernelCapabilities":'
kernel_capabilities_json
printf ',"psi":'
psi_json
printf '}\n'
