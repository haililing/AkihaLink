param(
    [Parameter(Mandatory = $true)]
    [string]$InputApk,
    [Parameter(Mandatory = $true)]
    [string]$Output,
    [long]$SourceDateEpoch = 0
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$pinnedLocalGo = Join-Path $root "build/toolchains/go1.26.7/bin/go.exe"
$goCommand = Get-Command go -ErrorAction SilentlyContinue
$go = if (Test-Path -LiteralPath $pinnedLocalGo) {
    $pinnedLocalGo
} elseif ($goCommand) {
    $goCommand.Source
} else {
    Join-Path $root "build/toolchains/go/bin/go.exe"
}
if (-not (Test-Path -LiteralPath $go)) { throw "Go 1.26.7 toolchain is unavailable" }
$goVersion = (& $go version)
if ($goVersion -notmatch 'go1\.26\.7\b') { throw "Go 1.26.7 is required (found: $goVersion)" }
if (-not (Test-Path -LiteralPath $InputApk)) { throw "Missing unsigned APK: $InputApk" }
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
New-Item -ItemType Directory -Path (Split-Path $Output -Parent) -Force | Out-Null
if (Test-Path -LiteralPath $Output) { Remove-Item -LiteralPath $Output -Force }
& $go run (Join-Path $root "tools/repropack/main.go") `
    normalize-zip ([IO.Path]::GetFullPath($InputApk)) ([IO.Path]::GetFullPath($Output)) $SourceDateEpoch
if ($LASTEXITCODE -ne 0) { throw "APK normalization failed" }
