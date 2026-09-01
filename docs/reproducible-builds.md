# Rebuilding and verifying AkihaLink 1.1

## Fixed inputs

- Gradle 9.6.1 wrapper with its official distribution SHA-256.
- JDK 21, Go 1.26.6, NDK r29 (29.0.14206865), Android SDK API 36 and Build Tools 36.0.0.
  The native core is compiled with r29's `aarch64-linux-android35-clang`
  wrapper, matching the pinned upstream Android eBPF workflow.
- The OCI image in `release/toolchain.Dockerfile`, published to GHCR and then
  referenced by the `AKIHALINK_RELEASE_IMAGE` repository variable using an
  immutable `@sha256:` digest.
- Host archives and Android compiler inputs pinned in
  `release/toolchain.lock.json`; archive hashes must be verified before use.
- Gradle dependency locks and `gradle/verification-metadata.xml`.
- The sing-box commit and AkihaLink patch hashes in
  `patches/sing-box/*.lock.json`.
- `SOURCE_DATE_EPOCH` equal to the release commit timestamp and `TZ=UTC`.

Run the `Build pinned release toolchain` workflow after intentionally changing
the Dockerfile. Copy the printed digest reference into
`AKIHALINK_RELEASE_IMAGE`; never set that variable to a tag.

Before creating a release tag, complete `docs/device-acceptance.md` and set the
repository variable `AKIHALINK_DEVICE_MATRIX_ATTESTATION` to
`90bb3d43634b56b38834a239f3129d3009ece9d4:akihalink-upstream-ebpf-v16:1.1`.
The trusted release stays blocked unless the attestation matches the exact
core commit, patch set, and release version. Its verifier job independently
loads and attaches the NDK r29-generated cgroup and shared-network programs on
the Ubuntu runner kernel before any release artifact is published.

## Rebuild the public reproducible artifacts

From the v1.1 source commit inside the pinned toolchain image:

```bash
export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"
export TZ=UTC
pwsh ./scripts/build-release-candidate.ps1 \
  -Output "$PWD/build/rebuild" \
  -Ndk "$ANDROID_NDK_HOME" \
  -Version 1.1 \
  -SourceDateEpoch "$SOURCE_DATE_EPOCH"
sha256sum \
  build/rebuild/AkihaLink-1.1-unsigned.apk \
  build/rebuild/AkihaLink-KSU-1.1.zip \
  build/rebuild/sing-box-1.1-android-arm64 \
  build/rebuild/AkihaLink-1.1-source.tar.gz
```

Compare these four hashes with `reproducibility.json` and `SHA256SUMS`.
The signed APK is intentionally not an external reproducibility target because
the signing key is not public.

## Verify the release

```bash
sha256sum --check SHA256SUMS

gh attestation verify AkihaLink-1.1.apk --repo OWNER/AkihaLink
gh attestation verify AkihaLink-1.1-unsigned.apk --repo OWNER/AkihaLink
gh attestation verify AkihaLink-KSU-1.1.zip --repo OWNER/AkihaLink
gh attestation verify sing-box-1.1-android-arm64 --repo OWNER/AkihaLink
gh attestation verify AkihaLink-1.1-source.tar.gz --repo OWNER/AkihaLink

cosign verify-blob \
  --bundle SHA256SUMS.sigstore.json \
  --certificate-identity \
  "https://github.com/OWNER/AkihaLink/.github/workflows/release.yml@refs/tags/v1.1" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  SHA256SUMS

"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify \
  --verbose --print-certs AkihaLink-1.1.apk
```

The APK signer certificate SHA-256 must exactly equal
`release/signing-certificate.sha256`. The workflow also verifies the Cosign
certificate identity, GitHub OIDC issuer, transparency-log-backed bundle, APK
signature and attachment list before changing the GitHub Release from draft to
published.

GitHub artifact attestations and the exported Sigstore bundle prove the
workflow/source relationship. This project does not claim SLSA Build Level 3.
