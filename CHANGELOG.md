# Changelog

All notable changes are recorded here. The format follows Keep a Changelog
principles; alpha versions are tied to an exact Minecraft version.

## [Unreleased]

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
- Expanded semantic item inspection and real-use fixture coverage while keeping
  unsafe custom components filtered.
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
final bundle reports 53/53 checks, 34/34 client steps, and 17/17 screenshots.
GitHub Actions verification remains a separate result until a concrete run ID
and downloaded artifact have been inspected.

## [0.1.0-alpha.1+26.1.2]

- Established the server-only official-name Fabric/Loom build for Minecraft
  26.1.2 and Java 25.
- Added Polymer-first immutable compatibility planning, deterministic mapping
  persistence/resource packs, strict profiles, diagnostics/commands, selected
  legacy source compatibility, JUnit, server GameTest, and dedicated-server
  smoke infrastructure.
