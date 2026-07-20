# Release process

Release candidates start from the current `main` branch and use
`release/<version>` until their pull request is merged. Ordinary development
never targets the historical Archive or `TheEpicBlock/PolyMc`.

## Candidate preparation

1. Confirm a clean worktree and the intended baseline commit.
2. Update project, API, Fabric/POM, workflow, SBOM, build-manifest, checksum,
   signature-header, changelog, and release-note version metadata together.
3. Prove that runtime Java, Mixins, Access Wideners, mapping schema/algorithm,
   stable API descriptors, and behavioral defaults have the expected delta.
4. Run all applicable local commands in `AGENTS.md` and `docs/testing.md`.
5. Inspect the main/API archives, Fabric metadata, manifests, SBOM, checksum
   files, dependency locks, and tracked-input state.
6. Push the release branch and open a pull request against `main`.

The candidate pull request records scope, compatibility impact, tests actually
run, documentation changes, and security boundaries. Full logs, private paths,
worlds, downloaded third-party mod JARs, and local evidence remain outside the
repository.

## Hosted gates

Standard CI, the production client playtest, production multi-client playtest,
external-mod matrix, cross-platform reproducibility, short soak, and long soak
must execute in the independent repository on the exact candidate. A workflow
conclusion alone is insufficient: download its artifacts and validate commit,
repository, reports, JUnit totals, scenario counts, loaded-mod lists,
screenshots, process exits, cleanup state, archive hashes, and content
allow-lists.

The manual `.github/workflows/release-rc.yml` workflow accepts an exact
`release_ref`, an exact `expected_version`, and `create_draft_release`. Its gate
job runs the build/API/server, legacy and current API consumers, single- and
multi-client tests, pack policy, external matrix, upgrade/expansion, Linux
short/long soak, reproducibility, SBOM, checksums, and bounded artifact audit.

Run the first final-candidate dispatch with `create_draft_release=false`. The
second job creates GitHub-hosted provenance for the main Mod JAR, API JAR,
CycloneDX SBOM, `SHA256SUMS`, and `SHA512SUMS`; verifies every subject against
the current repository; and requires a one-byte-tampered JAR to fail. Download
and inspect the final attested artifact set.

## Merge and finalization

After all P0 evidence passes:

1. Merge the candidate pull request using the repository's linear-history
   policy and delete its remote head branch.
2. Confirm that `main` is clean and points to the reviewed candidate commit.
3. Re-run the release gate on that exact `main` commit if the merge strategy
   changed the commit identity.
4. Create the exact annotated release tag on final `main`.
5. Create a Draft Pre-release with the bounded main/API binaries, sources,
   Javadocs, SBOM, checksum manifests, API signature, release notes, licenses,
   notices, build metadata, and provenance-verification record.
6. Verify every uploaded asset size and digest. Keep the release Draft and
   Pre-release; do not publish a stable release from this process.

Only after the independent repository, final artifacts, and provenance are
verified may the historical repository be switched to GitHub's read-only
archive state. Never delete or rewrite its historical releases, tags, pull
requests, branches, or upstream fork relationship.
