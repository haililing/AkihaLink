param(
    [string]$Output = "",
    [int]$DurationHours = 24,
    [int]$IntervalSeconds = 60,
    [int]$DebugPort = 16060
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if ($DurationHours -lt 1 -or $DurationHours -gt 72) { throw "Soak duration must be 1-72 hours" }
if ($IntervalSeconds -lt 10) { throw "Soak interval must be at least 10 seconds" }
if (-not $Output) { $Output = Join-Path $root "build/benchmarks/soak.jsonl" }
New-Item -ItemType Directory -Path (Split-Path $Output -Parent) -Force | Out-Null

$pid = (& adb shell su -c "cat /data/adb/akihalink/runtime/core.pid" 2>$null).Trim()
if (-not $pid) { throw "The proxy core is not running" }
& adb forward "tcp:$DebugPort" tcp:6060 | Out-Null
$deadline = [DateTimeOffset]::UtcNow.AddHours($DurationHours)
try {
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $currentPid = (& adb shell su -c "cat /data/adb/akihalink/runtime/core.pid" 2>$null).Trim()
        if ($currentPid -ne $pid) { throw "Core restarted during soak test" }
        $rss = ((& adb shell su -c "grep VmRSS /proc/$pid/status" 2>$null) -replace '[^0-9]', '')
        $fds = (& adb shell su -c "find /proc/$pid/fd -mindepth 1 -maxdepth 1 2>/dev/null | wc -l").Trim()
        $maps = (& adb shell su -c "find /sys/fs/bpf/akihalink -type f 2>/dev/null | wc -l").Trim()
        $goroutines = 0
        try {
            $goroutineDump = (Invoke-WebRequest `
                -Uri "http://127.0.0.1:$DebugPort/debug/pprof/goroutine?debug=1" `
                -UseBasicParsing `
                -TimeoutSec 5).Content
            if ($goroutineDump -match 'goroutine profile: total (\d+)') {
                $goroutines = [int]$Matches[1]
            }
        } catch {
            throw "The temporary loopback soak-test debug endpoint is unavailable"
        }
        $sampleJson = ([ordered]@{
            timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
            pid = [int]$pid
            rssKb = [long]$rss
            fdCount = [int]$fds
            bpfPinnedObjects = [int]$maps
            goroutines = $goroutines
        } | ConvertTo-Json -Compress) + "`n"
        [IO.File]::AppendAllText($Output, $sampleJson, [Text.UTF8Encoding]::new($false))
        Start-Sleep -Seconds $IntervalSeconds
    }
} finally {
    & adb forward --remove "tcp:$DebugPort" 2>$null
}

$samples = @(Get-Content -LiteralPath $Output | ForEach-Object { $_ | ConvertFrom-Json })
$window = [Math]::Max(3, [int]($samples.Count / 10))
$first = @($samples | Select-Object -First $window)
$last = @($samples | Select-Object -Last $window)
foreach ($metric in @("rssKb", "fdCount", "bpfPinnedObjects", "goroutines")) {
    $before = ($first.$metric | Measure-Object -Average).Average
    $after = ($last.$metric | Measure-Object -Average).Average
    if ($after -gt $before * 1.10 + 2) {
        throw "Sustained $metric growth exceeded the soak gate: $before -> $after"
    }
}
Write-Host "24-hour leak gate passed: $Output"
