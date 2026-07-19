# Supply-chain artifacts

`assembleBetaArtifacts` creates a bounded release directory containing the
main and standalone API artifacts, CycloneDX 1.5 JSON SBOM, SHA-256/SHA-512
checksums, build provenance, API signature, license, and notices.

The SBOM records exact resolved direct/transitive coordinates and available
hashes without local absolute paths, secrets, playtest content Mod artifacts,
or worlds. `verifyReleaseArtifacts` rejects fixture/driver/external-JAR leakage.
`verifyReproducibleArchives` builds main and API archives twice and compares
bytes. All archives disable file timestamps and use reproducible entry order.

Commands:

```text
./gradlew generateSbom generateProvenance generateReleaseChecksums
./gradlew verifyReleaseArtifacts verifyReproducibleArchives
./gradlew assembleBetaArtifacts
```
