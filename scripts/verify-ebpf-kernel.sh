#!/usr/bin/env bash
# Run on a disposable Linux CI runner, after build-core.ps1 regenerates BPF.
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="${1:-$root/build/core-source}"
work="$(mktemp -d "${RUNNER_TEMP:-/tmp}/akihalink-verifier.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT
cd "$source_dir"

go test -c -tags with_ebpf,ebpf_integration -o "$work/common" ./common/ebpf
go test -c -tags with_ebpf,ebpf_integration -o "$work/protocol" ./protocol/ebpf

run_required() {
  local binary="$1" name="$2" log="$work/$2.log"
  # A renamed/removed test must fail before privileged execution.
  "$binary" -test.list "^$name$" | grep -Fxq "$name"
  sudo env SING_BOX_EBPF_INTEGRATION=1 "$binary" \
    -test.v -test.count=1 -test.run "^$name$" 2>&1 | tee "$log"
  grep -Fq -- "--- PASS: $name " "$log"
  if grep -Fq -- '--- SKIP:' "$log"; then
    echo "Required kernel coverage was skipped: $name" >&2
    return 1
  fi
}

for name in \
  TestCgroupProgramMatrixIntegration \
  TestTCProgramRunIntegration \
  TestTCIPv6PathIsolationIntegration \
  TestTCFragmentPolicyIntegration \
  TestMapBatchIntegration \
  TestUIDPolicyMapTransactionIntegration \
  TestUIDPolicyMapCapacityFailureRollsBackIntegration; do
  run_required "$work/common" "$name"
done

for name in \
  TestFakeIPICMPSharedRewriteAnswersARealClientPing \
  TestFakeIPICMPSharedRewriteAnswersARealIPv6ClientPing \
  TestFakeIPICMPSharedRewriteIgnoresNonICMPToFakeIPTarget; do
  run_required "$work/protocol" "$name"
done
