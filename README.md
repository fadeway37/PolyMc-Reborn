# PolyMc Reborn

PolyMc Reborn is a community-maintained, server-side Fabric compatibility
platform for **Minecraft 26.1.2**. It keeps each mod's real registered objects
and game logic authoritative on the server while Polymer supplies conservative
vanilla-facing overlays.

The current development release is `0.2.0-alpha.1+26.1.2`, the **Interactive
Compatibility Alpha**. It is not an official continuation endorsed by
TheEpicBlock or the original PolyMc contributors.

> PolyMc Reborn does not make every content mod compatible. Compatibility is a
> per-entry decision. Unsafe or unmodelled content is reported instead of being
> guessed into a plausible-looking representation.

## Exact platform

| Component | Pinned version |
| --- | --- |
| Minecraft | `26.1.2` |
| Java toolchain and bytecode release | `25` |
| Fabric Loader | `0.19.3` |
| Fabric Loom | `1.17.16` |
| Fabric API | `0.155.2+26.1.2` |
| Polymer Core, Blocks, Resource Pack, Virtual Entity | `0.16.5+26.1.2` |
| Gradle Wrapper | `9.5.1` |

The project uses Minecraft's official names. It has no Yarn mappings
dependency and does not use intermediary-named source.

## What 0.2 implements

Reborn discovers registered content in stable identifier order, asks ordered
compatibility providers for candidates, freezes an immutable mapping plan, and
applies the selected representation through Polymer. Every decision records
the chosen provider/backend, confidence, degradation, reason chain, required
resources, warnings, and failure information.

The 0.2 implementation adds to the 0.1 foundation:

- conservative semantic item carriers and safe client-component projection;
- deterministic per-state mappings for full-cube blocks whose variants and
  resource dependencies can be resolved safely;
- an explicit standard-container GUI API backed by the real server
  `Container`, bounded sessions, policy-gated interactions, and resync support;
- an explicit Polymer Virtual Entity API with a vanilla surrogate, guarded
  use/attack callbacks, and lifecycle cleanup;
- mapping status, strict validation, stable diff, no-write dry run, checksummed
  backup, and restart-only rollback preparation;
- a two-process production playtest harness: an independent dedicated server
  runs the final official-namespace distribution JAR and fixture while a real Minecraft 26.1.2
  client runs an isolated Fabric Client GameTest driver;
- deterministic resource-pack generation, strict compatibility profiles,
  structured diagnostics, and the selected source-migration bridge for legacy
  `polymc` entrypoints.

Native Polymer implementations retain priority. The real server item, block,
entity, menu, and container are never replaced with a vanilla registration.

## Explicit limits

0.2 does **not** provide arbitrary GUI conversion or automatic entity guessing.
Only reviewed GUI/entity adapters are projected. Furnace/property menus,
pagination, custom client screens, broad entity metadata/equipment/passenger
synchronization, complex block geometry, custom client renderers, and broad
packet rewriting remain unsupported or roadmap work.

Runtime creative reverse mapping remains disabled. The `REBORN_COMPANION` and
`TRUSTED_MODDED` client profiles remain future API values; Fabric presence does
not grant registry passthrough. Packet fallback remains an isolated disabled
no-op/audit boundary.

Old extensions compiled for Minecraft 1.20/1.21 are not binary compatible with
26.1.2. The bridge is for extensions ported and recompiled against this release.
See [migration-from-polymc.md](docs/migration-from-polymc.md).

## Server installation

1. Run a dedicated Fabric server for exactly Minecraft `26.1.2` on Java 25.
2. Install Fabric API `0.155.2+26.1.2`.
3. Install the pinned Polymer Core, Blocks, Resource Pack, and Virtual Entity
   modules at `0.16.5+26.1.2`.
4. Put the PolyMc Reborn JAR in the server `mods` directory and start once.
5. Review the generated compatibility, mapping-diff, and resource-pack reports
   before admitting players.

Reborn and Polymer are server dependencies. Ordinary players are not required
to install them. Visual fidelity can require accepting the server resource
pack. Polymer AutoHost is optional and is not bundled.

Do not install another real mod whose metadata ID is `polymc` beside Reborn.
Reborn advertises `polymc` through `provides` for recompiled extensions and
rejects a distinct implementation. `polymc-extra` is declared incompatible.

## Configuration and operational state

Server state lives below `config/polymc-reborn/`:

| Path | Purpose |
| --- | --- |
| `config.json` | strict main configuration |
| `compat.d/*.json` | versioned administrator compatibility profiles |
| `mappings-v1.json` | stable mapping allocations; back up with the world |
| `backups/mappings/` | checksummed mapping backups |
| `reports/` | compatibility, mapping-diff, and resource-pack reports |
| `cache/` | bounded, rebuildable generated-data cache |

Unknown fields are rejected. Mapping-affecting changes are read during startup
and never hot-apply to a frozen plan. Corrupt mappings are a startup error;
Reborn does not silently replace them with an empty store.

Mapping operations are documented in
[mapping-migration.md](docs/mapping-migration.md). The principal commands are:

```text
/pmcr mappings status
/pmcr mappings validate
/pmcr mappings diff
/pmcr mappings dry-run
/pmcr mappings backup
/pmcr mappings rollback <backup-id>
```

Rollback stages validated pending data and takes effect only during the next
startup. It never changes the live frozen plan.

## Administrator commands

`/polymcreborn about` is informational. Inspection/build commands require
administrator permission. `/pmcr` is the short alias; `/polymc` is registered
only when that literal is free.

- `/polymcreborn about`
- `/polymcreborn scan`
- `/polymcreborn report [json|markdown]`
- `/polymcreborn why <registry-id>`
- `/polymcreborn pack build`
- `/polymcreborn config validate`
- `/polymcreborn stats`
- `/polymcreborn client-profile <player>`
- `/polymcreborn mappings <status|validate|diff|dry-run|backup|rollback>`

`scan` and mapping commands inspect the frozen plan; they do not remap a live
server. Reports omit absolute local paths by default.

## Resource packs

Reborn normalizes resource paths, rejects traversal, walks bounded model
dependencies, validates texture references, diagnoses duplicate/conflicting
bytes, sorts ZIP entries, normalizes timestamps, and records content hashes.
Equivalent resources and mappings must produce byte-identical pack output.

The two-process playtest additionally downloads the real served pack through
the Minecraft client and records the application/reconnect observations. A
resource-pack or playtest claim is valid only when its command actually exits
successfully and the evidence validates. See
[resource-pack-pipeline.md](docs/resource-pack-pipeline.md).

## Testing and client terminology

Run Java first; do not substitute Java 21:

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
```

On Windows use `gradlew.bat`. The canonical full interactive entrypoint is
`runPlaytest`. It writes ignored evidence below `build/playtest/`, including
summary JSON/Markdown, JUnit XML, loaded-client mods, independent client/server
state, logs, and screenshots.

The Client Driver Playtest launches a **real Minecraft client**, but that client
contains Fabric Loader, the minimal Fabric Client GameTest/resource modules,
and the automation driver. It is therefore not a pure zero-mod vanilla client.
The driver contains no Reborn, Polymer, server fixture, server content
definitions, or private mapping-plan access and rejects those mods at startup.

A pure zero-mod vanilla-client smoke and a completed third-party content-mod
matrix are separate P1 layers and are not implemented or claimed by this
release. On 2026-07-18 the local `runClientPlaytest`,
`runProductionClientPlaytest`, and `runPlaytest` commands each completed
successfully; the retained final evidence reports 53/53 checks, 34/34 client
steps, and 17/17 screenshots. A GitHub Actions result is a separate gate and
must be cited by run ID after it completes. See
[testing.md](docs/testing.md) and [client-playtest.md](docs/client-playtest.md).

The distributable JAR and sources/Javadoc JARs are written to `build/libs/`.
The release JAR must not contain the client driver, fixtures, screenshots, or
playtest reports.

## License and attribution

PolyMc Reborn is derived from
[TheEpicBlock/PolyMc](https://github.com/TheEpicBlock/PolyMc) and is licensed
under `LGPL-3.0-or-later`. Reused/adapted files retain upstream notices; new
Java sources carry an SPDX identifier. No original logo is reused because its
separate artwork license has not been confirmed, and no PolyMc-Extra source was
used. See [NOTICE.md](NOTICE.md) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
