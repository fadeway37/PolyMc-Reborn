# ADR 0013: Beta release provenance

Status: accepted for 0.3 Beta

## Decision

Publish reproducible main/API binary, sources, and Javadoc archives plus a
CycloneDX SBOM, SHA-256/SHA-512 checksums, provenance, API signature, license,
and notices. Reject fixture, driver, world, evidence, external Mod JAR, secret,
or absolute-path leakage.

Beta tagging and Draft Release creation are manual workflow actions that occur
only after every P0 gate succeeds. Normal pushes never publish a release.

## Consequences

Any failed/missing P0 gate leaves only the development branch; no tag or draft
is created. Official GitHub Actions are pinned to commit SHAs.
