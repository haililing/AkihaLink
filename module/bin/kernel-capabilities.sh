#!/system/bin/sh

PROC_ROOT=${AKIHALINK_PROC_ROOT:-/proc}
SYS_ROOT=${AKIHALINK_SYS_ROOT:-/sys}
CAP_RUNTIME=${AKIHALINK_RUNTIME_ROOT:-${RUNTIME:-/data/adb/akihalink/runtime}}
KCONFIG_FILE="$CAP_RUNTIME/kernel.config.$$"
KCONFIG_SOURCE=runtime_only

read_value() {
  [ -r "$1" ] || return 1
  head -n 1 "$1" 2>/dev/null | tr -d '\r\n'
}

json_string_or_null() {
  value=$1
  if [ -n "$value" ]; then
    printf '"%s"' "$(json_escape "$value")"
  else
    printf 'null'
  fi
}

words_json() {
  words=$1
  first=true
  printf '['
  for word in $words; do
    [ -n "$word" ] || continue
    if $first; then first=false; else printf ','; fi
    printf '"%s"' "$(json_escape "$word")"
  done
  printf ']'
}

load_kernel_config() {
  mkdir -p "$CAP_RUNTIME" 2>/dev/null
  rm -f "$KCONFIG_FILE"
  if [ -n "${AKIHALINK_CONFIG_FILE:-}" ] && [ -r "$AKIHALINK_CONFIG_FILE" ]; then
    cp "$AKIHALINK_CONFIG_FILE" "$KCONFIG_FILE" 2>/dev/null || true
    KCONFIG_SOURCE=provided_config
  elif [ -r "$PROC_ROOT/config.gz" ]; then
    if command -v zcat >/dev/null 2>&1; then
      zcat "$PROC_ROOT/config.gz" > "$KCONFIG_FILE" 2>/dev/null || true
    elif command -v gzip >/dev/null 2>&1; then
      gzip -dc "$PROC_ROOT/config.gz" > "$KCONFIG_FILE" 2>/dev/null || true
    elif command -v toybox >/dev/null 2>&1; then
      toybox zcat "$PROC_ROOT/config.gz" > "$KCONFIG_FILE" 2>/dev/null || true
    elif [ -x /data/adb/ksu/bin/busybox ]; then
      /data/adb/ksu/bin/busybox zcat "$PROC_ROOT/config.gz" > "$KCONFIG_FILE" 2>/dev/null || true
    fi
  fi
  if [ -s "$KCONFIG_FILE" ]; then
    [ "$KCONFIG_SOURCE" = provided_config ] || KCONFIG_SOURCE=proc_config_gz
  else
    KCONFIG_SOURCE=runtime_only
    rm -f "$KCONFIG_FILE"
  fi
}

config_state() {
  symbol=$1
  if [ ! -s "$KCONFIG_FILE" ]; then
    printf unknown
  elif grep -Eq "^${symbol}=(y|m)$" "$KCONFIG_FILE"; then
    printf enabled
  elif grep -q "^${symbol}=n$" "$KCONFIG_FILE" || grep -q "^# ${symbol} is not set$" "$KCONFIG_FILE"; then
    printf disabled
  else
    printf unknown
  fi
}

runtime_markers_or_config_state() {
  symbol=$1
  shift
  for marker in "$@"; do
    if [ -e "$marker" ]; then
      printf enabled
      return
    fi
  done
  config_state "$symbol"
}

word_present() {
  wanted=$1
  shift
  for candidate in "$@"; do
    [ "$candidate" = "$wanted" ] && return 0
  done
  return 1
}

kallsyms_has() {
  pattern=$1
  [ -n "${KALLSYMS_CAPABILITY_MARKERS:-}" ] && \
    printf '%s\n' "$KALLSYMS_CAPABILITY_MARKERS" | grep -Eqi "$pattern" 2>/dev/null
}

runtime_or_config_state() {
  runtime_path=$1
  symbol=$2
  if [ -e "$runtime_path" ]; then
    printf enabled
  else
    config_state "$symbol"
  fi
}

bpf_jit_state() {
  value="$(read_value "$PROC_ROOT/sys/net/core/bpf_jit_enable" 2>/dev/null)"
  case "$value" in
    1|2) printf enabled; return ;;
    0) printf disabled; return ;;
  esac
  state="$(config_state CONFIG_BPF_JIT_ALWAYS_ON)"
  if [ "$state" = enabled ]; then printf enabled; else config_state CONFIG_BPF_JIT; fi
}

cgroup_bpf_state() {
  if [ "${AKIHALINK_CGROUP_BPF_PROBED:-false}" = true ]; then
    printf enabled
  else
    config_state CONFIG_CGROUP_BPF
  fi
}

tcp_fast_open_client_state() {
  value="$(read_value "$PROC_ROOT/sys/net/ipv4/tcp_fastopen" 2>/dev/null)"
  case "$value" in
    ''|*[!0-9]*) printf unknown ;;
    *)
      if [ $((value & 1)) -ne 0 ]; then printf enabled; else printf disabled; fi
      ;;
  esac
}

kernel_capabilities_json() {
  load_kernel_config
  KALLSYMS_CAPABILITY_MARKERS="$(grep -Ei 'hmbird|rekernel|fq_.*qdisc|sch_fq|tcp_.*brutal|brutal_.*cong' \
    "$PROC_ROOT/kallsyms" 2>/dev/null | head -n 64)"
  available="$(read_value "$PROC_ROOT/sys/net/ipv4/tcp_available_congestion_control" 2>/dev/null)"
  current="$(read_value "$PROC_ROOT/sys/net/ipv4/tcp_congestion_control" 2>/dev/null)"
  ecn="$(read_value "$PROC_ROOT/sys/net/ipv4/tcp_ecn" 2>/dev/null)"
  qdisc="$(read_value "$PROC_ROOT/sys/net/core/default_qdisc" 2>/dev/null)"
  case "$ecn" in ''|*[!0-9]*) ecn_json=null ;; *) ecn_json=$ecn ;; esac

  fq_state="$(runtime_markers_or_config_state CONFIG_NET_SCH_FQ "$SYS_ROOT/module/sch_fq")"
  if [ "$qdisc" = fq ] || grep -q '^sch_fq ' "$PROC_ROOT/modules" 2>/dev/null || \
    kallsyms_has 'fq_.*qdisc|sch_fq'; then fq_state=enabled; fi
  hmbird_state="$(runtime_markers_or_config_state CONFIG_HMBIRD_SCHED \
    "$SYS_ROOT/kernel/hmbird" "$SYS_ROOT/module/hmbird" "$PROC_ROOT/hmbird")"
  if kallsyms_has 'hmbird'; then hmbird_state=enabled; fi
  rekernel_state="$(runtime_markers_or_config_state CONFIG_REKERNEL_NETWORK \
    "$SYS_ROOT/module/rekernel" "$PROC_ROOT/rekernel")"
  if kallsyms_has 'rekernel'; then rekernel_state=enabled; fi
  brutal_state="$(runtime_markers_or_config_state CONFIG_TCP_CONG_BRUTAL "$SYS_ROOT/module/tcp_brutal")"
  if [ -n "$available" ]; then
    if word_present brutal $available; then brutal_state=enabled; else brutal_state=disabled; fi
  elif kallsyms_has 'tcp_.*brutal|brutal_.*cong'; then
    brutal_state=enabled
  fi

  printf '{"configSource":"%s","bpfJit":"%s","btf":"%s","cgroupBpf":"%s","fq":"%s","hmbird":"%s","rekernelNetwork":"%s","tcpBrutal":"%s","tcpFastOpenClient":"%s","availableTcpCongestionControls":' \
    "$KCONFIG_SOURCE" \
    "$(bpf_jit_state)" \
    "$(runtime_or_config_state "$SYS_ROOT/kernel/btf/vmlinux" CONFIG_DEBUG_INFO_BTF)" \
    "$(cgroup_bpf_state)" \
    "$fq_state" \
    "$hmbird_state" \
    "$rekernel_state" \
    "$brutal_state" \
    "$(tcp_fast_open_client_state)"
  words_json "$available"
  printf ',"currentTcpCongestionControl":'
  json_string_or_null "$current"
  printf ',"tcpEcn":%s,"defaultQdisc":' "$ecn_json"
  json_string_or_null "$qdisc"
  printf ',"btfRootReadable":%s,"coreRelocation":%s,"bpffs":%s,"bpfLink":%s,"bpfLinkUpdate":%s,"ringBuffer":%s,"sockOps":%s,"tcpInfo":%s,"rtnetlink":%s,"tcpMtuProbing":%s,"perSocketPmtu":%s,"perSocketMss":%s,"quicDplpmtud":%s,"pidfdOpen":%s,"pidfdSendSignal":%s,"pidfdPoll":%s' \
    "${AKIHALINK_BTF_ROOT_READABLE:-null}" \
    "${AKIHALINK_CORE_RELOCATION:-null}" \
    "${AKIHALINK_BPFFS:-null}" \
    "${AKIHALINK_BPF_LINK:-null}" \
    "${AKIHALINK_BPF_LINK_UPDATE:-null}" \
    "${AKIHALINK_RING_BUFFER:-null}" \
    "${AKIHALINK_SOCK_OPS:-null}" \
    "${AKIHALINK_TCP_INFO:-null}" \
    "${AKIHALINK_RTNETLINK:-null}" \
    "${AKIHALINK_TCP_MTU_PROBING:-null}" \
    "${AKIHALINK_PER_SOCKET_PMTU:-null}" \
    "${AKIHALINK_PER_SOCKET_MSS:-null}" \
    "${AKIHALINK_QUIC_DPLPMTUD:-null}" \
    "${AKIHALINK_PIDFD_OPEN:-null}" \
    "${AKIHALINK_PIDFD_SEND_SIGNAL:-null}" \
    "${AKIHALINK_PIDFD_POLL:-null}"
  printf '}'
  rm -f "$KCONFIG_FILE"
}

psi_json() {
  printf '{"cpu":'
  json_string_or_null "$(head -n 2 "$PROC_ROOT/pressure/cpu" 2>/dev/null)"
  printf ',"memory":'
  json_string_or_null "$(head -n 2 "$PROC_ROOT/pressure/memory" 2>/dev/null)"
  printf ',"io":'
  json_string_or_null "$(head -n 2 "$PROC_ROOT/pressure/io" 2>/dev/null)"
  printf '}'
}
