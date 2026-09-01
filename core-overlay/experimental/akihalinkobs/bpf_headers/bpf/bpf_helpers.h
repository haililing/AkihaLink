/* Minimal libbpf-compatible helper declarations for the embedded CO-RE object. */
#ifndef __AKIHALINK_BPF_HELPERS_H
#define __AKIHALINK_BPF_HELPERS_H

#define SEC(name) __attribute__((section(name), used))
#define __uint(name, val) int (*name)[val]
#define __type(name, val) val *name
#ifndef __always_inline
#define __always_inline inline __attribute__((always_inline))
#endif

static void *(*bpf_map_lookup_elem)(void *map, const void *key) =
    (void *)BPF_FUNC_map_lookup_elem;
static long (*bpf_map_update_elem)(void *map, const void *key, const void *value,
                                   unsigned long long flags) =
    (void *)BPF_FUNC_map_update_elem;
static long (*bpf_map_delete_elem)(void *map, const void *key) =
    (void *)BPF_FUNC_map_delete_elem;
static unsigned long long (*bpf_ktime_get_ns)(void) =
    (void *)BPF_FUNC_ktime_get_ns;
static unsigned long long (*bpf_get_current_pid_tgid)(void) =
    (void *)BPF_FUNC_get_current_pid_tgid;
static unsigned long long (*bpf_get_socket_cookie)(void *ctx) =
    (void *)BPF_FUNC_get_socket_cookie;
static void *(*bpf_ringbuf_reserve)(void *ringbuf, unsigned long long size,
                                    unsigned long long flags) =
    (void *)BPF_FUNC_ringbuf_reserve;
static void (*bpf_ringbuf_submit)(void *data, unsigned long long flags) =
    (void *)BPF_FUNC_ringbuf_submit;
static long (*bpf_sock_ops_cb_flags_set)(void *ctx, int flags) =
    (void *)BPF_FUNC_sock_ops_cb_flags_set;

#endif
