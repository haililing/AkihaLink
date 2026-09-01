# AkihaLink 1.1 release evidence

## Host-verifiable results

The Android arm64 core is built from
`90bb3d43634b56b38834a239f3129d3009ece9d4` with
`akihalink-upstream-ebpf-v16`, Go 1.26.6, and NDK r29. The build verifies the
version string, pure-Go cilium eBPF packages, bpf2go little- and big-endian
cgroup/shared/splice objects, ELF/EM_BPF headers, sections, Map ABI, manifest
hashes, and a second byte-identical generation pass.

Host compilation and unit tests are necessary but do not establish Android
kernel verifier, vendor SELinux, radio, hotspot, performance, or endurance
results.

## Required device and reproducibility evidence

Before publishing 1.1, complete `docs/device-acceptance.md` on Android 16
arm64 across KernelSU, KernelSU-Next, SukiSU Ultra, and ReSukiSU. Use v14 as
the paired data-plane baseline and reject the candidate if throughput regresses
more than 3%, first-connect p95 more than 5%, or UDP loss more than 0.1
percentage point. The existing project core-size and CPU/bit gate remains in
force.

Run the final candidate for 24 hours and reject unbounded Map, FD, memory, or
goroutine growth, DNS leakage, unexpected core exits, or third-party TC state
damage. Build twice locally and twice in the newly published immutable Go
1.26.6 toolchain image; APK, module ZIP, core, and source archive must match
byte for byte. Record the new image digest and device matrix attestation before
enabling the release workflow.
