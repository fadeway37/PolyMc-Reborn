# Roadmap

This roadmap separates implemented alpha scope from future work. An interface,
fixture, or harness does not by itself prove a release claim.

## 0.1 foundation

- Server-only Fabric/JDK 25 build for exactly Minecraft 26.1.2 using official
  names and pinned dependencies.
- Immutable, explainable provider planning with native Polymer priority.
- Conservative item/simple full-cube overlays, deterministic persistence and
  resource packs, strict profiles, diagnostics/commands, selected legacy source
  compatibility, JUnit, server GameTest, and dedicated-server smoke.
- Unsupported classification for unsafe shapes, entities, and menus; disabled
  packet fallback and creative reverse mapping.

## 0.2 Interactive Compatibility Alpha

Implemented engineering scope:

- isolated two-process production playtest harness and `build/playtest`
  evidence contract;
- explicit standard 9xN container projection with server-authoritative
  inventory transactions;
- explicit Polymer Virtual Entity adapters with guarded use/attack;
- deterministic per-state mappings for safely resolvable full-cube blocks;
- richer semantic item analysis/use coverage;
- mapping diff, dry run, checksum backup, and restart-only rollback staging.

The exact local Client, Production Client, and aggregate Playtest commands
passed on 2026-07-18 and their retained evidence was inspected. GitHub Actions
Client Playtest `29641300974` and standard CI `29641300985` also passed; their
downloaded evidence and release-JAR artifacts were inspected.

## P1 work not completed for 0.2

- Authenticated, rate-limited runtime creative reverse mapping and real
  creative-slot adversarial playtests.
- A pure zero-mod Minecraft client smoke with no Fabric or driver.
- A pinned, license-reviewed, hash-verified external-mod compatibility matrix.
- Versioned diagnostic suppression/promotion policy.
- Furnace/property GUI projection, paging, server buttons, and dynamic layouts.
- Entity equipment, passengers, leashes, poses/animations, broader metadata,
  and dimension specialization. Projection capacity itself is bounded in 0.2.

These remain fail-closed. No incomplete runtime switch should imply support.

## Candidate 0.3 direction

- Add explicit furnace/property adapters only after full transaction/property
  synchronization tests.
- Add reviewed equipment/passenger compositions without generic entity
  guessing while retaining the bounded projection registry.
- Design authenticated creative reverse mapping with replay/rate/component
  protection before enabling its packet path.
- Run a legal zero-mod client smoke and a small pinned external-mod matrix with
  sanitized reproducible artifacts.
- Add diagnostic policy with audit-preserving reason chains.
- Gather downstream feedback before extracting/versioning a separate API JAR.
- Add release provenance/signing and manual release automation.

## Permanent non-goals

There is no plan to promise compatibility with every mod, infer arbitrary GUI
or entity semantics, emulate arbitrary client renderers, trust Fabric/mod-list
claims as authentication, download/execute remote compatibility code, add broad
packet cancellation, or wholesale-port old PolyMc Mixins. Quilt, NeoForge,
Geyser/Bedrock, and forced companion clients are outside the current release
line.
