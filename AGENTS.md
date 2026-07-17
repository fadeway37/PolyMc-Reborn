# Contributor and agent guide

This file defines repository-wide implementation rules for PolyMc Reborn.
Apply it together with the user-visible behavior described in `README.md` and
the accepted decisions in `docs/adr/`.

## Product invariant

The server keeps the real modded registry object and server behavior. A client
representation is an overlay, mapping, or serialization transform only. Never
replace a real server registration with a vanilla stand-in. Never describe the
project as compatible with every mod.

Target exactly Minecraft 26.1.2, Fabric Loader 0.19.3, Java 25, official
Minecraft names, and the exact dependency pins in `gradle.properties`. Do not
add Yarn, intermediary-named source, dynamic versions, `flatDir`, vendored
dependency JARs, or unrelated Maven mirrors.

## Architecture and package boundaries

The normal flow is:

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
  contracts. Do not expose Polymer implementation types here.
- `io.github.polymcreborn.core`: lifecycle orchestration, registry discovery,
  plan freezing, and startup guards.
- `io.github.polymcreborn.backend`: generic backend SPI.
- `io.github.polymcreborn.backend.polymer`: all direct Polymer integration.
- `io.github.polymcreborn.compat`: provider registry, built-in providers,
  candidate ordering, and compatibility profiles after parsing.
- `io.github.polymcreborn.config`: strict configuration/profile decoding and
  validation. It must never load arbitrary classes or execute scripts.
- `io.github.polymcreborn.mapping`: immutable decisions, deterministic
  allocation, validation, atomic persistence, and capacity errors.
- `io.github.polymcreborn.pack`: normalized resource discovery, dependency
  traversal, deterministic pack output, hashing, and manifests.
- `io.github.polymcreborn.diagnostics`: structured diagnostics, counters, and
  path-sanitized JSON/Markdown reports.
- `io.github.polymcreborn.command`: Brigadier registration and read-only
  inspection/build commands.
- `io.github.polymcreborn.legacy`: adapters between legacy registrations and
  the new planning API.
- `io.github.theepicblock.polymc.api`: only the deliberately ported legacy
  source-compatibility surface. Preserve upstream license headers here.

No common or server initializer may reference client-only Minecraft classes.
Hot-path lookups use precomputed immutable maps; do not scan registries per
tick, read files during packet conversion, or use reflection in packet paths.

## Lifecycle and mutability

Registration and provider contribution happen during mod initialization.
Discovery is sorted by canonical registry identifier before planning. A narrow
registry-freeze hook finalizes the plan after startup registrations are
collected but while Polymer can still mark server-only entries. Server-start checks
may inspect dynamic registry state, but commands must not change a frozen plan.
Mapping-affecting config changes require restart.

Native Polymer overlays win unless both an administrator rule requests an
override and `override_native_polymer` is explicitly enabled. The full provider
order is documented in `docs/compatibility-model.md`; changes to it require an
ADR, deterministic-order tests, and migration notes.

## Adding a CompatibilityProvider

1. Implement the backend-neutral provider contract in `compat` or a downstream
   mod. Give it a stable ID and an explicit priority tier; never depend on
   insertion order or `HashMap` iteration.
2. Return an explainable candidate, not an applied mutation. Include strategy,
   confidence, degradation, reason chain, required resources, warnings, and
   failure information.
3. Refuse types the provider cannot model safely. Prefer `UNSUPPORTED` to a
   plausible-looking but behaviorally unsafe mapping.
4. Register during the `polymc-reborn` entrypoint. Do not mutate registries
   after plan freeze.
5. Add tests for priority, native-Polymer preservation, stable ordering,
   capacity behavior, reports, and the new content case.
6. Update compatibility documentation and release notes if observable behavior
   changes.

## Adding a declarative compatibility profile

1. Start from the example and JSON Schema referenced in
   `docs/compat-profile-schema.md`.
2. Use a unique stable profile ID, schema version, target mod/version
   condition, explicit priority, and narrow rules.
3. Match exact IDs where possible. Safe globs are permitted; regular
   expressions, scripts, class names to instantiate, shell commands, and remote
   code are forbidden.
4. An override of native Polymer behavior must be visible in the profile and is
   inert unless the dangerous global switch is enabled.
5. Run the schema/config tests and validate through the administrator command.
   Validation does not hot-apply the file.

## Build and test commands

Run `java -version` before building.

```text
./gradlew clean build
./gradlew test
./gradlew runGameTest
./gradlew runDedicatedServerSmoke
./gradlew dependencies
git diff --check
```

On Windows use `gradlew.bat`. Do not claim a GameTest or server smoke test
passed unless that exact task completed successfully. There is no claim of a
real vanilla-client end-to-end login unless a real client automation was
actually run.

Before committing, inspect `git status --short`, dependency locks/verification,
the distributable JAR manifest and `fabric.mod.json`, and confirm no build output,
cache, secrets, local JARs, absolute paths, or temporary research clones are
tracked.

## Style and compatibility discipline

- Use ordinary Java 25, four-space indentation for Java, UTF-8, final fields,
  immutable snapshots, and explicit validation messages containing the logical
  config path.
- Prefer records and small value objects for immutable data. Normalize IDs and
  paths at boundaries.
- Avoid Lombok and avoid dependencies that duplicate JDK/Fabric facilities.
- Use stable sorting for persisted/reportable collections. Deterministic tests
  compare bytes, not merely parsed objects.
- Mapping files use temporary-file plus replacement semantics and are backed up
  before incompatible migration. Never recover corruption by silently creating
  an empty store.
- Packet fallback is isolated, experimental, and off by default. Never add a
  broad “cancel unknown packets” Mixin to make login appear to work.
- Entity and GUI automation requires explicit safe semantics; do not guess
  generic projections in 0.1.

## License policy

New Java source files begin with:

```java
/* SPDX-License-Identifier: LGPL-3.0-or-later */
```

Files reused or adapted from upstream PolyMc keep the original copyright and
license header. Do not copy from PolyMc-Extra or any source with unclear or
incompatible licensing. Do not reuse the original logo without a documented
artwork license. Markdown does not require an SPDX header, but must not change
the repository's `LGPL-3.0-or-later` policy.

Any behavior change must update the relevant report shape, tests, README or
focused documentation, and `CHANGELOG.md` in the same logical change.
