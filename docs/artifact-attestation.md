# GitHub Artifact Attestation

The registered manual `.github/workflows/release-rc.yml` workflow uses the
official `actions/attest-build-provenance` action pinned to commit
`0f67c3f4856b2e3261c31976d6725780e5e4c373` (v4.1.1). Its attestation job has
only `contents: write`, `id-token: write`, and `attestations: write`; the long
gate job remains read-only.

Provenance subjects are the main Mod JAR, standalone API JAR, CycloneDX SBOM,
`SHA256SUMS`, and `SHA512SUMS`. The workflow must run from the candidate branch
or tag and asserts that `GITHUB_SHA`, the checked-out commit, and the explicit
`release_ref` all resolve to the same commit.

After GitHub stores the attestations, the workflow runs `gh attestation verify`
for every subject with the repository identity constraint. It then appends one
byte to a temporary JAR and requires verification to fail. Only real CLI output
is converted into `attestation-verification.json`; the project does not create
or imitate a provenance statement locally.

For a downloaded asset, operators can run:

```text
gh attestation verify <asset> --repo fadeway37/PolyMc-Reborn
```

An attestation proves build provenance and subject digest. It does not prove
runtime compatibility with an arbitrary Mod.
