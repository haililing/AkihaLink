param(
    [Parameter(Mandatory = $true)]
    [string]$Core
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$corePath = [IO.Path]::GetFullPath($Core)
if (-not (Test-Path -LiteralPath $corePath -PathType Leaf)) {
    throw "Missing sing-box core: $corePath"
}

$patchLock = Get-Content (Join-Path $root "patches/sing-box/patches.lock.json") -Raw | ConvertFrom-Json
$genericLock = Get-Content (Join-Path $root "patches/sing-box/generic-patches.lock.json") -Raw | ConvertFrom-Json
$expectedVersion = "$($patchLock.baseVersion)-$($genericLock.patchSet)"

# An Android core cannot be executed on the build host. Inspect immutable strings
# embedded by the linker and by the registered custom outbound instead.
$coreText = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($corePath))
$requiredMarkers = @(
    $expectedVersion,
    "akihalink-fallback"
)
foreach ($marker in $requiredMarkers) {
    if ($coreText.IndexOf($marker, [StringComparison]::Ordinal) -lt 0) {
        throw "Core artifact is stale or incompatible: missing marker '$marker'. Run scripts/build-core.ps1 first."
    }
}

Write-Host "Verified core artifact $corePath ($expectedVersion)"
