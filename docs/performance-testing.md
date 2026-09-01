# Performance testing

The benchmark build never talks to
KernelSU, sing-box, or the network while running the benchmark build; that
variant injects an in-process fake root/module backend and a 500-node database.

## Profiles

Generate the Baseline and Startup Profile rules with the managed Android 16
device, then review and replace
`app/src/main/baselineProfiles/baseline-prof.txt` and
`app/src/main/baselineProfiles/startup-prof.txt`:

```powershell
.\gradlew.bat :baselineprofile:pixel6Api36BenchmarkAndroidTest
.\scripts\update-baseline-profiles.ps1 `
  -GeneratedOutput .\baselineprofile\build\outputs
```

Device execution is deliberately separate from a normal release build. AGP 9
consumes the committed ART profiles and performs R8 rewriting and
Startup Profile DEX layout; the release task does not boot an emulator.

AndroidX Baseline Profile Gradle plugin 1.4.1 does not recognize AGP 9
application modules. The project therefore uses its 1.4.1 generation API
directly and the AGP 9 ART Profile pipeline instead of applying that plugin to
`:app`. Macrobenchmark, BaselineProfileRule and ProfileInstaller remain pinned
to 1.4.1.

## Macrobenchmarks

```powershell
.\gradlew.bat :baselineprofile:pixel6Api36BenchmarkAndroidTest
```

The suite reports cold startup with no compilation, full compilation and the
Baseline Profile, plus first node load, scrolling, app-side connect, node
switch, and configuration generation. Results and `.perfetto-trace` files are
written by the Android benchmark runner. Emulator numbers are reference data,
not release thresholds.

## Cross-process Perfetto trace

The capture helper is opt-in and requires an explicitly selected device:

```powershell
.\scripts\capture-perfetto.ps1 -Serial SERIAL
```

It uses `scripts/perfetto/akihalink.pbtx`, which records ATrace, scheduling,
frequency/idle, Binder, UI and selected network ftrace events for 30 seconds.
Use `trace_processor_shell -q scripts/perfetto/validate-trace.sql TRACE` to list
the `AKL/*` slices, report each anonymous trace ID's stage durations, and emit
stage-level `p50_ns`/`p95_ns` values for repeated-run comparisons. The final
query is a privacy check.

Every connection has a random 16-character hexadecimal ID. Only that ID and
fixed stage names are emitted. Node names, hosts, addresses, ports,
configuration JSON and raw errors are excluded.
