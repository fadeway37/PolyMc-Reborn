# Supply-chain artifacts

`assembleRcArtifacts` creates a bounded release directory containing the
main and standalone API artifacts, CycloneDX 1.5 JSON SBOM, SHA-256/SHA-512
checksums, a local path-free build manifest, API signature, release notes,
license, and notices.
Metadata is generated in a separate intermediate directory before assembly.
The final copy starts from an empty bounded release directory and fails unless
the exact 16-file allow-list is present with every file non-empty.

The SBOM records exact resolved direct/transitive coordinates and available
hashes without local absolute paths, secrets, playtest content Mod artifacts,
or worlds. `verifyReleaseArtifacts` rejects fixture/driver/external-JAR leakage.
`verifyReproducibleArchives` builds main and API archives twice and compares
bytes. All archives disable file timestamps and use reproducible entry order.

Commands:

```text
./gradlew generateSbom generateProvenance generateReleaseChecksums
./gradlew verifyReleaseArtifacts verifyReproducibleArchives
./gradlew assembleRcArtifacts
```

The local `provenance.json` is descriptive build metadata, not a hosted
attestation. The manual `release-rc.yml` uses the official, SHA-pinned
`actions/attest-build-provenance` action for the main JAR, API JAR, SBOM, and
both checksum lists. It then runs `gh attestation verify` for every subject and
a tampered negative check before writing `attestation-verification.json`.
See [artifact-attestation.md](artifact-attestation.md).
