param(
    [Parameter(Mandatory = $true)]
    [string]$GeneratedOutput
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path -LiteralPath $GeneratedOutput)) {
    throw "Missing generated profile output: $GeneratedOutput"
}

function Merge-ProfileRules([string]$Pattern, [string]$Destination) {
    $files = Get-ChildItem -LiteralPath $GeneratedOutput -Recurse -File -Filter $Pattern |
        Sort-Object FullName
    if (-not $files) { throw "No generated profile files matched $Pattern" }
    $rules = $files |
        ForEach-Object { Get-Content -LiteralPath $_.FullName } |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith("#") } |
        Sort-Object -Unique
    if (-not $rules) { throw "Generated profile $Pattern contained no rules" }
    New-Item -ItemType Directory -Path (Split-Path $Destination -Parent) -Force | Out-Null
    [IO.File]::WriteAllText(
        $Destination,
        (($rules -join "`n") + "`n"),
        [Text.UTF8Encoding]::new($false)
    )
}

$destination = Join-Path $root "app/src/main/baselineProfiles"
Merge-ProfileRules "*-baseline-prof.txt" (Join-Path $destination "baseline-prof.txt")
Merge-ProfileRules "*-startup-prof.txt" (Join-Path $destination "startup-prof.txt")
Write-Host "Updated Baseline and Startup Profiles"
