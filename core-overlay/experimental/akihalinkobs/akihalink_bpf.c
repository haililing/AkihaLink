//go:build ignore

// SPDX-License-Identifier: GPL-2.0
#include <linux/bpf.h>
#include <linux/types.h>
#include <bpf/bpf_helpers.h>

#define AKIHA_ABI_VERSION 1
#define AKIHA_EVENT_CONNECT 1
#define AKIHA_EVENT_ESTABLISHED 2
#define AKIHA_EVENT_METRICS 3
#define AKIHA_EVENT_RETRANS 4
#define AKIHA_EVENT_STATE 5
#define AKIHA_EVENT_INTERVAL_NS 1000000000ULL

struct akihalink_event {
    __u16 abi_version;
    __u16 event_type;
    __u32 state;
    __u64 sequence;
    __u64 monotonic_ns;
    __u64 socket_cookie;
    __u32 handshake_us;
    __u32 srtt_us;
    __u32 snd_cwnd;
    __u32 total_retrans;
    __u32 segs_out;
    __u32 reserved;
    __u64 delivery_rate;
};

struct flow_state {
    __u64 connect_ns;
    __u64 last_emit_ns;
};

struct owner_state {
    __u64 magic;
    __u64 abi_version;
    __u64 generation;
    __u64 program_identity;
};

struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 1024 * 1024);
} events SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_LRU_HASH);
    __uint(max_entries, 4096);
    __type(key, __u64);
    __type(value, struct flow_state);
} flows SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(max_entries, 1);
    __type(key, __u32);
    __type(value, __u64);
} sequence SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(max_entries, 1);
    __type(key, __u32);
    __type(value, __u64);
} dropped SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(max_entries, 1);
    __type(key, __u32);
    __type(value, struct owner_state);
} owner SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(max_entries, 1);
    __type(key, __u32);
    __type(value, __u32);
} target_tgid SEC(".maps");

/*
 * A preserve_access_index flavor intentionally lists only the fields used by
 * AkihaLink. libbpf/cilium-ebpf relocates these accesses from target BTF.
 */
struct akihalink_sock_ops___local {
    __u32 op;
    __u32 args[4];
    __u32 reply;
    __u32 replylong[4];
    __u32 family;
    __u32 remote_ip4;
    __u32 local_ip4;
    __u32 remote_ip6[4];
    __u32 local_ip6[4];
    __u32 remote_port;
    __u32 local_port;
    __u32 is_fullsock;
    __u32 snd_cwnd;
    __u32 srtt_us;
    __u32 bpf_sock_ops_cb_flags;
    __u32 state;
    __u32 rtt_min;
    __u32 snd_ssthresh;
    __u32 rcv_nxt;
    __u32 snd_nxt;
    __u32 snd_una;
    __u32 mss_cache;
    __u32 ecn_flags;
    __u32 rate_delivered;
    __u32 rate_interval_us;
    __u32 packets_out;
    __u32 retrans_out;
    __u32 total_retrans;
    __u32 segs_in;
    __u32 data_segs_in;
    __u32 segs_out;
    __u32 data_segs_out;
} __attribute__((preserve_access_index));

static __always_inline void increment_dropped(void)
{
    __u32 key = 0;
    __u64 *value = bpf_map_lookup_elem(&dropped, &key);
    if (value)
        __sync_fetch_and_add(value, 1);
}

static __always_inline __u64 next_sequence(void)
{
    __u32 key = 0;
    __u64 *value = bpf_map_lookup_elem(&sequence, &key);
    if (!value)
        return 0;
    // Clang 21 correctly rejects using BPF XADD's return value. Use the
    // kernel-supported CMPXCHG form instead so emitted sequence values remain
    // unique across CPUs without relying on a racy load after increment.
    __u64 current = *value;
#pragma unroll
    for (int attempt = 0; attempt < 8; attempt++) {
        __u64 next = current + 1;
        __u64 observed = __sync_val_compare_and_swap(value, current, next);
        if (observed == current)
            return next;
        current = observed;
    }
    return 0;
}

static __always_inline void emit_event(
    struct bpf_sock_ops *raw,
    struct flow_state *flow,
    __u16 type,
    __u64 cookie,
    __u64 now)
{
    struct akihalink_sock_ops___local *skops =
        (struct akihalink_sock_ops___local *)raw;
    struct akihalink_event *event = bpf_ringbuf_reserve(&events, sizeof(*event), 0);
    if (!event) {
        increment_dropped();
        return;
    }

    event->abi_version = AKIHA_ABI_VERSION;
    event->event_type = type;
    event->state = __builtin_preserve_access_index(skops->state);
    event->sequence = next_sequence();
    event->monotonic_ns = now;
    event->socket_cookie = cookie;
    event->handshake_us =
        flow && flow->connect_ns && now > flow->connect_ns
            ? (__u32)((now - flow->connect_ns) / 1000)
            : 0;
    event->srtt_us = __builtin_preserve_access_index(skops->srtt_us) >> 3;
    event->snd_cwnd = __builtin_preserve_access_index(skops->snd_cwnd);
    event->total_retrans = __builtin_preserve_access_index(skops->total_retrans);
    event->segs_out = __builtin_preserve_access_index(skops->segs_out);
    event->reserved = 0;
    event->delivery_rate =
        (__u64)__builtin_preserve_access_index(skops->rate_delivered) * 1000000ULL /
        (__u64)(__builtin_preserve_access_index(skops->rate_interval_us) ?: 1);
    bpf_ringbuf_submit(event, 0);
}

SEC("sockops")
int akihalink_sockops(struct bpf_sock_ops *skops)
{
    __u64 cookie = bpf_get_socket_cookie(skops);
    __u64 now = bpf_ktime_get_ns();
    struct flow_state initial = {};
    struct flow_state *flow = bpf_map_lookup_elem(&flows, &cookie);

    if (skops->op == BPF_SOCK_OPS_TCP_CONNECT_CB) {
        __u32 key = 0;
        __u32 *target = bpf_map_lookup_elem(&target_tgid, &key);
        __u32 current = (__u32)(bpf_get_current_pid_tgid() >> 32);
        if (!target || *target == 0 || current != *target)
            return 0;
        initial.connect_ns = now;
        initial.last_emit_ns = now;
        bpf_map_update_elem(&flows, &cookie, &initial, BPF_ANY);
        flow = bpf_map_lookup_elem(&flows, &cookie);
        bpf_sock_ops_cb_flags_set(skops,
            BPF_SOCK_OPS_STATE_CB_FLAG |
            BPF_SOCK_OPS_RTT_CB_FLAG |
            BPF_SOCK_OPS_RETRANS_CB_FLAG);
        emit_event(skops, flow, AKIHA_EVENT_CONNECT, cookie, now);
        return 0;
    }

    if (!flow)
        return 0;

    if (skops->op == BPF_SOCK_OPS_ACTIVE_ESTABLISHED_CB) {
        emit_event(skops, flow, AKIHA_EVENT_ESTABLISHED, cookie, now);
    } else if (skops->op == BPF_SOCK_OPS_RETRANS_CB) {
        emit_event(skops, flow, AKIHA_EVENT_RETRANS, cookie, now);
    } else if (skops->op == BPF_SOCK_OPS_STATE_CB) {
        emit_event(skops, flow, AKIHA_EVENT_STATE, cookie, now);
        if (skops->args[1] == BPF_TCP_CLOSE)
            bpf_map_delete_elem(&flows, &cookie);
    } else if (skops->op == BPF_SOCK_OPS_RTT_CB &&
               flow && now - flow->last_emit_ns >= AKIHA_EVENT_INTERVAL_NS) {
        flow->last_emit_ns = now;
        emit_event(skops, flow, AKIHA_EVENT_METRICS, cookie, now);
    }
    return 0;
}

char LICENSE[] SEC("license") = "GPL";
