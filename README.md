# PolyMc Reborn

PolyMc Reborn is a community-maintained, server-side Fabric compatibility
platform for **Minecraft 26.1.2**. It keeps a mod's real registered items,
blocks, and game logic on the server while using Polymer overlays to present a
conservative vanilla representation to an unmodified client.

The first release line is `0.1.0-alpha.1+26.1.2`. It requires Java 25 and is
not an official continuation endorsed by TheEpicBlock or the original PolyMc
contributors.

> PolyMc Reborn does not make every content mod compatible. Compatibility is a
> per-entry decision, and unsupported content is reported instead of being
> guessed into an unsafe representation.

## Exact platform

| Component | Pinned version |
| --- | --- |
| Minecraft | `26.1.2` |
| Java toolchain and bytecode release | `25` |
| Fabric Loader | `0.19.3` |
| Fabric Loom | `1.17.16` |
| Fabric API | `0.155.2+26.1.2` |
| Polymer Core, Blocks, Resource Pack | `0.16.5+26.1.2` |
| Gradle Wrapper | `9.5.1` |

The project uses Minecraft's official names. It has no Yarn mappings
dependency and does not use intermediary-named source.

## What the MVP does

The 0.1 architecture discovers registered content in a stable identifier
order, asks ordered compatibility providers for candidates, and freezes the
result as an immutable mapping plan. Every decision records its selected
provider/backend, status, confidence, degradation, reasons, resources,
warnings, and failure information.

The implemented safe MVP boundary is:

- preserve any native Polymer item or block implementation by default;
- attach Polymer overlays to supported existing item and simple full-cube
  block registrations without replacing the real server object;
- choose conservative vanilla carriers and report lossy decisions;
- persist deterministic mapping assignments in
  `config/polymc-reborn/mappings-v1.json`;
- load strict, versioned compatibility profiles from bundled resources and
  `config/polymc-reborn/compat.d/*.json`;
- build deterministic resource-pack inputs and emit JSON/Markdown reports;
- expose a source-migration bridge for selected legacy `polymc` entrypoints;
- classify entities and menus without attempting broad automatic emulation;
- expose an experimental packet-fallback SPI whose no-op implementation is
  selected by default.

See [the compatibility model](docs/compatibility-model.md) for the decision
order and [the testing guide](docs/testing.md) for the claims actually covered
by automated tests in this checkout.

## What it does not do

The 0.1 release does not provide generic entity conversion, generic GUI
projection, arbitrary packet rewriting, custom client renderer emulation,
Quilt/NeoForge support, automatic trust of modded clients, or runtime code
downloads. A Fabric client is not treated as trusted merely because it runs
Fabric. Unknown clients use the `VANILLA` profile.

Old PolyMc extensions compiled for Minecraft 1.20 or 1.21 are **not binary
compatible** with Minecraft 26.1.2. The legacy bridge is for extensions that
are ported and recompiled against this release; consult
[migration-from-polymc.md](docs/migration-from-polymc.md).

## Installation

1. Run a dedicated Fabric server for exactly Minecraft `26.1.2` on Java 25.
2. Install Fabric API `0.155.2+26.1.2`.
3. Install Polymer Core, Polymer Blocks, and Polymer Resource Pack, all at
   `0.16.5+26.1.2`.
4. Put the PolyMc Reborn JAR in the server's `mods` directory and start the
   server once.
5. Review generated configuration and compatibility reports before admitting
   players. Arrange distribution of the generated resource pack if the
   selected mappings require its assets.

PolyMc Reborn and Polymer are server dependencies. A vanilla client is not
required to install either mod. Visual fidelity can depend on accepting the
server resource pack. Polymer AutoHost may be installed separately to host and
send a pack, but it is not a core dependency and PolyMc Reborn does not bundle
it.

Do not install an independent implementation whose actual mod ID is `polymc`
beside PolyMc Reborn. Reborn advertises `polymc` through Fabric's `provides`
metadata for recompiled extensions and fails startup if a distinct real
`polymc` container is detected. `polymc-extra` is declared incompatible.

## Configuration and generated data

The server-side state lives under `config/polymc-reborn/`:

| Path | Purpose |
| --- | --- |
| `config.json` | Strict main configuration |
| `compat.d/*.json` | Administrator compatibility profiles |
| `mappings-v1.json` | Stable mapping allocations; back up with the world |
| `reports/` | Compatibility and resource-pack reports |
| `cache/` | Rebuildable, bounded generated-data cache |

Unknown fields are rejected consistently rather than silently ignored. A
profile that changes mapping decisions is read during startup; validating a
new file online does not apply it to the frozen plan. Restart after changing
mapping-affecting configuration. Never delete `mappings-v1.json` as a casual
troubleshooting step: doing so can change client carriers. Corruption is a
startup error, not a signal to discard existing assignments.

The packet fallback is disabled by default. Enabling an experimental switch
does not add a broad packet transformer in 0.1.

Creative reverse mapping is unavailable in the 0.1 runtime. Setting
`creative_reverse_mapping_enabled` to `true` deliberately fails startup rather
than accepting Polymer's unsigned restoration metadata. The verification guard
is retained and tested for future integration, but no 0.1 command or packet path
enables reversal. A vanilla carrier by itself is never enough to recover a
server item.

## Administrator commands

The informational `/polymcreborn about` command is available without operator
permission. All scan, report, explanation, pack-build, configuration, statistics,
and player-profile subcommands require the server's administrator permission
level. The primary root is `/polymcreborn`; `/pmcr` is the short alias.
`/polymc` is registered only if no other command owns that literal.

- `/polymcreborn about`
- `/polymcreborn scan`
- `/polymcreborn report [json|markdown]`
- `/polymcreborn why <registry-id>`
- `/polymcreborn pack build`
- `/polymcreborn config validate`
- `/polymcreborn stats`
- `/polymcreborn client-profile <player>`

`scan` reports the frozen plan; it does not mutate registries or remap a live
server. `why` shows candidates, the winner, and the reason chain. Reports omit
absolute local paths by default.

## Resource packs

Polymer is the active rendering and resource-pack integration layer. Reborn
collects only normalized resource paths, rejects traversal, walks declared
model dependencies, and validates texture references. Missing dependencies are
recorded in compatibility diagnostics; conflicting bytes for one destination
fail the build instead of producing a successful resource-pack report.
Equivalent normalized inputs are written in a stable ZIP order with normalized
timestamps so they produce the same content hash.

For an automatic item, Reborn sets the vanilla-facing `ITEM_MODEL` component
only when the owning mod supplies the corresponding 26.1
`assets/<namespace>/items/<path>.json` definition. A missing definition is
reported and the safe vanilla carrier keeps its own model instead of displaying
an unresolvable custom reference.

Generated packs can contain assets copyrighted by installed mods. Server
operators are responsible for having permission to redistribute those assets.
See [resource-pack-pipeline.md](docs/resource-pack-pipeline.md).

## Build from source

Run `java -version` first; do not substitute Java 21. The Gradle Foojay
toolchain resolver can provision a JDK 25 toolchain when the local Gradle JVM
can start and network policy permits it.

```text
./gradlew clean build
./gradlew test
./gradlew runGameTest
./gradlew runDedicatedServerSmoke
```

On Windows use `gradlew.bat`. GameTest and the dedicated-server smoke run are
separate integration tasks; consult [docs/testing.md](docs/testing.md) before
interpreting their results. The distributable JAR is written to
`build/libs/` together with sources and Javadoc JARs.

## Compatibility and support

Before reporting an incompatibility, retain the compatibility report, relevant
server log excerpt, exact mod versions, mapping-store schema/algorithm
versions, and whether the resource pack was accepted. Do not publish world
data, authentication material, full local paths, or other secrets.

Security issues should follow [SECURITY.md](SECURITY.md). Development rules are
in [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md).

## License and attribution

PolyMc Reborn is derived from
[TheEpicBlock/PolyMc](https://github.com/TheEpicBlock/PolyMc) and is licensed
under `LGPL-3.0-or-later`. Reused or adapted upstream files retain their
copyright and license notices. New Java sources carry an SPDX identifier.

See [NOTICE.md](NOTICE.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
No original logo is reused because its separate artwork license has not been
confirmed. No PolyMc-Extra source was used.
