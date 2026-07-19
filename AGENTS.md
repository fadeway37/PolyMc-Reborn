# Contributor and agent guide

This file defines repository-wide implementation rules for PolyMc Reborn.
Apply it together with `README.md` and accepted decisions in `docs/adr/`.

## Product invariant

The server keeps the real modded registry object and server behavior. A client
representation is an overlay, mapping, virtual presentation, or serialization
transform only. Never replace a real server registration with a vanilla
stand-in. Never describe the project as compatible with every mod.

Target exactly Minecraft 26.1.2, Fabric Loader 0.19.3, Java 25, official
Minecraft names, and the exact dependency pins in `gradle.properties`. Do not
add Yarn, intermediary-named source, dynamic versions, `flatDir`, vendored
dependency JARs, unrelated Maven mirrors, Lombok, or runtime code downloads.

## Architecture and package boundaries

The normal compatibility flow is:

```text
stable registry discovery
  -> ContentDescriptor collection
  -> ordered CompatibilityProvider candidates
  -> immutable MappingPlan
  -> CompatibilityBackend application
  -> diagnostics and reports
```

Package responsibilities:

- `io.github.polymcreborn.api`: backend-neutral extension API and stable data
  contracts; do not expose Polymer types.
- `io.github.polymcreborn.core`: lifecycle, discovery, plan freezing, startup
  guards, and server orchestration.
- `io.github.polymcreborn.backend`: generic backend SPI.
- `io.github.polymcreborn.backend.polymer`: direct Polymer integration only.
- `io.github.polymcreborn.compat`: providers, deterministic candidate ordering,
  and validated compatibility-profile decisions.
- `io.github.polymcreborn.config`: strict decoding/validation; never load
  arbitrary classes or run scripts.
- `io.github.polymcreborn.mapping`: immutable decisions, deterministic state
  allocation, strict persistence, diff, backup, and restart-only rollback.
- `io.github.polymcreborn.pack`: normalized resource discovery, dependency
  traversal, deterministic output, hashing, and manifests.
- `io.github.polymcreborn.diagnostics`: structured diagnostics, statistics,
  and path-sanitized reports.
- `io.github.polymcreborn.command`: Brigadier inspection, pack, validation, and
  mapping-operations commands.
- `io.github.polymcreborn.legacy`: adapters into the new planning API.
- `io.github.theepicblock.polymc.api`: only deliberately ported legacy source-
  compatibility types; preserve upstream copyright/license headers.

No common/server initializer may reference client-only Minecraft classes.
Hot-path lookups use precomputed immutable maps. Do not scan registries per
tick, read files during packet conversion, or use reflection in packet paths.

## Interactive compatibility boundary

The 0.2 APIs are deliberately narrow:

- GUI projection means an explicit adapter to a standard vanilla 9xN container
  backed directly by the real server `Container`. Never infer an arbitrary menu
  from slot count or copy its inventory into a second authority.
- Entity projection means an explicit Polymer Virtual Entity adapter with a
  registered vanilla surrogate and guarded callbacks. Never choose a vaguely
  similar entity automatically or spawn a real replacement entity.
- Stateful automatic blocks remain complete cubes whose per-state variants and
  resource dependencies resolve deterministically. Unsafe shapes, ambiguous or
  multipart resources, and unmodelled block-entity rendering fail closed.
- Semantic item carriers are selected before players join and remain fixed;
  unsupported custom client components are filtered.

Native Polymer behavior wins unless an administrator profile explicitly
requests override and `override_native_polymer` is enabled. Provider ordering
changes require an ADR, deterministic tests, and migration notes.

## Lifecycle and mutability

Provider/adapter registration happens during initialization. Discovery sorts
canonical IDs before planning. A narrow registry-freeze hook finalizes the
static plan while Polymer can still mark server-only entries; server startup
may finish component-aware validation without writing registries. Commands do
not mutate the frozen plan, and mapping-affecting configuration requires a
restart.

Mapping diff, dry-run, and validation are read-only. Backup writes only below
the validated backup root. Rollback validates the selected backup and writes a
pending data/metadata pair; it becomes active before planning on the next
startup. Never hot-swap mappings beneath live Polymer overlays.

## Adding a CompatibilityProvider

1. Implement the backend-neutral contract and give the provider a stable ID and
   explicit priority tier; never depend on insertion or `HashMap` order.
2. Return an explainable candidate, not a mutation. Include strategy,
   confidence, degradation, reasons, resources, warnings, and failures.
3. Refuse content that cannot be modelled safely. Prefer `UNSUPPORTED` over an
   attractive but behaviorally unsafe projection.
4. Register through the `polymc-reborn` entrypoint before freeze.
5. Test priority, native preservation, stable ordering, capacity, persistence,
   reports, and positive/negative content cases.
6. Update focused documentation and `CHANGELOG.md` for observable behavior.

## Adding a declarative compatibility profile

1. Start from the example/JSON Schema in `docs/compat-profile-schema.md`.
2. Use a stable unique ID, schema version, target mod/version condition,
   explicit priority, and narrow rules.
3. Prefer exact IDs. Safe globs are allowed; regular expressions, scripts,
   class names to instantiate, shell commands, and remote code are forbidden.
4. A native override is inert unless both the rule and dangerous global switch
   authorize it, and must remain visible in decision traces.
5. Run schema/config tests and validate through the administrator command.
   Validation never hot-applies a profile.

## Build and test commands

Run Java first:

```text
java -version
./gradlew javaToolchains
./gradlew clean build
./gradlew test
./gradlew runGameTest
./gradlew runDedicatedServerSmoke
./gradlew verifyPlaytestClientIsolation
./gradlew runClientPlaytest
./gradlew runProductionClientPlaytest
./gradlew runPlaytest
./gradlew checkApiSignature
./gradlew buildApiConsumer
./gradlew runApiConsumerPlaytest
./gradlew runProductionMultiClientPlaytest
./gradlew runPackPolicyPlaytest
./gradlew runUpgradePlaytest
./gradlew runModSetExpansionPlaytest
./gradlew runExternalModMatrix
./gradlew assembleBetaArtifacts verifyReleaseArtifacts verifyReproducibleArchives
./gradlew dependencies
git diff --check
```

Use `gradlew.bat` on Windows. Name the exact layer in reports: JUnit, server
GameTest, dedicated-server smoke, isolated Client Driver Playtest, production
Client Playtest, pure zero-mod vanilla smoke, or external-mod matrix. A layer
passes only when its exact command succeeds and required evidence validates.

The Client Driver Playtest runs a real Minecraft 26.1.2 client with minimal
Fabric Client GameTest/resource modules and an automation driver. It is not a
pure zero-mod vanilla client. The client must reject Reborn, Polymer, the
server fixture, tested content mods, and unexpected modules. Client and server
must be separate processes and cannot share statics or private plan state.

The canonical evidence root is `build/playtest/`. Reports, logs, screenshots,
runtime mods, and worlds are ignored build output and never enter the release
JAR. A missing assertion, timeout, non-zero process result, or forced cleanup
is failed/incomplete, never success. Test-harness source is not proof a run
passed.

The standalone API is published from `api/`; its canonical Java sources are
also compiled into the main Mod. Public changes require annotation/signature
review, the independent Maven-coordinate consumer, and no backend leakage.

Before committing, inspect `git status --short`, dependency locks/verification,
the release JAR manifest and `fabric.mod.json`, and confirm no build output,
cache, screenshots, secrets, local JARs, absolute paths, research clones,
client-driver classes, or fixture classes are tracked/shipped.

## Style and compatibility discipline

- Use Java 25, four-space Java indentation, UTF-8, final fields, immutable
  snapshots, and path-specific validation messages.
- Prefer records/small immutable values. Normalize registry IDs and paths at
  boundaries.
- Use stable sorting for persisted/reportable data; deterministic tests compare
  bytes, not only parsed values.
- Mapping writes use adjacent temporary files and replacement semantics.
  Corruption never causes silent regeneration.
- GUI sessions are bounded and generation-safe. Rejected transaction claims
  request resync; client predictions never become inventory authority.
- Entity interaction checks current generation, liveness, level, tracking,
  finite hit data, and bounded distance before invoking real server logic.
- Packet fallback is isolated, experimental, and disabled by default. Never add
  a broad "cancel unknown packets" Mixin to make login appear to work.
- Playtest changes must keep the client allow-list, independent server
  observations, timeouts, screenshot contract, structured reports, and clean
  shutdown assertions synchronized.

## License policy

New Java source files begin with:

```java
/* SPDX-License-Identifier: LGPL-3.0-or-later */
```

Files reused/adapted from upstream PolyMc retain original copyright and license
headers. Do not copy PolyMc-Extra or another unclear/incompatible source. Do not
reuse the original logo without a documented artwork license. Markdown does not
need an SPDX header but must preserve the `LGPL-3.0-or-later` policy.

Any behavior change must update the relevant report shape, tests, README or
focused documentation, and `CHANGELOG.md` in the same logical change.
