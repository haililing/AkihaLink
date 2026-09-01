param(
    [string]$Output = "",
    [string]$Version = "1.1",
    [long]$SourceDateEpoch = 0
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$pinnedLocalGo = Join-Path $root "build/toolchains/go1.26.6/bin/go.exe"
$goCommand = Get-Command go -ErrorAction SilentlyContinue
$go = if (Test-Path $pinnedLocalGo) {
    $pinnedLocalGo
} elseif ($goCommand) {
    $goCommand.Source
} else {
    Join-Path $root "build/toolchains/go/bin/go.exe"
}
if (-not (Test-Path $go)) { throw "Go 1.26.6 toolchain is unavailable" }
$goVersion = (& $go version)
if ($goVersion -notmatch 'go1\.26\.6\b') { throw "Go 1.26.6 is required (found: $goVersion)" }
if (-not $Output) { $Output = Join-Path $root "build/outputs/AkihaLink-$Version-source.tar.gz" }
New-Item -ItemType Directory -Path (Split-Path $Output -Parent) -Force | Out-Null

$workRoot = [IO.Path]::GetFullPath((Join-Path $root "build/source-archive"))
$buildRoot = [IO.Path]::GetFullPath((Join-Path $root "build"))
if (-not $workRoot.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Source archive staging escaped the build directory"
}
if (Test-Path $workRoot) { Remove-Item -LiteralPath $workRoot -Recurse -Force }
New-Item -ItemType Directory -Path $workRoot -Force | Out-Null

$sourceName = "AkihaLink-$Version"
$sourceRoot = Join-Path $workRoot $sourceName
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
& git -C $root rev-parse --verify HEAD *> $null
$hasHead = $LASTEXITCODE -eq 0
$worktreeStatus = if ($hasHead) { (& git -C $root status --porcelain=v1 --untracked-files=normal) -join "`n" } else { "" }
$useGitArchive = $hasHead -and [string]::IsNullOrWhiteSpace($worktreeStatus)
$ErrorActionPreference = $previousErrorActionPreference
if ($useGitArchive) {
    $rootTar = Join-Path $workRoot "root.tar"
    & git -C $root archive --format=tar --output=$rootTar --prefix="$sourceName/" HEAD
    if ($LASTEXITCODE -ne 0) { throw "Root source archive failed" }
    & tar -xf $rootTar -C $workRoot
    if ($LASTEXITCODE -ne 0) { throw "Root source extraction failed" }
    Remove-Item -LiteralPath $rootTar -Force
} else {
    # Keep local pre-commit builds useful without packaging caches or machine-local state.
    New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath $root -File -Recurse -Force) {
        $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
        if ($relative -eq "local.properties" -or
            $relative -like "6.6.118-*.zip" -or
            $relative -match '(^|/)(\.git|\.gradle|\.idea|build)(/|$)' -or
            $relative -match '^third_party/sing-box(/|$)') {
            continue
        }
        $destination = Join-Path $sourceRoot $relative
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
    }
}

$coreSource = Join-Path $root "third_party/sing-box"
$lock = Get-Content (Join-Path $root "patches/sing-box/patches.lock.json") -Raw | ConvertFrom-Json
$actualCoreCommit = (& git -C $coreSource rev-parse HEAD).Trim()
if ($actualCoreCommit -ne $lock.baseCommit) {
    throw "Pinned sing-box commit does not match the patch lock"
}
$coreDestination = Join-Path $sourceRoot "third_party/sing-box"
New-Item -ItemType Directory -Path $coreDestination -Force | Out-Null
$coreTar = Join-Path $workRoot "sing-box.tar"
& git -C $coreSource archive --format=tar --output=$coreTar $lock.baseCommit
if ($LASTEXITCODE -ne 0) { throw "Failed to archive the pinned sing-box source" }
& tar -xf $coreTar -C $coreDestination
if ($LASTEXITCODE -ne 0) { throw "Failed to extract the pinned sing-box source" }
Remove-Item -LiteralPath $coreTar -Force

if ($SourceDateEpoch -le 0 -and $env:SOURCE_DATE_EPOCH) {
    $SourceDateEpoch = [long]$env:SOURCE_DATE_EPOCH
}
if ($SourceDateEpoch -le 0) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    & git -C $root rev-parse --verify HEAD *> $null
    $hasRootHead = $LASTEXITCODE -eq 0
    $ErrorActionPreference = $previousPreference
    if ($hasRootHead) {
        $epochText = & git -C $root show -s --format=%ct HEAD
    } else {
        $epochText = & git -C (Join-Path $root "third_party/sing-box") show -s --format=%ct HEAD
    }
    $SourceDateEpoch = [long](($epochText | Select-Object -First 1).Trim())
}
& $go run (Join-Path $root "tools/repropack/main.go") `
    targz $sourceRoot $sourceName $Output $SourceDateEpoch
if ($LASTEXITCODE -ne 0) { throw "Deterministic source archive compression failed" }
$sourceHash = (Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumPath = "$Output.sha256"
"$sourceHash  $(Split-Path $Output -Leaf)" |
    Set-Content -LiteralPath $checksumPath -Encoding ascii
Write-Host "Packaged source archive $Output"
