# Roadmap

## 0.3 Real-World Compatibility Beta

- standalone annotated/signature-checked API and external consumer;
- per-player pack policy, explicit furnace properties, and explicit entity
  passenger/equipment composition;
- two-client, external-Mod, upgrade, and mod-set preservation evidence;
- diagnostic policy/support bundles and Beta supply-chain artifacts.

Pure zero-Mod vanilla automation and authenticated creative reverse mapping are
not complete; creative enablement fails startup. The bounded five-run soak is
not the future full 10,000-tick/stress target.

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
Client Playtest `29642433900` and standard CI `29642433896` also passed; their
downloaded evidence and release-JAR artifacts were inspected.

## Historical 0.2 P1 gaps

- Authenticated, rate-limited runtime creative reverse mapping and real
  creative-slot adversarial playtests.
- A pure zero-mod Minecraft client smoke with no Fabric or driver.
- A pinned, license-reviewed, hash-verified external-mod compatibility matrix.
- Versioned diagnostic suppression/promotion policy.
- Furnace/property GUI projection, paging, server buttons, and dynamic layouts.
- Entity equipment, passengers, leashes, poses/animations, broader metadata,
  and dimension specialization. Projection capacity itself is bounded in 0.2.

These remain fail-closed. No incomplete runtime switch should imply support.

## 0.3 delivery status

- The explicit furnace/property adapter and reviewed equipment/passenger
  composition are implemented behind narrow registries and have dedicated
  production playtest assertions.
- The standalone API Artifact, signature baseline, consumer, diagnostic policy,
  support bundle, external-Mod matrix, upgrade/mod-set harness, SBOM, checksums,
  provenance, and manual Beta workflow are implemented release gates.
- The per-player REQUIRED/OPTIONAL/DISABLED resource-pack policy remains
  presentation-only; it never alters the frozen mapping plan.

## Candidate 0.4 / release-candidate direction

- Design authenticated creative reverse mapping with replay/rate/component
  protection before enabling its packet path.
- Run a legal zero-Mod client smoke with no Fabric or automation driver.
- Expand the license-reviewed external matrix and gather downstream API
  feedback without weakening feature-scoped claims.
- Add hosted artifact attestations/signing after the manual Beta provenance
  workflow has accumulated stable evidence.

## Permanent non-goals

There is no plan to promise compatibility with every mod, infer arbitrary GUI
or entity semantics, emulate arbitrary client renderers, trust Fabric/mod-list
claims as authentication, download/execute remote compatibility code, add broad
packet cancellation, or wholesale-port old PolyMc Mixins. Quilt, NeoForge,
Geyser/Bedrock, and forced companion clients are outside the current release
line.
