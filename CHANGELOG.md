# Changelog

## 0.4.0-rc.1+26.1.2 development

### Changed

- Began the Stability and Release Candidate phase from the audited final 0.3
  Beta commit without changing Minecraft, mappings, or dependency versions.
- Creative reverse mapping, packet fallback, and trusted-modded passthrough
  remain fail closed or disabled.
- Isolated soak orchestration now keeps control logs outside nested playtest
  cleanup, uses per-iteration staging, validates dynamic-port/process/file-handle
  cleanup, and retains strict failure evidence on Windows and Linux.
- Server stop now idempotently clears projected GUI, entity, and resource-pack
  sessions; production evidence requires every interactive session count to be
  zero before declaring a run successful.
- Added an immutable 0.4 RC API signature, a classified compatibility check
  against the 0.3 Beta signature, and a real-client gate that runs a Consumer
  compiled only against the published, hash-locked 0.3 API JAR on the RC.
- Added Farmer's Delight Refabricated as a third hash-locked server-only
  compatibility target and exercised its real food behavior from an isolated
  vanilla-protocol client representation.
- Static registry entries from server-only Mods are hidden through Polymer's
  Registry Sync Manipulator. Dynamic registry and recipe-book records are
  filtered only when they reference those exact hidden types; the real server
  registrations and mechanics remain untouched.
- Added a ten-iteration long-soak mode with real repeated GUI resync, entity
  projection, tracking, reconnect, pack, support-bundle, and mapping-dry-run
  operations plus bounded JVM/resource trend evidence.
- Added a manually dispatched RC workflow that checks out an explicit
  candidate ref, builds bounded artifacts, generates GitHub-hosted build
  provenance with the official pinned attestation action, independently
  verifies it, and rejects a one-byte-tampered negative sample.
- Made the 0.3 binary Consumer gate work with least-privilege GitHub Actions:
  it validates the exact audited workflow artifact and published API hash,
  with a hash-identical build from the audited commit as the expiry fallback.
- Preserve the complete sanitized nested client/server evidence when a Soak
  iteration fails before its operation assertions can be materialized.

All notable changes are recorded here. The format follows Keep a Changelog
principles; alpha versions are tied to an exact Minecraft version.

## [Unreleased]

- Run the beta external-mod matrix and cross-platform archive reproducibility
  gates automatically on pushes to the 0.3 beta release branch.
- Normalize generated Javadoc text to LF so main and standalone API Javadoc
  archives reproduce byte-for-byte across Linux and Windows.
- Track resource-pack responses against the current protocol pack UUID and
  make terminal responses idempotent, preventing stale acknowledgements from
  corrupting per-session policy diagnostics.

### 0.3.0-beta.1+26.1.2 development

#### Changed

- Began the Real-World Compatibility Beta from the verified final 0.2 commit.
- Corrected the 0.2 release-evidence references after auditing both successive
  GitHub Actions run pairs and their downloaded artifacts.
- Release metadata is generated outside the final copy destination; Beta
  assembly now deletes stale output and rejects missing, unexpected, or empty
  files against an exact artifact allow-list.

#### Added

- A standalone `polymc-reborn-api` Maven publication with Java 25
  sources/Javadoc artifacts and one canonical source tree shared by the
  production build.
- Stable/experimental/internal Beta annotations, a deterministic API signature
  baseline/check, and a negative signature-change test.
- An independent Maven-coordinate-only API consumer fixture covering provider,
  item, block, GUI, entity, and resource registrations.
- Per-player `REQUIRED`, `OPTIONAL`, and `DISABLED` resource-pack policy state,
  with safe model/block fallbacks for players without the generated pack.
- Explicit entity compositions can declare one vanilla passenger and bounded
  vanilla equipment; the fixture proves both on a real client.
- Explicit furnace projections backed by the real three-slot `Container` and
  four bounded `ContainerData` properties, including progress and Shift-click.
- Strict diagnostic policy rules with protected security categories and a
  bounded, path-sanitized, whitelist-only local support bundle.
- Hash-locked real third-party Mod, two-client, pack-policy, 0.2 upgrade, and
  mod-set expansion playtest gates with structured evidence.
- A five-leg cross-process upgrade harness that runs the audited 0.2 JAR and
  0.3 JAR over one world, then adds, removes, and re-adds an independent Mod
  while checking world/player data, resource packs, and mapping bytes.
- CycloneDX SBOM, SHA-256/SHA-512 checksums, build provenance, reproducibility
  checks, and manually gated Beta draft-release automation.

#### Security

- Resource-pack responses are tracked per player with idempotent terminal-state
  counters and disconnected-session cleanup; OPTIONAL decline and DISABLED
  mode never expose custom model identifiers.
- Non-vanilla data-component types are registered with Polymer registry
  filtering so real server components remain authoritative without leaking
  custom registry entries to vanilla clients.
- Creative reverse mapping still fails startup when enabled. The 0.3 Beta does
  not accept Polymer's unsigned reverse payload or claim a creative-mode path.

### 0.2.0-alpha.1+26.1.2 development

#### Added

- Explicit, initialization-only safe standard-container projection backed by
  the real server `Container`, including bijective slot mapping, interaction
  policy, bounded sessions, disconnect cleanup, and strict resync validation.
- Explicit Polymer Virtual Entity projection with vanilla-surrogate validation,
  lifecycle cleanup, selected metadata synchronization, and guarded use/attack
  callbacks against the real server entity.
- Deterministic per-state mapping for safely resolvable full-cube blocks,
  canonical property keys, stable replay, model dependency validation, and
  stateful block-item support.
- Stable mapping diff reports, no-write dry run, strict status/validation,
  checksummed backups, and restart-only rollback preparation with a safety
  backup.
- A two-process production playtest architecture: a dedicated server loads the
  final official-namespace distribution JAR and a separate real Minecraft client loads only the
  minimal Fabric Client GameTest driver dependencies. Its evidence contract is
  rooted at `build/playtest/`.
- Focused GUI, entity, stateful-block, mapping-operation, isolation, and
  production JAR leakage tests.

#### Changed

- Advanced the development version to `0.2.0-alpha.1+26.1.2` while retaining
  Minecraft 26.1.2, Java 25, official names, and exact dependency pins.
- Expanded semantic item inspection and real-use fixture coverage, including a
  real world drop/pickup round trip, while keeping unsafe custom components filtered.
- Extended compatibility and operational reports for interactive projections,
  per-state mappings, rollback state, and playtest observations.

#### Security

- Native Polymer behavior still wins by default; broad packet cancellation was
  not introduced to make client login succeed.
- GUI mutations remain server-authoritative and reject unsafe encodings;
  entity callbacks validate generation, tracking, liveness, level, distance,
  and finite hit data.
- Mapping rollback validates paths, metadata, size, checksum, schema,
  algorithm, and Minecraft version before staging data for restart.

#### Not included

- Runtime creative reverse mapping, pure zero-mod vanilla-client automation,
  completed third-party mod compatibility results, furnace/property GUI
  projection, automatic entity selection, broad entity metadata/equipment/
  passenger support, and packet transformation remain disabled or roadmap work.

Local verification on 2026-07-18 completed `runClientPlaytest`,
`runProductionClientPlaytest`, and `runPlaytest` successfully. The retained
final bundle reports 53/53 checks, 35/35 client steps, and 17/17 screenshots.
GitHub Actions Client Playtest `29642433900` and standard CI `29642433896`
completed successfully, and their evidence/JAR artifacts were downloaded and
inspected.

## [0.1.0-alpha.1+26.1.2]

- Established the server-only official-name Fabric/Loom build for Minecraft
  26.1.2 and Java 25.
- Added Polymer-first immutable compatibility planning, deterministic mapping
  persistence/resource packs, strict profiles, diagnostics/commands, selected
  legacy source compatibility, JUnit, server GameTest, and dedicated-server
  smoke infrastructure.
