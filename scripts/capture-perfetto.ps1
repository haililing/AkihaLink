param(
    [string]$Serial = "",
    [string]$Output = "",
    [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$config = Join-Path $PSScriptRoot "perfetto/akihalink.pbtx"
if (-not $Output) {
    $Output = Join-Path $root ("build/perfetto/akihalink-{0}.perfetto-trace" -f
        [DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss"))
}
$outputDirectory = Split-Path $Output -Parent
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }
$remote = "/data/misc/perfetto-traces/akihalink.perfetto-trace"

Write-Host "Recording the fixed 30 second AkihaLink trace configuration..."
Get-Content -LiteralPath $config -Raw |
    & $Adb @adbArgs shell perfetto --txt -c - -o $remote
if ($LASTEXITCODE -ne 0) { throw "Perfetto capture failed" }

& $Adb @adbArgs pull $remote $Output
if ($LASTEXITCODE -ne 0) { throw "Unable to pull Perfetto trace" }
& $Adb @adbArgs shell rm -f $remote | Out-Null
Write-Host "Trace saved to $Output"
