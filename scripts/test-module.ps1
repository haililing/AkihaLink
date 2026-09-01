param(
    [string]$Core = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$module = Join-Path $root "module"
$bash = if ($IsWindows -or $env:OS -eq "Windows_NT") {
    "C:\Program Files\Git\bin\bash.exe"
} else {
    (Get-Command bash -ErrorAction Stop).Source
}
if (-not (Test-Path $bash)) { throw "Git Bash is required for shell syntax checks" }

$packager = Get-Content (Join-Path $root "scripts/package-module.ps1") -Raw
if ($packager -match '\bCompress-Archive\b') {
    throw "Compress-Archive emits Windows path separators that Android module installers cannot extract"
}
if (-not $packager.Contains('Module ZIP contains Windows path separators')) {
    throw "Module packager does not validate ZIP entry paths"
}
if ($packager -notmatch 'tools/repropack/main\.go' -or
    $packager -notmatch 'SourceDateEpoch' -or
    $packager -notmatch 'go1\\\.26\\\.6') {
    throw "Generic module packager is not deterministic"
}
$apkNormalizer = Get-Content (Join-Path $root "scripts/normalize-apk.ps1") -Raw
if ($apkNormalizer -notmatch 'build/toolchains/go1\.26\.6/bin/go\.exe' -or
    $apkNormalizer -notmatch "go1\\\.26\\\.6" -or
    $apkNormalizer -match '& go run') {
    throw "APK normalizer does not use the locked Go 1.26.6 toolchain"
}
$artifactBuilder = Get-Content (Join-Path $root "scripts/build-artifacts.ps1") -Raw
$benchmarkRunner = Get-Content (Join-Path $root "scripts/benchmark-dataplane.ps1") -Raw
if ($artifactBuilder -notmatch '\[string\]\$Version = "1\.1"' -or
    $artifactBuilder -match 'AkihaLink-(KSU-)?1\.0' -or
    $benchmarkRunner -notmatch 'ValidateSet\("0\.15\.0", "0\.16\.0", "1\.1"\)') {
    throw "Artifact and benchmark scripts are not aligned with AkihaLink 1.1"
}

$scripts = @(
    "customize.sh", "service.sh", "action.sh", "uninstall.sh",
    "bin/supervisor.sh", "bin/akihalinkctl", "bin/kernel-capabilities.sh"
) | ForEach-Object { Join-Path $module $_ }
$fixtureScripts = @(
    (Join-Path $root "scripts/fixtures/kernel-capabilities-fixture.sh"),
    (Join-Path $root "scripts/test-main-control.sh"),
    (Join-Path $root "scripts/test-speedtest-control.sh"),
    (Join-Path $root "scripts/ci-network-path-test.sh"),
    (Join-Path $root "scripts/test-trace-control.sh")
)
& $bash -n @scripts @fixtureScripts
if ($LASTEXITCODE -ne 0) { throw "Module shell syntax check failed" }

$installer = Get-Content (Join-Path $module "customize.sh") -Raw
if ($installer -notmatch 'INSTALLED_CONTROL=.*/akihalinkctl' -or
    $installer -notmatch '"\$INSTALLED_CONTROL" stop' -or
    $installer -notmatch '"\$INSTALLED_CONTROL" cleanup-stale' -or
    $installer -notmatch 'PREVIOUS_DESIRED' -or
    $installer -notmatch 'uid-policy\.sock') {
    throw "Module upgrade does not stop the old core, clean stale eBPF state, and preserve desired state"
}

$forbidden = '(?i)(^|[;&|]\s*)(iptables|ip6tables|nft|tc|sysctl|setenforce)\s'
$forbiddenProcWrite = '(?i)(>|\btee\s+)[^\r\n]*/proc/sys/'
foreach ($script in $scripts) {
    $matches = Select-String -Path $script -Pattern $forbidden
    if ($matches) { throw "Forbidden networking or SELinux command in $script" }
    $procWrites = Select-String -Path $script -Pattern $forbiddenProcWrite
    if ($procWrites) { throw "Forbidden global /proc/sys write in $script" }
}

$controller = Get-Content (Join-Path $module "bin/akihalinkctl") -Raw
foreach ($command in @(
    "version", "status", "status-fast", "status-health", "probe", "probe-hotspot", "activate", "apply", "start", "stop", "restart",
    "speedtest-start", "speedtest-status", "speedtest-stop",
    "telemetry-status", "telemetry-clear", "network-events",
    "observability-snapshot", "observability-sampling", "path-status",
    "diagnostics", "uid-policy-status", "apply-exclusions", "cleanup"
)) {
    if ($controller -notmatch [regex]::Escape($command)) { throw "Missing controller command: $command" }
}
if ($controller -notmatch '"type": "ebpf"') { throw "Probe config does not use the eBPF inbound" }
if ($controller -notmatch '90bb3d43634b56b38834a239f3129d3009ece9d4') { throw "Core commit marker is missing" }
if ($controller -notmatch 'akihalink-upstream-ebpf-v16') { throw "Core patch-set marker is missing" }
if ($controller -notmatch '(?m)^PROTOCOL=16$' -or $controller -notmatch '(?m)^MODULE_VERSION=1\.1$') {
    throw "Controller version markers are stale"
}
$probeConfigWriter = [regex]::Match($controller, '(?s)write_probe_config\(\) \{(.*?)\r?\n\}').Groups[1].Value
if ($probeConfigWriter -notmatch '"type": "udp"' -or
    $probeConfigWriter -notmatch '"tag": "probe-dns"' -or
    $probeConfigWriter -notmatch '"server": "127\.0\.0\.1"' -or
    $probeConfigWriter -notmatch '"final": "probe-dns"' -or
    $probeConfigWriter -match '"type": "local"') {
    throw "eBPF probe does not define a minimal-core-compatible DNS fallback"
}
$hotspotProbeWriter = [regex]::Match($controller, '(?s)write_hotspot_probe_config\(\) \{(.*?)\r?\n\}').Groups[1].Value
if ($hotspotProbeWriter -notmatch '"mode": "shared"' -or
    $hotspotProbeWriter -notmatch '"shared"' -or
    $hotspotProbeWriter -notmatch '\$probe_interface' -or
    $hotspotProbeWriter -notmatch '"tc_priority": 1' -or
    $hotspotProbeWriter -notmatch '"data_plane": "rewrite"' -or
    $hotspotProbeWriter -notmatch '"type": "udp"' -or
    $hotspotProbeWriter -notmatch '"tag": "hotspot-probe-dns"' -or
    $hotspotProbeWriter -notmatch '"server": "127\.0\.0\.1"' -or
    $hotspotProbeWriter -notmatch '"final": "hotspot-probe-dns"' -or
    $hotspotProbeWriter -match '"type": "local"' -or
    $controller -notmatch 'hotspotProxyState' -or
    $controller -notmatch 'waiting\|attached\|failed') {
    throw "Safe hotspot capability probe or bounded hotspot status is missing"
}
if ($controller -notmatch 'cleanup_owned_shared_network' -or
    $controller -notmatch 'cleanup-shared-network' -or
    $controller -notmatch 'HOTSPOT_PROBE_CACHE') {
    throw "Hotspot TC cleanup or per-boot capability caching is missing"
}
if ($controller -match '(?i)device[_-]observer|stable_session|network_history') {
    throw "Removed device-specific control surface is still present"
}
if ($controller -notmatch 'akihalink-daemon process status' -or
    $controller -notmatch 'akihalink-daemon process signal') {
    throw "Controller does not validate PID ownership through the pidfd helper"
}
$stopProcesses = [regex]::Match($controller, '(?s)stop_processes\(\) \{(.*?)\n\}').Groups[1].Value
if ($stopProcesses -notmatch 'stop_observability true') {
    throw "Controller does not stop the combined pidfd supervisor"
}
$applyConfig = [regex]::Match($controller, '(?s)apply_config\(\) \{(.*?)\n\}').Groups[1].Value
if ($applyConfig -notmatch 'core_alive "\$\(core_pid\)"' -or
    $applyConfig -notmatch 'supervisor_alive "\$\(supervisor_pid\)"') {
    throw "Config apply does not account for live processes when desired state is stale"
}
if ($controller -notmatch 'compare-config-except-uid' -or
    $controller -match 'ebpf_redirect_spec|same_ebpf_redirect|preserve_redirect') {
    throw "Config comparison or restart lifecycle still assumes persistent v14 redirect pins"
}
if ($applyConfig -notmatch '(?s)else\s+# A stopped selector.*?rm -f "\$DATA/cache\.db"') {
    throw "Stopped config apply does not invalidate a stale selector cache"
}
if ($applyConfig -notmatch '127\\\.0\\\.0\\\.1:9090' -or
    $applyConfig -notmatch 'Main controller must listen') {
    throw "Main config validation does not require the private Clash controller"
}
if ($controller -notmatch 'CONTROL_LOCK="\$RUNTIME/control\.lock"' -or
    $controller -notmatch 'control_lock_owner_alive' -or
    $controller -notmatch 'acquire_control_lock' -or
    $controller -notmatch 'atomic_copy "\$CONFIG_DIR/current\.json" "\$CONFIG_DIR/previous\.json"') {
    throw "Controller mutation locking or atomic config backup is missing"
}
$speedTestStart = [regex]::Match($controller, '(?s)start_speedtest\(\) \{(.*?)\n\}').Groups[1].Value
if ($speedTestStart -notmatch 'must not contain inbounds' -or
    $speedTestStart -notmatch '127\\\.0\\\.0\\\.1:19090' -or
    $speedTestStart -notmatch 'speedtest\.candidate' -or
    $speedTestStart -notmatch '"\$CORE" check') {
    throw "Auxiliary speed test config validation is incomplete"
}
if ($controller -notmatch 'speedtest_api_ready' -or
    $controller -notmatch '/net/tcp6' -or
    $controller -notmatch '\$4 == "0A"') {
    throw "Auxiliary speed test readiness still depends on info-level logs"
}
if ($controller -notmatch 'while \[ "\$count" -lt 2 \]' -or
    $controller -notmatch 'if ! speedtest_alive "\$pid"; then') {
    throw "Auxiliary speed test does not defer final readiness to the local API"
}
if ($speedTestStart -notmatch 'set_speedtest_readiness ready' -or
    $speedTestStart -notmatch 'set_speedtest_readiness unverified' -or
    $controller -notmatch 'speedtest_status_json failed') {
    throw "Auxiliary speed test does not expose ready, unverified, and failed states"
}
$stopSpeedTest = [regex]::Match($controller, '(?s)stop_speedtest\(\) \{(.*?)\n\}').Groups[1].Value
if ($stopSpeedTest -notmatch '(?s)process signal.*? 15' -or
    $stopSpeedTest -notmatch '(?s)process signal.*? 9' -or
    $stopSpeedTest -notmatch 'Speed test process survived TERM and KILL' -or
    $stopSpeedTest -notmatch 'tcp_listener_ready') {
    throw "Auxiliary speed test stop does not verify forced cleanup"
}
$startCore = [regex]::Match($controller, '(?s)start_core\(\) \{(.*?)\n\}').Groups[1].Value
$statusJson = [regex]::Match($controller, '(?ms)^status_json\(\) \{(.*?)^\}').Groups[1].Value
$coreReady = [regex]::Match($controller, '(?s)core_ready\(\) \{(.*?)\n\}').Groups[1].Value
if ($controller -notmatch '(?s)status-fast\)\s*status_json true' -or
    $statusJson -notmatch 'fast_mode=\$\{1:-false\}' -or
    $statusJson -notmatch '(?s)if \[ "\$fast_mode" = true \].*?else.*?observability_runtime_status_json') {
    throw "Fast status does not skip the observability snapshot"
}
if ($controller -notmatch '(?s)status-health\)\s*status_json true false true' -or
    $statusJson -notmatch 'controller_ready' -or
    $statusJson -notmatch 'main_api_ready') {
    throw "Controller health status does not report local API readiness"
}
if ($controller -notmatch '(?m)^MAIN_API_PORT=9090$' -or
    $controller -notmatch 'tcp_listener_ready' -or
    $controller -notmatch 'main_api_ready' -or
    $controller -notmatch 'system_resolver_ready' -or
    $controller -notmatch 'core_ready') {
    throw "Main core readiness does not verify the private local controller"
}
if ($controller -notmatch 'startup_readiness_value' -or
    $controller -notmatch 'controller_authenticated') {
    throw "Main core readiness does not require the authenticated startup record"
}
if ($controller -notmatch 'system_resolver_attached' -or
    $controller -notmatch 'service call dnsresolver 11 i32' -or
    $controller -notmatch 'active_dns_netids') {
    throw "Android DNS cache recovery status is incomplete"
}
if ($startCore -match 'eBPF inbound attached' -or
    $statusJson -match 'eBPF inbound attached') {
    throw "Main core readiness still depends on an info-level log message"
}
if ($startCore -notmatch 'ready_samples' -or
    $startCore -notmatch '\$STARTUP_WAIT_SECONDS' -or
    $startCore -notmatch 'Core startup health timed out' -or
    $controller -notmatch 'stop_failed_start') {
    throw "Main core readiness is not stable across consecutive samples or failure cleanup"
}
if ($controller -notmatch 'activate_config' -or
    $controller -notmatch 'ACTIVATION_TRANSACTION' -or
    $controller -notmatch 'recover_interrupted_activation' -or
    $controller -notmatch 'Activation configuration violates') {
    throw "Atomic activation transaction and recovery are missing"
}
if ($statusJson -notmatch 'core_ready_from_marker' -or
    $statusJson -match 'core_alive "\$pid"' -or
    $statusJson -match 'system_resolver_ready') {
    throw "Fast status still performs startup-time process validation"
}
if ($controller -notmatch 'STARTUP_WAIT_SECONDS=\$\{AKIHALINK_STARTUP_WAIT_SECONDS:-8\}') {
    throw "Main core startup wait is not capped at eight seconds"
}
$stopObservability = [regex]::Match($controller, '(?s)stop_observability\(\) \{(.*?)\n\}').Groups[1].Value
if (($stopObservability | Select-String -Pattern 'count" -lt 5' -AllMatches).Matches.Count -lt 2 -or
    $stopObservability -notmatch 'timeout 1s' -or
    $stopObservability -notmatch '(?s)process signal.*? 9') {
    throw "Main core stop does not use the bounded graceful and forced shutdown sequence"
}
$probe = [regex]::Match($controller, '(?s)probe\)\s*(.*?)\n\s*apply\)').Groups[1].Value
if ($probe -notmatch 'core_ready' -or
    $probe -notmatch 'core_alive.*?supervisor_alive' -or
    $probe -notmatch 'cleanup_owned_redirect' -or
    $controller -notmatch '(?s)boot\).*?stop_failed_start' -or
    $controller -notmatch '"dns_mode": "hijack"') {
    throw "Probe ownership cleanup or boot failure cleanup is incomplete"
}
$diagnostics = [regex]::Match($controller, '(?s)diagnostics\)(.*?)\n\s*;;').Groups[1].Value
if ($diagnostics -notmatch '\[ -r "\$LOGDIR/core\.log" \]' -or
	$diagnostics -notmatch '"telemetry":\{"detail":"aggregate_only"\}' -or
	$diagnostics -notmatch '"networkEvents":\[\]' -or
    $controller -notmatch [regex]::Escape("printf '\033'")) {
    throw "Diagnostics can still mix shell or ANSI errors into JSON"
}
if ($diagnostics -notmatch 'status_json true true') {
    throw "Diagnostics still performs a second slow runtime-status request"
}
if ($controller -notmatch 'SPEEDTEST_TIMEOUT_SECONDS=1800' -or
    $controller -notmatch 'auxiliary --timeout "\$\{SPEEDTEST_TIMEOUT_SECONDS\}s"') {
    throw "Auxiliary speed test timeout is missing"
}
if ($controller -match 'qualitytest|networkquality|ordered_failover|network_quality_test') {
    throw "Removed failover or capacity-test control surface is still present"
}
foreach ($feature in @(
    "ebpf_core_relocation", "upstream_cgroup_ebpf",
    "dns_startup_health", "android_dns_cache_flush", "flow_telemetry", "rtnetlink_observer",
    "passive_path_observation",
    "pidfd_supervision", "perfetto_trace_markers", "atomic_config_activation", "authenticated_startup_gate",
    "wifi_hotspot_proxy", "cilium_ebpf_backend", "ebpf_kernel_probe"
)) {
    if ($controller -notmatch [regex]::Escape($feature)) {
        throw "Generic feature set is missing $feature"
    }
}
if ($controller -notmatch 'detected_core_patch_set' -or
    $controller -notmatch '"\$CORE" version' -or
    $controller -notmatch 'actual_core_patch_set="\$\(detected_core_patch_set\)"') {
    throw "Controller version metadata does not verify the installed core binary"
}
foreach ($traceStage in @(
    "AKL/config_check", "AKL/config_commit", "AKL/supervisor_wake", "AKL/core_ready"
)) {
    if ($controller -notmatch [regex]::Escape($traceStage)) {
        throw "Controller trace markers are missing $traceStage"
    }
}
if ($controller -notmatch '\^\[0-9a-f\]\{16\}\$' -or
    $controller -notmatch 'TRACE_CONTEXT="\$RUNTIME/trace-context"' -or
    $controller -notmatch '(?s)cleanup\).*?clear_trace_context') {
    throw "Trace ID validation or runtime context cleanup is incomplete"
}
if ($controller -match 'scoring-(status|observe|clear)|scoring-file|adaptiveScores|contentQueueDropped' -or
    $controller -match 'adaptive_node_scoring|content_path_metrics|adaptive_http3_policy') {
    throw "Removed scoring, content-quality, or HTTP/3 policy surface is still present"
}
if ($controller -notmatch 'observability-target.*fingerprint' -and
    $controller -notmatch 'observability_client target "\$2" "\$3"') {
    throw "Observability target update is missing"
}
if ($controller -match 'speedtest\.watchdog|AKIHALINK_SPEEDTEST_WATCHDOG') {
    throw "Removed shell speed-test watchdog logic has returned"
}
if ($controller -match 'happyEyeballs|happyEyeballsMode|rfc8305_fallback') {
    throw "Removed custom Happy Eyeballs status fields have returned"
}

$supervisor = Get-Content (Join-Path $module "bin/supervisor.sh") -Raw
if ($supervisor -notmatch '(?s)exec "\$CORE" akihalink-daemon.*?supervise' -or
    $supervisor -match 'observability-ready') {
    throw "Supervisor is not owned by the combined pidfd daemon"
}

$application = Get-Content (Join-Path $root "app/src/main/java/com/akiha/akihalink/AkihaLinkApplication.kt") -Raw
$shellTimeoutMatch = [regex]::Match($application, '\.setTimeout\((\d+)\)')
if (-not $shellTimeoutMatch.Success -or [int]$shellTimeoutMatch.Groups[1].Value -lt 90) {
    throw "Root shell timeout is shorter than the bounded module cleanup lifecycle"
}

$lock = Get-Content (Join-Path $root "patches/sing-box/patches.lock.json") -Raw | ConvertFrom-Json
$submoduleCommit = (& git -C (Join-Path $root "third_party/sing-box") rev-parse HEAD).Trim()
if ($submoduleCommit -ne $lock.baseCommit) { throw "Pinned sing-box commit does not match the patch lock" }
if ([string]::IsNullOrWhiteSpace([string]$lock.baseVersion)) {
    throw "Pinned sing-box version is missing from the patch lock"
}
if ($lock.baseVersion -ne "v1.14.0-rc.1-9-g90bb3d43") {
    throw "Pinned sing-box version is stale"
}
$coreBuildScript = Get-Content (Join-Path $root "scripts/build-core.ps1") -Raw
$coreArtifactVerifier = Get-Content (Join-Path $root "scripts/verify-core-artifact.ps1") -Raw
$modulePackager = Get-Content (Join-Path $root "scripts/package-module.ps1") -Raw
$toolchainLock = Get-Content (Join-Path $root "release/toolchain.lock.json") -Raw | ConvertFrom-Json
if ($toolchainLock.go.version -ne "1.26.6" -or
    $toolchainLock.go.'windows-amd64'.sha256 -ne "5b6c5b556525810463b5c897b50dc7a82d6a3dc0bfaf55d990a7e9f31d6b2318" -or
    $toolchainLock.go.'linux-amd64'.sha256 -ne "708effb774be8237570d0add163225abbdfaf4fca28b2611df167beba4feef89" -or
    $toolchainLock.android.ndk -ne "29.0.14206865" -or
    $toolchainLock.ciliumEbpf -ne "v0.22.1-0.20260724091036-00feb08ae4e5") {
    throw "Immutable release toolchain lock is stale"
}
if ($coreBuildScript -match 'git\s+-C\s+\$upstreamSource\s+describe' -or
    $coreBuildScript -notmatch '\$baseVersion\s*=\s*\[string\]\$lock\.baseVersion') {
    throw "Core version must come from the immutable patch lock"
}
$configCheckMod = Get-Content (Join-Path $root "tools/configcheck/go.mod") -Raw
if ($configCheckMod -match 'github\.com/reF1nd/' -or
    $configCheckMod -notmatch 'replace\s+github\.com/sagernet/sing-box\s+=>\s+\.\./\.\./build/core-source') {
    throw "Configuration validation does not use the prepared pinned core dependency graph"
}
if ($coreBuildScript -notmatch 'verify-core-artifact\.ps1' -or
    $modulePackager -notmatch 'verify-core-artifact\.ps1' -or
    $coreArtifactVerifier -notmatch 'akihalink-fallback' -or
    $coreArtifactVerifier -notmatch '\$genericLock\.patchSet') {
    throw "Core build and packaging do not reject stale or capability-mismatched artifacts"
}
if ($coreBuildScript -notmatch '-pgo=off' -or
    $coreBuildScript -match 'PgoProfile|RequirePgo|AllowUnverifiedPgoForBenchmark|profiles/core') {
    throw "Core build must use the reproducible unprofiled configuration"
}
foreach ($removedPgoPath in @(
    "profiles/core",
    "scripts/benchmark-core-pgo.ps1",
    "scripts/capture-core-pgo.ps1",
    "scripts/evaluate-core-pgo.ps1",
    "scripts/run-pgo-workloads.ps1",
    "scripts/test-pgo-gate.ps1",
    "scripts/verify-core-pgo.ps1"
)) {
    if (Test-Path (Join-Path $root $removedPgoPath)) {
        throw "Removed core PGO artifact remains: $removedPgoPath"
    }
}
if ($coreBuildScript -notmatch 'Go 1\.26\.6' -or
    $coreBuildScript -notmatch '29\.0\.14206865' -or
    $coreBuildScript -notmatch 'aarch64-linux-android35-clang' -or
    $coreBuildScript -notmatch 'Stem = "cgroup"' -or
    $coreBuildScript -notmatch 'Stem = "shared_network"' -or
    $coreBuildScript -notmatch 'Stem = "splice"' -or
    $coreBuildScript -notmatch 'bpfel,bpfeb' -or
    $coreBuildScript -match 'with_connection_history') {
    throw "Core build is not pinned to Go 1.26.6/NDK r29 and all bpf2go objects"
}
foreach ($removedTag in @("with_gvisor", "with_dhcp", "with_provider")) {
    if ($coreBuildScript -match [regex]::Escape($removedTag)) {
        throw "Unused core build tag remains: $removedTag"
    }
}
foreach ($requiredTag in @("grpcnotrace", "with_akihalink_minimal_registry")) {
    if ($coreBuildScript -notmatch [regex]::Escape($requiredTag)) {
        throw "Required minimal-core build tag is missing: $requiredTag"
    }
}
$observabilityBpf = Get-Content (Join-Path $root "core-overlay/experimental/akihalinkobs/akihalink_bpf.c") -Raw
if ($observabilityBpf -match 'return\s+__sync_fetch_and_add') {
    throw "CO-RE observability still consumes the unsupported BPF XADD return value"
}
foreach ($entry in $lock.patches) {
    $patch = Join-Path $root "patches/sing-box/$($entry.file)"
    $hash = (Get-FileHash -Algorithm SHA256 $patch).Hash.ToLowerInvariant()
    if ($hash -ne $entry.sha256) { throw "Patch checksum mismatch: $($entry.file)" }
    & git -C (Join-Path $root "third_party/sing-box") apply --check $patch
    if ($LASTEXITCODE -ne 0) { throw "Patch does not apply to the pinned core: $($entry.file)" }
}
$genericLock = Get-Content (Join-Path $root "patches/sing-box/generic-patches.lock.json") -Raw | ConvertFrom-Json
foreach ($entry in $genericLock.patches) {
    $patch = Join-Path $root "patches/sing-box/$($entry.file)"
    $hash = (Get-FileHash -Algorithm SHA256 $patch).Hash.ToLowerInvariant()
    if ($hash -ne $entry.sha256) { throw "Generic patch checksum mismatch: $($entry.file)" }
}
$ciliumPatch = Get-Content (Join-Path $root "patches/sing-box/0015-akihalink-cilium-backend.patch") -Raw
foreach ($requiredMigration in @(
    'maxUIDPolicyEntries != 4096',
    'ReplaceExcludeUIDPolicy',
    'rollbackUIDPolicyMap',
    'health\.invalidate',
    'UIDPolicySnapshot',
    'cgroup_system_resolver_tgid',
    'AndroidSystemResolverStatus',
    'MarkReady',
    'android_tethering',
    'OOBPacketBatchHandler',
    'WritePacketBatch',
    'with_akihalink_minimal_registry'
)) {
    if ($ciliumPatch -notmatch $requiredMigration) {
        throw "Cilium backend migration is missing: $requiredMigration"
    }
}
$dnsPrewarmPatch = Get-Content (Join-Path $root "patches/sing-box/0007-akihalink-dns-prewarm.patch") -Raw
if ($dnsPrewarmPatch -notmatch 'go func\(\)' -or
    $dnsPrewarmPatch -notmatch 'proxy DNS startup health check deferred' -or
    $dnsPrewarmPatch -match 'return E\.Cause\(err, "run proxy DNS startup health check"\)') {
    throw "Proxy DNS prewarm must be diagnostic-only during startup"
}
if ($ciliumPatch -notmatch 'verified root netd process was not found' -or
    $ciliumPatch -notmatch 'system_resolver_selected') {
    throw "Android system resolver attachment is not a startup gate"
}
if ($ciliumPatch -notmatch 'startUIDPolicyControl\(\)' -or
	$ciliumPatch -notmatch 'func \(b \*CgroupBackend\) MarkReady' -or
    $ciliumPatch -notmatch 'removeReadinessMarker') {
	throw "Core readiness is no longer gated by the UID policy control socket"
}
if (Test-Path (Join-Path $root "core-overlay/common/ebpf/readiness_cgo.go")) {
    throw "Obsolete CGO readiness backend remains in the overlay"
}
if ($genericLock.patchSet -ne "akihalink-upstream-ebpf-v16" -or
    $genericLock.dependencies.'github.com/cilium/ebpf' -ne 'v0.22.1-0.20260724091036-00feb08ae4e5' -or
    $genericLock.patches.file -notcontains "0015-akihalink-cilium-backend.patch" -or
    $genericLock.patches.file -contains "0003-akihalink-adaptive-transport.patch" -or
    $genericLock.patches.file -contains "0005-akihalink-adaptive-http3-selector.patch" -or
    $genericLock.patches.file -contains "0006-akihalink-content-metrics.patch") {
    throw "Generic core lock still enables a removed adaptive feature"
}
if ($ciliumPatch -notmatch 'exec\.Command\("dumpsys", service\)' -or
    $ciliumPatch -notmatch 'androidTetheringDiscoveryTimeout.*2 \* time.Second' -or
    $ciliumPatch -notmatch 'androidTetheringInterfaceResolver' -or
    $ciliumPatch -notmatch 'disable shared-network after reconciliation failure') {
    throw "Locked Android Wi-Fi hotspot discovery or TC ownership patch is incomplete"
}
$sharedCleanup = Get-Content (Join-Path $root "core-overlay/experimental/akihalinkobs/shared_network_cleanup.go") -Raw
if ($sharedCleanup -notmatch 'LinkByName\(record.InterfaceName\)' -or
    $sharedCleanup -notmatch 'Index != record.InterfaceIndex' -or
    $sharedCleanup -notmatch 'sb_share_in' -or
    $sharedCleanup -notmatch 'sb_share_out' -or
    $sharedCleanup -notmatch 'len\(filters\) != 0' -or
    $sharedCleanup -notmatch 'RouteLocalnetOriginal == 0') {
    throw "Crash-safe shared-network cleanup does not preserve third-party TC state"
}
$supervisorSource = Get-Content (Join-Path $root "core-overlay/experimental/akihalinkobs/supervisor.go") -Raw
if ($supervisorSource -notmatch 'exitCode, err, ready := supervisor.runCore\(state\).*CleanupSharedNetwork\(supervisor.options.RuntimeDir\)' -and
    $supervisorSource -notmatch '(?s)exitCode, err, ready := supervisor.runCore\(state\).*?CleanupSharedNetwork\(supervisor.options.RuntimeDir\)') {
    throw "Core crash recovery does not clean owned hotspot TC state before restart"
}
if ($lock.patches.Count -ne 0 -or (Test-Path (Join-Path $root "patches/sing-box/0001-akihalink-tcp-congestion-control.patch"))) {
    throw "Custom TCP congestion-control patch still participates in the core build"
}
foreach ($stalePatch in @(
    "0002-akihalink-persistent-ebpf-backend.patch",
    "0004-akihalink-ebpf-startup-fallback.patch",
    "0005-akihalink-adaptive-http3-selector.patch",
    "0006-akihalink-content-metrics.patch",
    "0008-akihalink-android-system-resolver.patch"
)) {
    if (Test-Path (Join-Path $root "patches/sing-box/$stalePatch")) {
        throw "Stale pre-refactor eBPF patch remains: $stalePatch"
    }
}
foreach ($staleOverlay in @(
    "android_resolver_option.go", "persistent.go", "system_resolver.go",
    "system_resolver_identity.go"
)) {
    if (Test-Path (Join-Path $root "core-overlay/common/ebpf/$staleOverlay")) {
        throw "Stale pre-refactor eBPF overlay remains: $staleOverlay"
    }
}
foreach ($removedQualityFile in @(
    "core-overlay/experimental/akihalinkobs/adaptive.go",
    "core-overlay/experimental/akihalinkobs/content_metrics.go",
    "core-overlay/common/trafficcontrol/content.go",
    "core-overlay/common/trafficcontrol/content_test.go",
    "core-overlay/protocol/group/selector_http3_test.go"
)) {
    if (Test-Path (Join-Path $root $removedQualityFile)) {
        throw "Removed quality or HTTP/3 policy source remains: $removedQualityFile"
    }
}
$dialHotPath = Join-Path $root "core-overlay/common"
$forbiddenDialPath = 'path-advice|happy-eyeballs-observe|TCP_MAXSEG|akihalink_fingerprint|adaptiveTCPControl'
$dialMatches = Get-ChildItem -LiteralPath $dialHotPath -File -Recurse -ErrorAction SilentlyContinue |
    Select-String -Pattern $forbiddenDialPath
if ($dialMatches) {
    throw "Generic core overlay still performs active transport work in the dial path"
}

$moduleVersion = (Select-String -Path (Join-Path $module "module.prop") -Pattern '^version=(.+)$').Matches.Groups[1].Value
$moduleVersionCode = (Select-String -Path (Join-Path $module "module.prop") -Pattern '^versionCode=(\d+)$').Matches.Groups[1].Value
$gradleBuild = Get-Content (Join-Path $root "app/build.gradle.kts") -Raw
$appVersion = [regex]::Match($gradleBuild, 'versionName = "([^"]+)"').Groups[1].Value
$appVersionCode = [regex]::Match($gradleBuild, 'versionCode = (\d+)').Groups[1].Value
if ($moduleVersion -ne $appVersion) { throw "App/module version mismatch: $appVersion vs $moduleVersion" }
if ($appVersion -ne "1.1" -or $appVersionCode -ne "23" -or $moduleVersionCode -ne "23") {
    throw "Release version markers must be App/module 1.1/23"
}
$releaseCandidate = Get-Content (Join-Path $root "scripts/build-release-candidate.ps1") -Raw
$releaseDefault = [regex]::Match($releaseCandidate, '\[string\]\$Version = "([^"]+)"').Groups[1].Value
if ($releaseDefault -ne $appVersion) { throw "Release candidate default version mismatch: $releaseDefault vs $appVersion" }
$releaseWorkflow = Get-Content (Join-Path $root ".github/workflows/release.yml") -Raw
if ($releaseWorkflow -match '0\.12\.2' -or
    $releaseWorkflow -notmatch 'workflow_dispatch:\s*\r?\n\s*inputs:' -or
    $releaseWorkflow -notmatch 'version: \$\{\{ steps\.version\.outputs\.value \}\}' -or
    $releaseWorkflow -notmatch 'needs\.preflight\.outputs\.version') {
    throw "Release workflow does not derive all artifacts from the preflight version"
}
if ($releaseWorkflow -notmatch 'AKIHALINK_DEVICE_MATRIX_ATTESTATION' -or
    $releaseWorkflow -notmatch 'SING_BOX_EBPF_INTEGRATION_ATTACH=1' -or
    $releaseWorkflow -notmatch 'SING_BOX_EBPF_SHARED_INTEGRATION=1' -or
    $releaseWorkflow -notmatch 'with_ebpf,ebpf_integration' -or
    $releaseWorkflow -notmatch 'needs\.verifier\.result == ''success''') {
    throw "Release workflow does not gate publication on device acceptance and a real eBPF verifier run"
}
$appProtocol = [regex]::Match($gradleBuild, 'CONTROL_PROTOCOL_VERSION", "(\d+)"').Groups[1].Value
$controllerProtocol = (Select-String -Path (Join-Path $module "bin/akihalinkctl") -Pattern '^PROTOCOL=(\d+)$').Matches.Groups[1].Value
$installerProtocol = (Select-String -Path (Join-Path $module "customize.sh") -Pattern 'Control protocol: (\d+)').Matches.Groups[1].Value
if ($appProtocol -ne $controllerProtocol -or $appProtocol -ne $installerProtocol) {
    throw "Control protocol mismatch: app=$appProtocol controller=$controllerProtocol installer=$installerProtocol"
}
if ($appProtocol -ne "16") { throw "Control protocol must be 16" }
if ($packager -match '(?i)\$Edition|akihalink-tcp-cc-v1|akihalink-throughput-v1') {
    throw "Module packager still contains edition-specific branches"
}

& (Join-Path $PSScriptRoot "test-kernel-capabilities.ps1")
& $bash (Join-Path $root "scripts/test-main-control.sh")
if ($LASTEXITCODE -ne 0) { throw "Main core readiness controller fixtures failed" }
& $bash (Join-Path $root "scripts/test-speedtest-control.sh")
if ($LASTEXITCODE -ne 0) { throw "Auxiliary test controller fixtures failed" }
& $bash (Join-Path $root "scripts/test-trace-control.sh")
if ($LASTEXITCODE -ne 0) { throw "Perfetto trace controller fixtures failed" }

if (-not $Core) { $Core = Join-Path $root "build/core/sing-box" }
if (Test-Path $Core) {
    $readelf = Join-Path $env:LOCALAPPDATA "Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
    if (-not (Test-Path $readelf)) { throw "NDK readelf is unavailable" }
    $header = (& $readelf -h $Core) -join "`n"
    if ($header -notmatch 'ELF64' -or $header -notmatch 'AArch64') { throw "Core is not an ELF64 AArch64 binary" }
    & (Join-Path $PSScriptRoot "verify-core-artifact.ps1") -Core $Core
}

Write-Host "Module static checks passed"
