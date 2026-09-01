param(
    [switch]$Download
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$lock = Get-Content (Join-Path $root "rules.lock.json") -Raw | ConvertFrom-Json
$rulesDir = Join-Path $root "module/rules"
New-Item -ItemType Directory -Path $rulesDir -Force | Out-Null

if ($lock.schemaVersion -ne 2 -or -not $lock.sources) {
    throw "Unsupported or incomplete rules.lock.json schema"
}

$expectedFiles = @()
foreach ($source in $lock.sources) {
    if ($source.commit -notmatch '^[0-9a-f]{40}$') {
        throw "Rule source commit is not pinned: $($source.repository)"
    }
    foreach ($file in $source.files) {
        if ($file.url -notmatch [regex]::Escape($source.commit)) {
            throw "Rule URL is not pinned to $($source.commit): $($file.name)"
        }
        if ($file.sha256 -notmatch '^[0-9a-f]{64}$') {
            throw "Rule SHA-256 is invalid: $($file.name)"
        }
        $expectedFiles += $file.name
        $path = Join-Path $rulesDir $file.name
        if ($Download -or -not (Test-Path $path)) {
            $temporary = "$path.download"
            & curl.exe -L --fail --retry 3 -o $temporary $file.url
            if ($LASTEXITCODE -ne 0) { throw "Failed to download $($file.name)" }
            Move-Item -Force $temporary $path
        }
        $actual = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $file.sha256) {
            throw "SHA-256 mismatch for $($file.name): expected $($file.sha256), got $actual"
        }
        Write-Host "Verified $($file.name) $actual"
    }
}

if (($expectedFiles | Sort-Object -Unique).Count -ne $expectedFiles.Count) {
    throw "rules.lock.json contains duplicate destination names"
}
