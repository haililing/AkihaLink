param(
    [Parameter(Mandatory = $true)]
    [string]$Output,
    [string]$Ndk = "$env:ANDROID_NDK_HOME",
    [string]$Version = "1.1",
    [long]$SourceDateEpoch = 0
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
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
if (-not $Ndk) { throw "ANDROID_NDK_HOME or -Ndk is required" }

$outputPath = [IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null

& (Join-Path $PSScriptRoot "build-core.ps1") `
    -Ndk $Ndk `
    -Output (Join-Path $outputPath "sing-box-$Version-android-arm64")
if ($LASTEXITCODE -ne 0) { throw "Core build failed" }

Push-Location $root
try {
    $gradle = if ($IsWindows -or $env:OS -eq "Windows_NT") {
        Join-Path $root "gradlew.bat"
    } else {
        Join-Path $root "gradlew"
    }
    & $gradle --no-daemon assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Release APK build failed" }
} finally {
    Pop-Location
}
$apk = Get-ChildItem (Join-Path $root "app/build/outputs/apk/release") -File -Filter *.apk |
    Sort-Object Name |
    Select-Object -First 1
if (-not $apk) { throw "Unsigned release APK was not produced" }
& (Join-Path $PSScriptRoot "normalize-apk.ps1") `
    -InputApk $apk.FullName `
    -Output (Join-Path $outputPath "AkihaLink-$Version-unsigned.apk") `
    -SourceDateEpoch $SourceDateEpoch

& (Join-Path $PSScriptRoot "package-module.ps1") `
    -Core (Join-Path $outputPath "sing-box-$Version-android-arm64") `
    -Output (Join-Path $outputPath "AkihaLink-KSU-$Version.zip") `
    -SourceDateEpoch $SourceDateEpoch `
    -Staging (Join-Path $root "build/release-module-staging")

& (Join-Path $PSScriptRoot "source-archive.ps1") `
    -Version $Version `
    -Output (Join-Path $outputPath "AkihaLink-$Version-source.tar.gz") `
    -SourceDateEpoch $SourceDateEpoch
