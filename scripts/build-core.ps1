param(
    [string]$Ndk = "$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865",
    [string]$Output = "",
    [string]$Source = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$goEnvironmentNames = @("CGO_ENABLED", "GOOS", "GOARCH", "CC", "GOTOOLCHAIN", "GOPACKAGE")
$previousGoEnvironment = @{}
foreach ($name in $goEnvironmentNames) {
    $previousGoEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}
$upstreamSource = if ($Source) { [IO.Path]::GetFullPath($Source) } else { Join-Path $root "third_party/sing-box" }
if (-not $Output) { $Output = Join-Path $root "build/core/sing-box" }
$Output = [IO.Path]::GetFullPath($Output)
$pinnedLocalGo = Join-Path $root "build/toolchains/go1.26.7/bin/go.exe"
$goCommand = Get-Command go -ErrorAction SilentlyContinue
$go = if (Test-Path $pinnedLocalGo) {
    $pinnedLocalGo
} elseif ($goCommand) {
    $goCommand.Source
} else {
    Join-Path $root "build/toolchains/go/bin/go.exe"
}
if (-not (Test-Path $go)) { throw "Go 1.26.7 toolchain is unavailable" }
$lock = Get-Content (Join-Path $root "patches/sing-box/patches.lock.json") -Raw | ConvertFrom-Json

$goVersion = (& $go version)
if ($goVersion -notmatch 'go1\.26\.7\b') { throw "Go 1.26.7 is required (found: $goVersion)" }
$hostTag = if ($IsWindows -or $env:OS -eq "Windows_NT") { "windows-x86_64" } else { "linux-x86_64" }
$compilerName = if ($hostTag -eq "windows-x86_64") { "aarch64-linux-android35-clang.cmd" } else { "aarch64-linux-android35-clang" }
$cc = Join-Path $Ndk "toolchains/llvm/prebuilt/$hostTag/bin/$compilerName"
if (-not (Test-Path $cc)) { throw "NDK r29 Android clang was not found at $cc" }

$genericLock = Get-Content (Join-Path $root "patches/sing-box/generic-patches.lock.json") -Raw | ConvertFrom-Json
$source = (& (Join-Path $PSScriptRoot "prepare-core-source.ps1") -Source $upstreamSource).Trim()
    $overlay = Join-Path $root "core-overlay"
    foreach ($file in Get-ChildItem -LiteralPath $overlay -File -Recurse) {
        $relative = $file.FullName.Substring($overlay.Length + 1)
        $target = Join-Path $source $relative
        New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
    }
    foreach ($entry in $genericLock.patches) {
        $genericPatch = Join-Path $root "patches/sing-box/$($entry.file)"
        $actualHash = (Get-FileHash -Algorithm SHA256 $genericPatch).Hash.ToLowerInvariant()
        if ($actualHash -ne $entry.sha256) {
            throw "Generic patch checksum mismatch for $($entry.file): $actualHash"
        }
        & git -C $source apply --check $genericPatch
        if ($LASTEXITCODE -ne 0) { throw "Generic eBPF patch does not apply: $($entry.file)" }
        & git -C $source apply $genericPatch
        if ($LASTEXITCODE -ne 0) { throw "Failed to apply generic eBPF patch: $($entry.file)" }
    }
    & git -C $source diff --check
    if ($LASTEXITCODE -ne 0) { throw "Patched generic sing-box source failed whitespace validation" }

    $ciliumVersion = [string]$genericLock.dependencies.'github.com/cilium/ebpf'
    $observabilityDependency = "github.com/cilium/ebpf@$ciliumVersion"
    Push-Location $source
    try {
        & $go mod download $observabilityDependency
        if ($LASTEXITCODE -ne 0) { throw "Failed to download $observabilityDependency" }
        $upstreamCiliumVersion = (& $go list -m -f '{{.Version}}' github.com/cilium/ebpf).Trim()
        if ($upstreamCiliumVersion -ne $ciliumVersion) {
            throw "Pinned sing-box cilium/ebpf version mismatch: $upstreamCiliumVersion"
        }
    } finally {
        Pop-Location
    }

    $bpfCompiler = Join-Path $Ndk "toolchains/llvm/prebuilt/$hostTag/bin/clang"
    if ($hostTag -eq "windows-x86_64") { $bpfCompiler += ".exe" }
    if (-not (Test-Path $bpfCompiler)) { throw "NDK r29 BPF clang was not found at $bpfCompiler" }
    $readelf = Join-Path $Ndk "toolchains/llvm/prebuilt/$hostTag/bin/llvm-readelf"
    if ($hostTag -eq "windows-x86_64") { $readelf += ".exe" }
    if (-not (Test-Path $readelf)) { throw "NDK r29 llvm-readelf was not found at $readelf" }
    $objcopy = Join-Path $Ndk "toolchains/llvm/prebuilt/$hostTag/bin/llvm-objcopy"
    if ($hostTag -eq "windows-x86_64") { $objcopy += ".exe" }
    if (-not (Test-Path $objcopy)) { throw "NDK r29 llvm-objcopy was not found at $objcopy" }
    $sysroot = Join-Path $Ndk "toolchains/llvm/prebuilt/$hostTag/sysroot/usr/include"
    $resourceInclude = Join-Path ((& $bpfCompiler -print-resource-dir).Trim()) "include"
    if (-not (Test-Path $resourceInclude)) { throw "Clang resource headers are unavailable: $resourceInclude" }
    $bpf2goPackage = "github.com/cilium/ebpf/cmd/bpf2go@$ciliumVersion"
    $toolCopy = $null
    if ($hostTag -eq "windows-x86_64") {
        $moduleCache = (& $go env GOMODCACHE).Trim()
        $toolSource = Join-Path $moduleCache "github.com/cilium/ebpf@$ciliumVersion"
        $toolCopy = [IO.Path]::GetFullPath((Join-Path $root "build/tools/cilium-ebpf-bpf2go"))
        $buildRoot = [IO.Path]::GetFullPath((Join-Path $root "build"))
        $buildPrefix = $buildRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $toolCopy.StartsWith($buildPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "bpf2go tool copy escaped the build directory"
        }
        if (Test-Path $toolCopy) { Remove-Item -LiteralPath $toolCopy -Recurse -Force }
        Copy-Item -LiteralPath $toolSource -Destination $toolCopy -Recurse
        Get-ChildItem -LiteralPath $toolCopy -File -Recurse | ForEach-Object { $_.IsReadOnly = $false }
        Get-ChildItem (Join-Path $toolCopy "cmd/bpf2go") -Filter *.go -File -Recurse |
            ForEach-Object {
                $lines = Get-Content -LiteralPath $_.FullName
                if ($lines.Count -gt 2 -and $lines[0] -eq "//go:build !windows") {
                    [IO.File]::WriteAllLines(
                        $_.FullName,
                        $lines[2..($lines.Count - 1)],
                        [Text.UTF8Encoding]::new($false)
                    )
                }
            }
    }

    function Invoke-LockedBpf2Go([string[]]$Arguments) {
        if ($hostTag -eq "windows-x86_64") {
            Push-Location $toolCopy
            try { & $go run ./cmd/bpf2go @Arguments } finally { Pop-Location }
        } else {
            & $go run $bpf2goPackage @Arguments
        }
        if ($LASTEXITCODE -ne 0) { throw "Locked bpf2go generation failed" }
    }

    $commonBpfDirectory = Join-Path $source "common/ebpf"
    $generatedBpfDirectory = Join-Path $commonBpfDirectory "internal/bpfgen"
    # Upstream 1.15 removed splice from the loader; never ship stale generated objects.
    foreach ($name in @("splice_bpfel.go", "splice_bpfeb.go", "splice_bpfel.o", "splice_bpfeb.o")) {
        $stale = Join-Path $generatedBpfDirectory $name
        if (Test-Path -LiteralPath $stale) { Remove-Item -LiteralPath $stale -Force }
    }
    $mainCFlags = @(
        "-mcpu=v1", "-O2", "-g0", "-ffreestanding",
        "-mllvm", "-disable-block-placement", "-mllvm", "-disable-branch-fold",
        "-nostdinc", "-I", (Join-Path $commonBpfDirectory "native"), "-isystem", $resourceInclude,
        "-isystem", $sysroot, "-isystem", (Join-Path $sysroot "aarch64-linux-android")
    )
    $mainObjects = @(
        @{ Stem = "tc"; Name = "TC"; Source = "native/tc.bpf.c" },
        @{ Stem = "cgroup_coarse"; Name = "CgroupCoarse"; Source = "native/cgroup_coarse.bpf.c" },
        @{ Stem = "cgroup_storage"; Name = "CgroupStorage"; Source = "native/cgroup_storage.bpf.c" },
        @{ Stem = "cgroup"; Name = "Cgroup"; Source = "native/cgroup.bpf.c" },
        @{ Stem = "shared_network"; Name = "SharedNetwork"; Source = "native/shared_network.bpf.c" },
        @{ Stem = "fakeip_icmp"; Name = "FakeIPICMP"; Source = "native/fakeip_icmp.bpf.c" }
    )
    function Invoke-MainBpfGeneration {
        Push-Location $commonBpfDirectory
        try {
            $env:GOPACKAGE = "ebpf"
            foreach ($definition in $mainObjects) {
                $arguments = @(
                    "-cc", $bpfCompiler, "-target", "bpfel,bpfeb", "-tags", "with_ebpf",
                    "-no-global-types", "-no-strip", "-go-package", "bpfgen",
                    "-output-dir", $generatedBpfDirectory, "-output-stem", $definition.Stem,
                    $definition.Name, (Join-Path $commonBpfDirectory $definition.Source), "--"
                ) + $mainCFlags
                Invoke-LockedBpf2Go $arguments
            }
            Get-ChildItem -LiteralPath $generatedBpfDirectory -Filter "*_bpf*.o" -File |
                ForEach-Object {
                    & $objcopy --remove-section=.BTF --remove-section=.BTF.ext $_.FullName
                    if ($LASTEXITCODE -ne 0) { throw "Failed to strip main eBPF BTF sections from $($_.Name)" }
                }
        } finally {
            Pop-Location
        }
    }
    Invoke-MainBpfGeneration
    $generatedFiles = Get-ChildItem -LiteralPath $generatedBpfDirectory -File |
        Where-Object { $_.Name -match '^(tc|cgroup|cgroup_coarse|cgroup_storage|shared_network|fakeip_icmp)_bpf(e[bl])\.(go|o)$' } |
        Sort-Object Name
    if ($generatedFiles.Count -ne 24) { throw "Expected 24 generated main eBPF files, found $($generatedFiles.Count)" }
    $firstGeneration = @{}
    foreach ($file in $generatedFiles) {
        $firstGeneration[$file.Name] = (Get-FileHash -Algorithm SHA256 $file.FullName).Hash
    }
    Invoke-MainBpfGeneration
    foreach ($file in $generatedFiles) {
        $secondHash = (Get-FileHash -Algorithm SHA256 $file.FullName).Hash
        if ($secondHash -ne $firstGeneration[$file.Name]) {
            throw "Main eBPF generation is not reproducible: $($file.Name)"
        }
    }
    $manifest = @(
        "generator: Android NDK r29 Clang 21",
        "targets: bpfel,bpfeb",
        "cflags: -mcpu=v1 -O2 -g0 -ffreestanding -mllvm -disable-block-placement -mllvm -disable-branch-fold -nostdinc -Inative -isystem <clang-resource>/include -isystem <ndk-sysroot>/usr/include -isystem <ndk-sysroot>/usr/include/aarch64-linux-android",
        "include_environment: ignored by -nostdinc",
        "bpf2go: $ciliumVersion",
        "clang: $((& $bpfCompiler --version | Select-Object -First 1).Trim())"
    )
    $manifestInputs = @(
        Get-ChildItem (Join-Path $commonBpfDirectory "native") -File |
            Where-Object Extension -in @(".c", ".h")
        Get-ChildItem $generatedBpfDirectory -Filter "*_bpf*.o" -File
    ) | Sort-Object FullName
    foreach ($file in $manifestInputs) {
        $relative = [IO.Path]::GetRelativePath($commonBpfDirectory, $file.FullName).Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 $file.FullName).Hash.ToLowerInvariant()
        $manifest += "$hash  $relative"
    }
    [IO.File]::WriteAllLines(
        (Join-Path $generatedBpfDirectory "manifest.txt"),
        $manifest,
        [Text.UTF8Encoding]::new($false)
    )
    & (Join-Path $PSScriptRoot "verify-bpf-objects.ps1") `
        -Directory $generatedBpfDirectory -ReadElf $readelf

    $bpfDirectory = Join-Path $source "experimental/akihalinkobs"
    $observabilityArguments = @(
        "-cc", $bpfCompiler, "-target", "bpfel", "-no-global-types", "-no-strip",
        "-go-package", "akihalinkobs", "-output-dir", $bpfDirectory,
        "Akihalink", (Join-Path $bpfDirectory "akihalink_bpf.c"), "--",
        "-O2", "-g", "-Wall", "-Werror", "-fdebug-compilation-dir=.",
        "-fdebug-prefix-map=$source=.", "-fdebug-prefix-map=$root=.",
        "-I", (Join-Path $bpfDirectory "bpf_headers"),
        "-I", $sysroot, "-I", (Join-Path $sysroot "aarch64-linux-android")
    )
    $env:GOPACKAGE = "akihalinkobs"
    Invoke-LockedBpf2Go $observabilityArguments
    $sectionTable = (& $readelf -S (Join-Path $bpfDirectory "akihalink_bpfel.o")) -join "`n"
    if ($sectionTable -notmatch '\.BTF(\s|$)' -or $sectionTable -notmatch '\.BTF\.ext(\s|$)') {
        throw "bpf2go output is missing BTF or CO-RE relocation sections"
    }
New-Item -ItemType Directory -Path (Split-Path $Output -Parent) -Force | Out-Null
$env:CGO_ENABLED = "1"
$env:GOOS = "android"
$env:GOARCH = "arm64"
$env:CC = $cc
$env:GOTOOLCHAIN = "local"
$tags = "with_quic,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0,with_ebpf,grpcnotrace"
$tags += ",with_akihalink_observability,with_akihalink_minimal_registry"
$patchSet = $genericLock.patchSet
$baseVersion = [string]$lock.baseVersion
if (-not $baseVersion) { throw "Pinned sing-box base version is missing from patches.lock.json" }
$version = "$baseVersion-$patchSet"
$sharedLdflags = (Get-Content (Join-Path $source "release/LDFLAGS") -Raw).Trim()
Push-Location $source
try {
    & $go build -v -trimpath -buildvcs=false -pgo=off -tags $tags -ldflags "-X github.com/sagernet/sing-box/constant.Version=$version $sharedLdflags -s -w -buildid=" -o $Output ./cmd/sing-box
    if ($LASTEXITCODE -ne 0) { throw "sing-box build failed" }
    & (Join-Path $PSScriptRoot "verify-core-artifact.ps1") -Core $Output
} finally {
    Pop-Location
    foreach ($name in $goEnvironmentNames) {
        [Environment]::SetEnvironmentVariable(
            $name,
            $previousGoEnvironment[$name],
            "Process"
        )
    }
}
Write-Host "Built core $Output from $($lock.baseCommit) with $patchSet"
