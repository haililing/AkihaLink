param(
    [Parameter(Mandatory = $true)]
    [string]$ServerHost,
    [Parameter(Mandatory = $true)]
    [ValidateSet("0.15.0", "0.16.0", "1.1")]
    [string]$Candidate,
    [Parameter(Mandatory = $true)]
    [ValidateSet("tcp", "hysteria2", "tuic")]
    [string]$Transport,
    [string]$Output = "",
    [int]$Runs = 7,
    [int]$DurationSeconds = 30,
    [int]$TcpPort = 5201,
    [int]$UdpPort = 5202
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if ($Runs -lt 7) { throw "Release measurements require at least seven runs" }
if ($DurationSeconds -lt 30) { throw "Each release measurement must run for at least 30 seconds" }
if (-not $Output) {
    $Output = Join-Path $root "build/benchmarks/dataplane-$Candidate.jsonl"
}
New-Item -ItemType Directory -Path (Split-Path $Output -Parent) -Force | Out-Null

$device = (& adb devices | Select-String "\tdevice$")
if ($device.Count -ne 1) { throw "Exactly one Android 16+ device must be attached" }
$sdk = (& adb shell getprop ro.build.version.sdk).Trim()
if ([int]$sdk -lt 36) { throw "The benchmark device must run Android 16 or newer" }

for ($run = 1; $run -le $Runs; $run++) {
    $temperature = (& adb shell dumpsys battery | Select-String 'temperature:' | ForEach-Object { $_.Line.Split(':')[1].Trim() })
    foreach ($test in @(
        @{ method = "tcpDownload"; concurrency = 1 },
        @{ method = "tcpDownload"; concurrency = 4 },
        @{ method = "tcpUpload"; concurrency = 1 },
        @{ method = "tcpUpload"; concurrency = 4 },
        @{ method = "udp1200BytePpsAndLoss"; concurrency = 1 },
        @{ method = "tcpConnectLatency"; concurrency = 1 }
    )) {
        & adb logcat -c
        $class = "com.akiha.akihalink.dataplanebenchmark.DataPlaneBenchmark#$($test.method)"
        $pid = (& adb shell su -c "cat /data/adb/akihalink/runtime/core.pid" 2>$null).Trim()
        if (-not $pid) { throw "The proxy core is not running" }
        $cpuCommand = "awk '{print " + '$14+$15' + "}' /proc/$pid/stat"
        $rssCommand = "awk '/VmRSS/{print " + '$2' + "}' /proc/$pid/status"
        $cpuBefore = ((& adb shell su -c $cpuCommand 2>$null) | Select-Object -First 1).Trim()
        $rssBefore = ((& adb shell su -c $rssCommand 2>$null) | Select-Object -First 1).Trim()
        & (Join-Path $PSScriptRoot "invoke-dataplane-instrumentation.ps1") `
            -Class $class `
            -ServerHost $ServerHost `
            -TcpPort $TcpPort `
            -UdpPort $UdpPort `
            -DurationSeconds $DurationSeconds `
            -Concurrency $($test.concurrency)
        if ($LASTEXITCODE -ne 0) { throw "Instrumentation benchmark failed: $class" }
        $cpuAfter = ((& adb shell su -c $cpuCommand 2>$null) | Select-Object -First 1).Trim()
        $metric = (& adb logcat -d -s AkihaLinkDataPlane:I '*:S' | Select-String '\{.*\}' | Select-Object -Last 1).Matches.Value
        if (-not $metric) { throw "Benchmark metric was not emitted: $class" }
        $rssKb = if ($pid) {
            ((& adb shell su -c "grep VmRSS /proc/$pid/status" 2>$null) -replace '[^0-9]', '')
        } else { "0" }
        $parsedMetric = $metric | ConvertFrom-Json
        $bits = if ($parsedMetric.workload -eq "udp_1200") {
            [double]$parsedMetric.received * 1200 * 8
        } elseif ($parsedMetric.bytes) {
            [double]$parsedMetric.bytes * 8
        } else { 0 }
        $cpuTicks = [long]$cpuAfter - [long]$cpuBefore
        $record = [ordered]@{
            version = $Candidate
            transport = $Transport
            run = $run
            deviceSdk = [int]$sdk
            temperatureDeciC = [int]$temperature
            coreRssBeforeKb = [long]$rssBefore
            coreRssKb = [long]$rssKb
            coreCpuTicks = $cpuTicks
            coreCpuTicksPerBit = if ($bits -gt 0) { $cpuTicks / $bits } else { 0 }
            metric = $parsedMetric
        }
        $recordJson = ($record | ConvertTo-Json -Compress -Depth 5) + "`n"
        [IO.File]::AppendAllText($Output, $recordJson, [Text.UTF8Encoding]::new($false))
    }
}
Write-Host "Recorded $Output. Run versions in alternating order and compare with scripts/evaluate-dataplane.ps1."
