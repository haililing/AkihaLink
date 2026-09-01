param(
    [string]$Directory = "",
    [string[]]$Include = @()
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $Directory) { $Directory = Join-Path $root "build/outputs" }
$files = Get-ChildItem $Directory -File |
    Where-Object {
        $_.Name -ne "SHA256SUMS" -and
        ($Include.Count -eq 0 -or $_.Name -in $Include)
    } |
    Sort-Object Name
$lines = foreach ($file in $files) {
    $hash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($file.Name)"
}
[System.IO.File]::WriteAllLines((Join-Path $Directory "SHA256SUMS"), $lines, [System.Text.UTF8Encoding]::new($false))
Write-Host "Wrote $(Join-Path $Directory 'SHA256SUMS')"
