param(
    [Parameter(Mandatory = $true)]
    [string]$Directory,
    [Parameter(Mandatory = $true)]
    [string]$ReadElf
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $ReadElf)) { throw "NDK llvm-readelf is unavailable: $ReadElf" }

$root = Split-Path $PSScriptRoot -Parent
$schemas = (Get-Content (Join-Path $root "patches/sing-box/bpf-objects.lock.json") -Raw | ConvertFrom-Json).objects
if ($schemas.Count -ne 6) { throw "Expected six locked eBPF object families" }

foreach ($schema in $schemas) {
    foreach ($endian in @("bpfel", "bpfeb")) {
        $fileName = "$($schema.Stem)_$endian.o"
        $path = Join-Path $Directory $fileName
        if (-not (Test-Path -LiteralPath $path) -or (Get-Item -LiteralPath $path).Length -eq 0) {
            throw "Missing generated BPF object: $path"
        }
        $header = (& $ReadElf -h $path) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $header -notmatch 'ELF64' -or $header -notmatch 'EM_BPF') {
            throw "$fileName is not an ELF64 Linux BPF object"
        }
        $sections = (& $ReadElf -SW $path) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Failed to inspect sections in $fileName" }
        foreach ($section in $schema.Sections) {
            if ($sections -notmatch "(?m)\s$([regex]::Escape($section))\s") {
                throw "$fileName is missing section $section"
            }
        }
        if ($sections -match '(?m)\s\.BTF(?:\.ext)?\s') {
            throw "$fileName unexpectedly retains BTF; main data-plane objects must be kernel-version independent"
        }
        $symbols = (& $ReadElf -sW $path) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Failed to inspect symbols in $fileName" }
        foreach ($symbol in $schema.Symbols) {
            if ($symbols -notmatch "(?m)\s$([regex]::Escape($symbol))$") {
                throw "$fileName is missing map symbol $symbol"
            }
        }
    }
}

$manifestPath = Join-Path $Directory "manifest.txt"
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Missing generated BPF manifest: $manifestPath" }
$manifest = Get-Content -LiteralPath $manifestPath -Raw
if ($manifest -notmatch '(?m)^targets: bpfel,bpfeb\r?$' -or
    $manifest -notmatch '(?m)^bpf2go: v0\.22\.1-0\.20260724091036-00feb08ae4e5\r?$') {
    throw "BPF manifest does not record the locked targets and cilium/ebpf generator"
}
foreach ($schema in $schemas) {
    foreach ($endian in @("bpfel", "bpfeb")) {
        $object = "$($schema.Stem)_$endian.o"
        if ($manifest -notmatch "(?m)^[0-9a-f]{64}\s+internal/bpfgen/$([regex]::Escape($object))\r?$") {
            throw "BPF manifest is missing the SHA-256 entry for $object"
        }
    }
}

Write-Host "Verified all six locked 1.15 eBPF object schemas"
