param(
    [string]$Core = "",
    [string]$Output = "",
    [long]$SourceDateEpoch = 0,
    [string]$Staging = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$version = "1.2.0"
$versionCode = 23
$protocol = 16
if (-not $Core) { $Core = Join-Path $root "build/core/sing-box" }
if (-not $Output) {
    $Output = Join-Path $root "build/outputs/AkihaLink-KSU-$version.zip"
}
if (-not (Test-Path $Core)) {
    throw "Missing sing-box core: $Core. Run scripts/build-core.ps1 first."
}

& (Join-Path $PSScriptRoot "verify-core-artifact.ps1") -Core $Core
& (Join-Path $PSScriptRoot "verify-rules.ps1")
$staging = if ($Staging) { $Staging } else { Join-Path $root "build/module-staging" }
if (Test-Path $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
Copy-Item (Join-Path $root "module") $staging -Recurse
Copy-Item $Core (Join-Path $staging "bin/sing-box") -Force
Remove-Item (Join-Path $staging "rules/README.md") -Force -ErrorAction SilentlyContinue

$modulePropPath = Join-Path $staging "module.prop"
$moduleProp = Get-Content -LiteralPath $modulePropPath -Raw
$description = "Android 16 arm64 sing-box eBPF proxy runtime (control protocol 16, optional Wi-Fi hotspot proxy, and on-demand TCP diagnostics)"
$moduleProp = [regex]::Replace($moduleProp, '(?m)^name=.*$', 'name=AkihaLink')
$moduleProp = [regex]::Replace($moduleProp, '(?m)^version=.*$', "version=$version")
$moduleProp = [regex]::Replace($moduleProp, '(?m)^versionCode=.*$', "versionCode=$versionCode")
$moduleProp = [regex]::Replace($moduleProp, '(?m)^description=.*$', "description=$description")
[IO.File]::WriteAllText($modulePropPath, $moduleProp, [Text.UTF8Encoding]::new($false))

$controllerPath = Join-Path $staging "bin/akihalinkctl"
$controller = Get-Content -LiteralPath $controllerPath -Raw
$controller = [regex]::Replace($controller, '(?m)^PROTOCOL=\d+$', "PROTOCOL=$protocol")
$controller = [regex]::Replace($controller, '(?m)^MODULE_VERSION=.*$', "MODULE_VERSION=$version")
$controller = [regex]::Replace(
    $controller,
    '(?m)^CORE_PATCH_SET=.*$',
    'CORE_PATCH_SET=akihalink-upstream-ebpf-v17'
)
[IO.File]::WriteAllText($controllerPath, $controller, [Text.UTF8Encoding]::new($false))

$customizePath = Join-Path $staging "customize.sh"
$customize = Get-Content -LiteralPath $customizePath -Raw
$customize = [regex]::Replace($customize, 'Control protocol: \d+', "Control protocol: $protocol")
[IO.File]::WriteAllText($customizePath, $customize, [Text.UTF8Encoding]::new($false))

New-Item -ItemType Directory -Path (Split-Path $Output -Parent) -Force | Out-Null
if (Test-Path $Output) { Remove-Item -LiteralPath $Output -Force }

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

$stagingRoot = (Resolve-Path $staging).Path.TrimEnd('\', '/')
$outputPath = [IO.Path]::GetFullPath($Output)
$pinnedLocalGo = Join-Path $root "build/toolchains/go1.26.7/bin/go.exe"
$goCommand = Get-Command go -ErrorAction SilentlyContinue
$goExecutable = if (Test-Path $pinnedLocalGo) {
    $pinnedLocalGo
} elseif ($goCommand) {
    $goCommand.Source
} else {
    Join-Path $root "build/toolchains/go/bin/go.exe"
}
if (-not (Test-Path $goExecutable)) {
    throw "Go 1.26.7 toolchain is unavailable. Run scripts/build-core.ps1 to provision the pinned toolchain."
}
$goVersion = (& $goExecutable version)
if ($goVersion -notmatch 'go1\.26\.7\b') { throw "Go 1.26.7 is required (found: $goVersion)" }
& $goExecutable run (Join-Path $root "tools/repropack/main.go") `
    zip $stagingRoot $outputPath $SourceDateEpoch
if ($LASTEXITCODE -ne 0) { throw "Deterministic module packaging failed" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$requiredEntries = @(
    "module.prop", "customize.sh", "service.sh", "action.sh", "uninstall.sh",
    "bin/akihalinkctl", "bin/kernel-capabilities.sh", "bin/sing-box", "bin/supervisor.sh",
    "rules/geoip-cn.srs", "rules/geosite-geolocation-cn.srs",
    "rules/geosite-geolocation-!cn.srs"
)
$archive = [IO.Compression.ZipFile]::OpenRead($outputPath)
try {
    $entryNames = @($archive.Entries | ForEach-Object FullName)
    if ($entryNames | Where-Object { $_.Contains('\') }) {
        throw "Module ZIP contains Windows path separators"
    }
    foreach ($entry in $requiredEntries) {
        if ($entry -notin $entryNames) { throw "Module ZIP is missing $entry" }
    }
} finally {
    $archive.Dispose()
}
Write-Host "Packaged $Output"
