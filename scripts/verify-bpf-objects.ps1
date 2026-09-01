param(
    [Parameter(Mandatory = $true)]
    [string]$Directory,
    [Parameter(Mandatory = $true)]
    [string]$ReadElf
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $ReadElf)) { throw "NDK llvm-readelf is unavailable: $ReadElf" }

$schemas = @(
    @{
        Stem = "cgroup"
        Sections = @("maps", "cgroup/connect4_tgid", "cgroup/sendmsg4_tgid", "cgroup/recvmsg4", "cgroup/sock_release_tgid")
        Symbols = @(
            "cgroup_control", "cgroup_stats", "cgroup_tcp_redirect", "cgroup_udp_redirect",
            "cgroup_udp_recovery", "cgroup_udp_token", "cgroup_udp_peer", "cgroup_udp_flow",
            "cgroup_socket_bypass", "cgroup_uid_policy", "cgroup_system_resolver_tgid",
            "cgroup_bypass_ipv4", "cgroup_bypass_ipv6", "cgroup_host_ipv4", "cgroup_host_ipv6",
            "cgroup_ipv6_available"
        )
    },
    @{
        Stem = "shared_network"
        Sections = @("maps", "classifier/ingress", "classifier/egress")
        Symbols = @(
            "shared_control", "shared_stats", "shared_flow_by_original", "shared_bypass_flow",
            "shared_flow_by_token", "shared_listener_sockets", "shared_assign_metadata", "shared_fragment",
            "shared_host_ipv4", "shared_host_ipv6", "shared_include_source_ipv4", "shared_include_source_ipv6",
            "shared_exclude_source_ipv4", "shared_exclude_source_ipv6", "shared_include_source_mac",
            "shared_exclude_source_mac", "shared_bypass_ipv4", "shared_bypass_ipv6", "shared_scratch"
        )
    },
    @{
        Stem = "splice"
        Sections = @("maps", "sk_skb/stream_parser", "sk_skb/stream_verdict")
        Symbols = @("splice_sockets", "splice_peers", "splice_stats")
    }
)

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

Write-Host "Verified cgroup, shared-network, and splice bpf2go object schemas"
