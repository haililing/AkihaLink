param(
    [string]$Source = "",
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $Source) { $Source = Join-Path $root "third_party/sing-box" }
if (-not $Output) { $Output = Join-Path $root "build/core-source" }

$lockPath = Join-Path $root "patches/sing-box/patches.lock.json"
$lock = Get-Content $lockPath -Raw | ConvertFrom-Json
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
$hasGitMetadata = $false
$actualCommit = (& git -C $Source rev-parse HEAD 2>$null).Trim()
$hasGitHead = $LASTEXITCODE -eq 0 -and $actualCommit
$ErrorActionPreference = $previousErrorActionPreference
if ($hasGitHead) {
    $hasGitMetadata = $true
    if ($actualCommit -ne $lock.baseCommit) {
        throw "sing-box must be pinned to $($lock.baseCommit) (found $actualCommit)"
    }
}

$buildRoot = [IO.Path]::GetFullPath((Join-Path $root "build"))
$outputPath = [IO.Path]::GetFullPath($Output)
$buildPrefix = $buildRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $outputPath.StartsWith($buildPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Patched core source must stay inside $buildRoot"
}
if (Test-Path $outputPath) { Remove-Item -LiteralPath $outputPath -Recurse -Force }

if ($hasGitMetadata) {
    $previousClonePreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    & git clone --quiet --no-hardlinks --no-checkout $Source $outputPath 2>$null
    $localCloneSucceeded = $LASTEXITCODE -eq 0
    $ErrorActionPreference = $previousClonePreference
    if (-not $localCloneSucceeded) {
        # A partially downloaded submodule can have a valid worktree but an
        # unusable object store. Fall back to the pinned remote instead of
        # making every local build depend on repairing that cache manually.
        if (Test-Path $outputPath) { Remove-Item -LiteralPath $outputPath -Recurse -Force }
        $remote = (& git -C $Source remote get-url origin 2>$null).Trim()
        if (-not $remote) { throw "Failed to create isolated sing-box source and no origin remote is configured" }
        & git clone --quiet --no-checkout $remote $outputPath
        if ($LASTEXITCODE -ne 0) { throw "Failed to create isolated sing-box source" }
    }
    & git -C $outputPath config core.autocrlf false
    if ($LASTEXITCODE -ne 0) { throw "Failed to configure isolated sing-box source" }
    & git -C $outputPath -c advice.detachedHead=false checkout --quiet $lock.baseCommit
    if ($LASTEXITCODE -ne 0) { throw "Failed to checkout pinned sing-box source" }
    & git -C $outputPath clean -ffdx
    if ($LASTEXITCODE -ne 0) { throw "Failed to clean isolated sing-box source" }
} else {
    # Release source archives intentionally omit .git. Their third_party tree
    # is the raw pinned upstream source and receives the same locked patches.
    New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
    Get-ChildItem -LiteralPath $Source -Force |
        Where-Object Name -ne ".git" |
        Copy-Item -Destination $outputPath -Recurse -Force
    & git -C $outputPath init --quiet
    if ($LASTEXITCODE -ne 0) { throw "Failed to initialize isolated source archive workspace" }
    # Normalize text while applying patches after a Windows archive extraction.
    & git -C $outputPath config core.autocrlf true
    if ($LASTEXITCODE -ne 0) { throw "Failed to configure isolated source archive workspace" }
}

foreach ($entry in $lock.patches) {
    $patchPath = Join-Path $root "patches/sing-box/$($entry.file)"
    $actualHash = (Get-FileHash -Algorithm SHA256 $patchPath).Hash.ToLowerInvariant()
    if ($actualHash -ne $entry.sha256) {
        throw "Patch checksum mismatch for $($entry.file): $actualHash"
    }
    & git -C $outputPath apply --check $patchPath
    if ($LASTEXITCODE -ne 0) { throw "Patch does not apply: $($entry.file)" }
    & git -C $outputPath apply $patchPath
    if ($LASTEXITCODE -ne 0) { throw "Failed to apply patch: $($entry.file)" }
}

if ($hasGitMetadata) {
    & git -C $outputPath diff --check
    if ($LASTEXITCODE -ne 0) { throw "Patched sing-box source failed whitespace validation" }
}
Write-Output $outputPath
