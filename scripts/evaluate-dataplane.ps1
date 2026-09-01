param(
    [Parameter(Mandatory = $true)] [string]$Baseline,
    [Parameter(Mandatory = $true)] [string]$Candidate,
    [Parameter(Mandatory = $true)] [string]$Output,
    [Parameter(Mandatory = $true)] [string]$BaselineCore,
    [Parameter(Mandatory = $true)] [string]$CandidateCore
)

$ErrorActionPreference = "Stop"
function Read-Records([string]$path) {
    @(Get-Content -LiteralPath $path | ForEach-Object { $_ | ConvertFrom-Json })
}
function Median([double[]]$values) {
    $sorted = @($values | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    if ($sorted.Count % 2) { return $sorted[[int]($sorted.Count / 2)] }
    ($sorted[$sorted.Count / 2 - 1] + $sorted[$sorted.Count / 2]) / 2
}
$baselineRecords = Read-Records $Baseline
$candidateRecords = Read-Records $Candidate
$workloads = @($baselineRecords | ForEach-Object { "$($_.transport):$($_.metric.workload)" } | Sort-Object -Unique)
$results = foreach ($key in $workloads) {
    $transport, $workload = $key.Split(':', 2)
    $baselineSet = @($baselineRecords | Where-Object { $_.transport -eq $transport -and $_.metric.workload -eq $workload })
    $candidateSet = @($candidateRecords | Where-Object { $_.transport -eq $transport -and $_.metric.workload -eq $workload })
    if ($baselineSet.Count -lt 7 -or $candidateSet.Count -lt 7) {
        throw "Each transport/workload requires at least seven samples: $key"
    }
    $metricName = if ($workload -eq "udp_1200") { "pps" } elseif ($workload -eq "tcp_connect") { "p95Millis" } else { "bitsPerSecond" }
    $before = Median @($baselineSet | ForEach-Object { [double]$_.metric.$metricName })
    $after = Median @($candidateSet | ForEach-Object { [double]$_.metric.$metricName })
    $change = if ($before -eq 0) { 0 } elseif ($workload -eq "tcp_connect") { ($before - $after) * 100 / $before } else { ($after - $before) * 100 / $before }
    $lossBefore = if ($workload -eq "udp_1200") { Median @($baselineSet.metric.lossPercent) } else { 0 }
    $lossAfter = if ($workload -eq "udp_1200") { Median @($candidateSet.metric.lossPercent) } else { 0 }
    $cpuBefore = Median @($baselineSet.coreCpuTicksPerBit)
    $cpuAfter = Median @($candidateSet.coreCpuTicksPerBit)
    $cpuImprovement = if ($cpuBefore -eq 0) { 0 } else { ($cpuBefore - $cpuAfter) * 100 / $cpuBefore }
    [ordered]@{
        transport = $transport
        workload = $workload
        metric = $metricName
        baselineMedian = $before
        candidateMedian = $after
        changePercent = $change
        lossDeltaPoints = $lossAfter - $lossBefore
        cpuPerBitImprovementPercent = $cpuImprovement
        throughputGate = if ($workload -eq "tcp_connect") { ($change -ge -5) } else { ($change -ge -3) }
        lossGate = ($lossAfter - $lossBefore -le 0.1)
    }
}
$baselineCoreBytes = (Get-Item -LiteralPath $BaselineCore).Length
$candidateCoreBytes = (Get-Item -LiteralPath $CandidateCore).Length
$coreSizeReduction = ($baselineCoreBytes - $candidateCoreBytes) * 100.0 / $baselineCoreBytes
$representativeImprovement = @($results | Where-Object {
    ($_.workload -ne "tcp_connect" -and $_.changePercent -ge 5) -or $_.cpuPerBitImprovementPercent -ge 8
}).Count -gt 0
$report = [ordered]@{
    schemaVersion = 1
    generatedAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    results = @($results)
    coreSizeReductionPercent = $coreSizeReduction
    baselineMaxRssKb = ($baselineRecords.coreRssKb | Measure-Object -Maximum).Maximum
    candidateMaxRssKb = ($candidateRecords.coreRssKb | Measure-Object -Maximum).Maximum
    representativeImprovement = $representativeImprovement
    releaseGatePassed = $representativeImprovement -and $coreSizeReduction -ge 10 -and
        -not (@($results | Where-Object { -not $_.throughputGate -or -not $_.lossGate }).Count)
}
$reportJson = $report | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText($Output, $reportJson, [Text.UTF8Encoding]::new($false))
if (-not $report.releaseGatePassed) { throw "Data-path release regression gate failed" }
