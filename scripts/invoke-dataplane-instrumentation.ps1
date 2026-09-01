param(
    [Parameter(Mandatory = $true)] [string]$Class,
    [Parameter(Mandatory = $true)] [string]$ServerHost,
    [Parameter(Mandatory = $true)] [int]$TcpPort,
    [Parameter(Mandatory = $true)] [int]$UdpPort,
    [Parameter(Mandatory = $true)] [int]$DurationSeconds,
    [Parameter(Mandatory = $true)] [int]$Concurrency,
    [int]$TimeoutSeconds = 0
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source
$package = "com.akiha.akihalink.dataplanebenchmark"
$component = "$package/.DataPlaneInstrumentation"
if ($TimeoutSeconds -le 0) { $TimeoutSeconds = $DurationSeconds + 30 }

# OEM process freezers may freeze a headless instrumentation target even while
# am instrument is waiting. These exemptions affect only the disposable test
# package, and sticky unfreeze is limited to the spawned process lifetime.
$savedPreference = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
& $adb shell cmd deviceidle whitelist "+$package" 2>$null | Out-Null
& $adb shell cmd activity set-bg-restriction-level $package exempted 2>$null | Out-Null
& $adb shell cmd activity set-inactive $package false 2>$null | Out-Null
& $adb shell cmd activity set-standby-bucket $package active 2>$null | Out-Null
$ErrorActionPreference = $savedPreference

$job = Start-Job -ScriptBlock {
    param($Adb, $Component, $TestClass, $HostName, $Tcp, $Udp, $Duration, $Flows)
    $output = & $Adb shell am instrument -w `
        -e class $TestClass `
        -e serverHost $HostName `
        -e tcpPort $Tcp `
        -e udpPort $Udp `
        -e durationSeconds $Duration `
        -e concurrency $Flows `
        $Component 2>&1
    [pscustomobject]@{
        exitCode = $LASTEXITCODE
        output = ($output -join "`n")
    }
} -ArgumentList $adb, $component, $Class, $ServerHost, $TcpPort, $UdpPort, $DurationSeconds, $Concurrency

try {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ($job.State -eq "Running" -and [DateTime]::UtcNow -lt $deadline) {
        $savedPreference = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        & $adb shell cmd activity unfreeze --sticky $package 2>$null | Out-Null
        $ErrorActionPreference = $savedPreference
        Start-Sleep -Milliseconds 500
    }
    $completed = Wait-Job $job -Timeout 5
    if (-not $completed -or $job.State -ne "Completed") {
        throw "Data-plane instrumentation timed out: $Class"
    }
    $result = Receive-Job $job
    if ($result.exitCode -ne 0) {
        throw "Data-plane instrumentation failed: $Class`n$($result.output)"
    }
    if ($result.output -notmatch '(?m)^INSTRUMENTATION_CODE: -1\s*$') {
        throw "Data-plane instrumentation reported a failed result: $Class`n$($result.output)"
    }
} finally {
    Stop-Job $job -ErrorAction SilentlyContinue
    Remove-Job $job -Force -ErrorAction SilentlyContinue
}
exit 0
