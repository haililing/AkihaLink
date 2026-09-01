param(
    [string]$PreparedSource = "",
    [string]$Go = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $PreparedSource) { $PreparedSource = Join-Path $root "build/core-source" }
if (-not $Go) { $Go = Join-Path $root "build/toolchains/go1.26.6/bin/go.exe" }
if (-not (Test-Path (Join-Path $PreparedSource "go.mod"))) {
    throw "Prepared core source is missing; run scripts/build-core.ps1 first"
}
$fixtures = Join-Path $root "build/config-fixtures"
New-Item -ItemType Directory -Path $fixtures -Force | Out-Null

Push-Location $root
try {
    & (Join-Path $root "gradlew.bat") --no-daemon testDebugUnitTest "-Pakihalink.fixtureDir=$fixtures"
    if ($LASTEXITCODE -ne 0) { throw "Configuration fixture generation failed" }
} finally {
    Pop-Location
}

$hostCore = Join-Path $root "build/core/sing-box-minimal-host.exe"
$previous = @{}
foreach ($name in @("GOOS", "GOARCH", "CGO_ENABLED", "GOTOOLCHAIN")) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}
Push-Location $PreparedSource
try {
    $env:GOOS = "windows"
    $env:GOARCH = "amd64"
    $env:CGO_ENABLED = "0"
    $env:GOTOOLCHAIN = "local"
    # Windows host validation omits Android-only linkname/uTLS glue; the
    # release Android build above still compiles those tags.
    $tags = "with_quic,with_clash_api,grpcnotrace,with_akihalink_minimal_registry"
    & $Go build -trimpath -buildvcs=false -pgo=off -tags $tags -o $hostCore ./cmd/sing-box
    if ($LASTEXITCODE -ne 0) { throw "Minimal host validation core failed to build" }
} finally {
    Pop-Location
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], "Process")
    }
}

foreach ($name in @("all-protocols.json", "all-transports.json", "speedtest.json")) {
    $source = Join-Path $fixtures $name
    if (-not (Test-Path $source)) { throw "Missing generated fixture: $name" }
    $config = Get-Content -LiteralPath $source -Raw | ConvertFrom-Json
    if ($config.PSObject.Properties.Name -contains "inbounds") {
        $config.PSObject.Properties.Remove("inbounds")
    }
    $hostFixture = Join-Path $fixtures "host-$name"
    $hostConfig = $config | ConvertTo-Json -Depth 100 -Compress
    [IO.File]::WriteAllText($hostFixture, $hostConfig, [Text.UTF8Encoding]::new($false))
    & $hostCore check -c $hostFixture
    if ($LASTEXITCODE -ne 0) { throw "Minimal core rejected supported fixture: $name" }
}

$unsupported = Join-Path $fixtures "unsupported.json"
[IO.File]::WriteAllText(
    $unsupported,
    '{"outbounds":[{"type":"socks","tag":"unsupported","server":"127.0.0.1","server_port":1080}]}',
    [Text.UTF8Encoding]::new($false)
)
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
& $hostCore check -c $unsupported *> $null
$unsupportedExit = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
if ($unsupportedExit -eq 0) { throw "Minimal core accepted an unsupported SOCKS outbound" }
Write-Host "Minimal core protocol and transport fixtures passed"
