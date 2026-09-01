param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug",
    [string]$Core = "",
    [string]$Version = "1.1"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $Core) { $Core = Join-Path $root "build/core/sing-box" }
if (-not (Test-Path $Core)) { throw "Missing sing-box core: $Core" }

$suffix = $Configuration.ToLowerInvariant()
$outputDirectory = Join-Path $root "build/outputs"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

Push-Location $root
try {
    & (Join-Path $root "gradlew.bat") "assemble$Configuration"
    if ($LASTEXITCODE -ne 0) { throw "Android build failed" }

    $apkDirectory = Join-Path $root "app/build/outputs/apk/$suffix"
    $apkSource = Get-ChildItem $apkDirectory -File -Filter "app-$suffix*.apk" |
        Sort-Object Name |
        Select-Object -First 1
    if (-not $apkSource) { throw "Missing APK in $apkDirectory" }
    $apkName = if ($Configuration -eq "Release") {
        "AkihaLink-$Version-unsigned.apk"
    } else {
        "AkihaLink-$Version.apk"
    }
    Copy-Item $apkSource.FullName (Join-Path $outputDirectory $apkName) -Force
    Copy-Item (Join-Path $root "THIRD_PARTY_NOTICES.md") $outputDirectory -Force

    & (Join-Path $PSScriptRoot "package-module.ps1") `
        -Core $Core `
        -Output (Join-Path $outputDirectory "AkihaLink-KSU-$Version.zip")

    & (Join-Path $PSScriptRoot "write-checksums.ps1") `
        -Directory $outputDirectory `
        -Include @($apkName, "AkihaLink-KSU-$Version.zip", "THIRD_PARTY_NOTICES.md")
} finally {
    Pop-Location
}
