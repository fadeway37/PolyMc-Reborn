# Maintainer workflow

This file contains repository-specific maintenance rules that do not belong in the public installation or Adapter guides. Public development documentation is maintained in [PolyMc-Reborn-Docs](https://polymc-reborn-docs.pages.dev/#/en/development/setup).

## Repository invariants

Use Java 25 and the exact versions in `gradle.properties`. Minecraft names are official; Yarn is not a dependency. Keep backend-neutral public contracts in `io.github.polymcreborn.api` and Polymer implementation types in `backend.polymer`. Common and server initialization must not reference client-only classes.

New Java files use the Reborn SPDX header. Adapted upstream files retain their original notices. Release archives never contain fixtures, client drivers, worlds, local paths, test evidence, downloaded content-mod JARs, or secrets.

## Change workflow

1. Start from clean `main` and use a focused topic branch.
2. Read the relevant ADRs before changing an accepted boundary.
3. Register providers, resources, and projections before freeze; commands do not mutate a frozen plan.
4. Keep persisted and reported output deterministic. Corruption must fail closed.
5. Add positive and negative coverage, update diagnostics and `CHANGELOG.md`, and update the external bilingual documentation when public behavior changes.
6. Run `java -version`, then every applicable check in `AGENTS.md`. Use `gradlew.bat` on Windows.
7. Inspect outputs instead of treating a task or workflow conclusion as proof.

The main source sets are `src/main` for the distributable server mod, `api/src/main` for the standalone API, `src/test` for unit tests, `src/gametest` for internal game fixtures, and `playtest` for isolated external processes. Test-only code and evidence must remain outside release artifacts.

## API compatibility

The standalone coordinate is
`io.github.polymcreborn:polymc-reborn-api:0.4.0-rc.2+26.1.2`. `@Stable`
contracts preserve the accepted Beta baseline through the RC line.
`@Experimental` contracts are public but may change with migration notes;
the explicit GUI and entity APIs currently have this status. `@Internal`
types are not external contracts. The adapted legacy package is a source
migration surface, not binary compatibility with old Minecraft JARs.

Signature baselines under `api/signatures/` change only through intentional
API review. A public API change requires the signature, compatibility, current
consumer, and legacy consumer checks; never update a baseline merely to make a
check pass.

## Evidence boundaries

Unit tests, GameTest, dedicated-server smoke, production client, multi-client, external-mod, upgrade, expansion, soak, reproducibility, and release-artifact checks prove different things. Report each layer separately. A skipped, timed-out, force-terminated, or uninspected layer is not a pass.

Client playtests use isolated test drivers and must not be described as automated pure zero-mod vanilla login. External-mod claims are limited to the exact version, registry IDs, and scenarios recorded by the lock and result.

API changes require signature and compatibility checks plus both current and legacy consumer validation. Mapping changes require migration, backup, restart, determinism, and expansion coverage. Pack, GUI, entity, persistence, or diagnostic changes require their relevant production evidence.

## Release maintenance

Release candidates use `release/<version>` from current `main`. Update version metadata, changelog, release note, signatures, manifests, checksums, SBOM inputs, and workflow expectations together. The workflow-consumed release note for RC2 is `docs/releases/0.4.0-rc.2.md`; do not move or delete it while the workflow references it.

Before tagging or changing a draft release:

- run and inspect the applicable local and hosted gates on the exact candidate;
- verify repository and commit identity in every artifact;
- inspect the main/API JARs, SBOM, checksums, build manifest, API signature, licenses, notices, and provenance;
- confirm the release allow-list excludes test and private material;
- verify positive provenance and the tampered negative;
- keep the release as a draft pre-release until every required layer passes.

If a merge changes commit identity, repeat commit-bound release evidence on final `main`. Never reuse historical provenance as proof for a new repository or commit.

## Git hygiene

Use focused commits and open pull requests only against the active repository. Preserve historical remotes as read-only. Before pushing, inspect `git diff --check`, `git status --short`, tracked build directories, and the intended archive contents.
