$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$bash = if ($IsWindows -or $env:OS -eq "Windows_NT") {
    "C:\Program Files\Git\bin\bash.exe"
} else {
    (Get-Command bash -ErrorAction Stop).Source
}
if (-not (Test-Path $bash)) { throw "Git Bash is required for kernel capability fixtures" }

$fixtureRoot = Join-Path $root "build/kernel-capability-fixtures"
if (Test-Path $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force }
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null

function Write-FixtureFile([string]$caseRoot, [string]$relativePath, [string]$content) {
    $path = Join-Path $caseRoot $relativePath
    New-Item -ItemType Directory -Path (Split-Path $path -Parent) -Force | Out-Null
    Set-Content -LiteralPath $path -Value $content -NoNewline -Encoding ASCII
}

function New-Case([string]$name) {
    $path = Join-Path $fixtureRoot $name
    New-Item -ItemType Directory -Path "$path/proc", "$path/sys", "$path/runtime" -Force | Out-Null
    return $path
}

function Read-Capabilities([string]$caseRoot) {
    $runner = (Join-Path $root "scripts/fixtures/kernel-capabilities-fixture.sh").Replace('\', '/')
    $helper = (Join-Path $root "module/bin/kernel-capabilities.sh").Replace('\', '/')
    $fixture = $caseRoot.Replace('\', '/')
    $json = (& $bash $runner $fixture $helper) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Kernel capability fixture failed: $caseRoot" }
    return $json | ConvertFrom-Json
}

$enabled = New-Case "enabled"
Write-FixtureFile $enabled "config" @"
CONFIG_BPF_JIT=y
CONFIG_DEBUG_INFO_BTF=y
# CONFIG_CGROUP_BPF is not set
CONFIG_NET_SCH_FQ=m
# CONFIG_HMBIRD_SCHED is not set
# CONFIG_REKERNEL_NETWORK is not set
# CONFIG_TCP_CONG_BRUTAL is not set
"@
Write-FixtureFile $enabled "proc/sys/net/core/bpf_jit_enable" "1`n"
Write-FixtureFile $enabled "proc/sys/net/core/default_qdisc" "fq`n"
Write-FixtureFile $enabled "proc/sys/net/ipv4/tcp_available_congestion_control" "reno cubic bbr`n"
Write-FixtureFile $enabled "proc/sys/net/ipv4/tcp_congestion_control" "cubic`n"
Write-FixtureFile $enabled "proc/sys/net/ipv4/tcp_ecn" "2`n"
Write-FixtureFile $enabled "proc/sys/net/ipv4/tcp_fastopen" "1`n"
Write-FixtureFile $enabled "proc/kallsyms" "0000000000000000 T hmbird_update_task`n0000000000000000 T rekernel_network_event`n"
Write-FixtureFile $enabled "proc/pressure/cpu" "some avg10=0.01 avg60=0.02 avg300=0.03 total=10`nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0`n"
Write-FixtureFile $enabled "proc/pressure/memory" "some avg10=0.00 avg60=0.00 avg300=0.00 total=0`n"
Write-FixtureFile $enabled "proc/pressure/io" "some avg10=0.04 avg60=0.03 avg300=0.02 total=20`n"
Write-FixtureFile $enabled "sys/kernel/btf/vmlinux" "fixture"
Write-FixtureFile $enabled "cgroup-bpf-probed" "true"
$enabledResult = Read-Capabilities $enabled
$cap = $enabledResult.kernelCapabilities
if ($cap.configSource -ne "provided_config") { throw "Unexpected config source" }
foreach ($field in @("bpfJit", "btf", "cgroupBpf", "fq", "hmbird", "rekernelNetwork")) {
    if ($cap.$field -ne "enabled") { throw "$field should be enabled" }
}
if ($cap.tcpBrutal -ne "disabled") { throw "TCP Brutal should be disabled" }
if ($cap.tcpFastOpenClient -ne "enabled") { throw "TCP Fast Open client should be enabled" }
if ($cap.availableTcpCongestionControls -notcontains "bbr") { throw "BBR should be available" }
if ($cap.currentTcpCongestionControl -ne "cubic" -or $cap.tcpEcn -ne 2 -or $cap.defaultQdisc -ne "fq") {
    throw "Runtime TCP values were not decoded"
}
if ($enabledResult.psi.cpu -notmatch "avg10") { throw "PSI snapshot is missing" }

$disabled = New-Case "disabled"
Write-FixtureFile $disabled "config" @"
# CONFIG_BPF_JIT is not set
# CONFIG_DEBUG_INFO_BTF is not set
# CONFIG_CGROUP_BPF is not set
# CONFIG_NET_SCH_FQ is not set
# CONFIG_HMBIRD_SCHED is not set
# CONFIG_REKERNEL_NETWORK is not set
# CONFIG_TCP_CONG_BRUTAL is not set
"@
Write-FixtureFile $disabled "proc/sys/net/core/bpf_jit_enable" "0`n"
Write-FixtureFile $disabled "proc/sys/net/ipv4/tcp_fastopen" "0`n"
$disabledResult = Read-Capabilities $disabled
foreach ($field in @("bpfJit", "btf", "cgroupBpf", "fq", "hmbird", "rekernelNetwork", "tcpBrutal")) {
    if ($disabledResult.kernelCapabilities.$field -ne "disabled") { throw "$field should be disabled" }
}
if ($disabledResult.kernelCapabilities.tcpFastOpenClient -ne "disabled") {
    throw "TCP Fast Open client should be disabled"
}

$unknown = New-Case "unknown"
$unknownResult = Read-Capabilities $unknown
if ($unknownResult.kernelCapabilities.configSource -ne "runtime_only") { throw "Unknown fixture should be runtime-only" }
foreach ($field in @("bpfJit", "btf", "cgroupBpf", "fq", "hmbird", "rekernelNetwork", "tcpBrutal")) {
    if ($unknownResult.kernelCapabilities.$field -ne "unknown") { throw "$field should be unknown" }
}
if ($unknownResult.kernelCapabilities.tcpFastOpenClient -ne "unknown") {
    throw "TCP Fast Open client should be unknown"
}
if ($unknownResult.kernelCapabilities.availableTcpCongestionControls.Count -ne 0) {
    throw "Unknown fixture should have no congestion-control list"
}

Write-Host "Kernel capability fixtures passed"
