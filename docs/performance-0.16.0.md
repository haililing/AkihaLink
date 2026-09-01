# AkihaLink 0.16.0 performance evidence

## Host-verifiable results

The Android arm64 core built with Go 1.26.5, NDK r29, commit
`5133ef62c9843577c6c8dd1ac6bada33f1ade01c`, and patch set
`akihalink-upstream-ebpf-v9` is 23,972,872 bytes. The pre-change 0.15.0 core in
the same workspace is 44,744,008 bytes, a 46.42% reduction. Both are stripped
release-style binaries. This exceeds the 10% size gate but is not a throughput
claim.

The following host checks are part of the implementation evidence:

- the Android arm64 core compiles with the minimal registry and
  `grpcnotrace`, without `with_gvisor`, `with_dhcp`, or `with_provider`;
- Linux OOB batch and observability test binaries cross-compile;
- App unit tests, module controller fixtures, and the repository test server
  compile independently;
- local, CI, and release core builds all use Go's reproducible `-pgo=off` path.

## Device evidence required before release

No Android device was attached while this implementation was prepared, so no
cross-ROM or throughput result is claimed here. Before publication, use
`scripts/benchmark-dataplane.ps1` in alternating 0.15.0/0.16.0 order for TCP,
Hysteria2, and TUIC selections, then run `scripts/evaluate-dataplane.ps1`.
Complete the 24-hour `scripts/soak-dataplane.ps1` leak gate as well.

The evaluator enforces seven 30-second samples per transport/workload, a 10%
core-size reduction, no throughput regression over 3%, no first-connect p95
regression over 5%, no UDP loss increase over 0.1 percentage point, and at
least one 5% throughput or 8% CPU/bit improvement.
