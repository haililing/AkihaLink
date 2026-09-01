param(
    [Parameter(Mandatory = $true)]
    [string]$First,
    [Parameter(Mandatory = $true)]
    [string]$Second,
    [Parameter(Mandatory = $true)]
    [string[]]$Names,
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $First)) { throw "Missing first build directory: $First" }
if (-not (Test-Path -LiteralPath $Second)) { throw "Missing second build directory: $Second" }
if ($Output) { New-Item -ItemType Directory -Path $Output -Force | Out-Null }

foreach ($name in $Names) {
    $firstPath = Join-Path $First $name
    $secondPath = Join-Path $Second $name
    if (-not (Test-Path -LiteralPath $firstPath)) { throw "First build is missing $name" }
    if (-not (Test-Path -LiteralPath $secondPath)) { throw "Second build is missing $name" }
    $firstHash = (Get-FileHash -LiteralPath $firstPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $secondHash = (Get-FileHash -LiteralPath $secondPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($firstHash -ne $secondHash) {
        throw "Reproducibility failure for ${name}: $firstHash != $secondHash"
    }
    if ($Output) { Copy-Item -LiteralPath $firstPath -Destination (Join-Path $Output $name) -Force }
    Write-Host "$name $firstHash"
}
