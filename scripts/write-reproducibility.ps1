param(
    [Parameter(Mandatory = $true)]
    [string]$Directory,
    [Parameter(Mandatory = $true)]
    [string[]]$Names,
    [Parameter(Mandatory = $true)]
    [long]$SourceDateEpoch,
    [string]$ToolchainImage = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$commit = (& git -C $root rev-parse HEAD).Trim()
$artifacts = foreach ($name in ($Names | Sort-Object)) {
    $path = Join-Path $Directory $name
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing reproducible artifact: $name" }
    [ordered]@{
        name = $name
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        size = (Get-Item -LiteralPath $path).Length
    }
}
$document = [ordered]@{
    schemaVersion = 1
    sourceCommit = $commit
    sourceDateEpoch = $SourceDateEpoch
    timezone = "UTC"
    toolchainImage = $ToolchainImage
    reproducibleTargets = $artifacts
    signedApkReproducible = $false
}
[IO.File]::WriteAllText(
    (Join-Path $Directory "reproducibility.json"),
    (($document | ConvertTo-Json -Depth 5) + "`n"),
    [Text.UTF8Encoding]::new($false)
)
