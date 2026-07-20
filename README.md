# PolyMc Reborn

A server-side Fabric compatibility layer for presenting supported modded
content to unmodified Minecraft clients.

[![CI](https://github.com/fadeway37/PolyMc-Reborn/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/fadeway37/PolyMc-Reborn/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/fadeway37/PolyMc-Reborn?include_prereleases&sort=semver)](https://github.com/fadeway37/PolyMc-Reborn/releases)
[![License](https://img.shields.io/github/license/fadeway37/PolyMc-Reborn)](LICENSE)

## Project status

The current candidate is `0.4.0-rc.2+26.1.2` for exactly Minecraft `26.1.2`.
It is pre-release software: use backups, review compatibility reports, and test
the intended mod set before admitting players.

PolyMc Reborn is a community-maintained successor initially forked from
[TheEpicBlock/PolyMc](https://github.com/TheEpicBlock/PolyMc). Development now
continues in an independent repository. The project is not endorsed by, or an
official continuation maintained by, the original PolyMc authors.

## What it does

PolyMc Reborn keeps real modded registry objects and game mechanics
authoritative on the Fabric server. It discovers content in stable identifier
order, creates an immutable and explainable mapping plan, and asks Polymer to
present bounded vanilla-facing representations. Client representations are
overlays and serialization transforms; they never replace the real server
registration.

Players are not required to install PolyMc Reborn, Polymer, Fabric Loader, or
the server's content mods. Visual fidelity may require accepting the server
resource pack.

## Core capabilities

- Polymer-first item and safe full-cube block representations, including
  deterministic state allocation and associated block items.
- Conservative semantic item carriers with unsupported client components
  filtered before serialization.
- Native Polymer implementations preserved unless a narrowly authorized
  administrator rule explicitly overrides them.
- Explicit standard-container and furnace projections backed by the real
  server `Container`; no second inventory authority.
- Explicit virtual-entity projections with registered vanilla surrogates and
  guarded lifecycle, equipment, passenger, and interaction handling.
- Deterministic persistent mappings, checksummed backups, diff/dry-run tools,
  and restart-only rollback preparation.
- Deterministic resource-pack collection, validation, hashing, caching, and
  reporting.
- Strict declarative compatibility profiles, structured decision chains,
  per-mod summaries, and sanitized support bundles.
- A backend-neutral extension API plus selected source-migration adapters for
  extensions recompiled against Minecraft 26.1.2.

## What it does not do

PolyMc Reborn does not make every mod compatible. It does not infer arbitrary
GUIs or entities, emulate custom client renderers, promise complex block
geometry, trust an unknown modded client, or broadly suppress packets to make
login appear successful. Packet fallback and creative reverse mapping remain
disabled. A mandatory companion client, Quilt, NeoForge, Geyser, and a general
packet transformation engine are outside the current release.

Old extensions compiled for Minecraft 1.20 or 1.21 are not binary compatible
with Minecraft 26.1.2. The legacy bridge is for reviewed source migration and
recompilation, not unchanged historical JARs.

## Requirements

| Component | Exact version |
| --- | --- |
| Minecraft | `26.1.2` |
| Java toolchain and bytecode release | `25` |
| Fabric Loader | `0.19.3` |
| Fabric Loom (build only) | `1.17.16` |
| Fabric API | `0.155.2+26.1.2` |
| Polymer modules | `0.16.5+26.1.2` |
| Gradle Wrapper (build only) | `9.5.1` |

The build uses Minecraft's official names and has no Yarn mappings dependency.
Required Polymer modules are Core, Blocks, Resource Pack, Virtual Entity, and
Registry Sync Manipulator. Polymer AutoHost is optional and is not bundled.

## Installation

1. Create a dedicated Fabric server for Minecraft `26.1.2` running Java 25.
2. Install Fabric API and the required Polymer modules at the versions above.
3. Download the matching PolyMc Reborn Mod JAR from
   [Releases](https://github.com/fadeway37/PolyMc-Reborn/releases).
4. Place the JAR in the server's `mods` directory and start the server once.
5. Review `config/polymc-reborn/reports/`, then test the exact content-mod set
   and a disposable copy of the world before production use.

Do not install a second implementation whose real metadata ID is `polymc`.
Reborn provides that ID only for recompiled extension dependency resolution
and rejects a distinct implementation. It also declares `polymc-extra`
incompatible.

## Unmodified-client model

Unknown clients are always treated as `VANILLA`; merely detecting Fabric never
authorizes registry passthrough. The server keeps its real objects while
Polymer supplies representations safe for the connected client. Unsupported
content remains server-side, falls back conservatively, or is reported as
unsupported instead of being guessed.

The release suite uses a real Minecraft client with a minimal isolated test
driver. That is not a pure zero-mod client, so this project does not claim an
automated pure-vanilla login result. It does verify server-only artifact
isolation, vanilla-protocol representations, and independent client/server
observations. See [Testing and verification](#testing-and-verification).

## Resource-pack behavior

Resource paths are normalized, traversal is rejected, model dependencies are
walked within bounds, texture references are checked, duplicate/conflicting
bytes are diagnosed, and ZIP ordering/timestamps are normalized. Equivalent
inputs and mappings must produce byte-identical pack output.

Per-player policy can be `REQUIRED`, `OPTIONAL`, or `DISABLED`. A declined
optional pack keeps safe vanilla carriers; it must not expose pack-only model
identifiers. Hosting integration is optional: core operation does not require
Polymer AutoHost.

## Configuration

Runtime state is below `config/polymc-reborn/`:

| Path | Purpose |
| --- | --- |
| `config.json` | Strict main configuration and safety switches |
| `compat.d/*.json` | Versioned declarative compatibility profiles |
| `mappings-v1.json` | Stable persisted allocations; back up with the world |
| `backups/mappings/` | Checksummed mapping backups |
| `reports/` | Compatibility, mapping, and resource-pack reports |
| `cache/` | Bounded rebuildable cache |
| `diagnostics-policy.json` | Display-only diagnostic policy |
| `support/` | Local sanitized support bundles |

Unknown fields are rejected. Mapping-affecting changes require a restart and
never mutate the frozen live plan. Corrupt mapping data is an explicit startup
error; it is not silently replaced.

## Administrator commands

The root command is `/polymcreborn`; `/pmcr` is the short alias. `/polymc` is
registered only when that literal is free.

```text
/pmcr about
/pmcr scan
/pmcr report [json|markdown]
/pmcr why <registry-id>
/pmcr pack build
/pmcr config validate
/pmcr stats
/pmcr client-profile <player>
/pmcr mappings <status|validate|diff|dry-run|backup|rollback>
/pmcr diagnostics <status|validate|why|list>
/pmcr support bundle [status]
```

Inspection commands do not remap a live server. Rollback prepares validated
pending state and takes effect only at the next startup.

## Extension API

The backend-neutral Maven coordinate for this candidate is:

```text
io.github.polymcreborn:polymc-reborn-api:0.4.0-rc.2+26.1.2
```

Public API types avoid Polymer implementation leakage. Providers and adapters
register during initialization, before the static plan freezes. Review
[the API consumer guide](docs/api-consumer-guide.md),
[API stability policy](docs/api-stability.md), and
[legacy compatibility table](docs/legacy-api-compatibility.md) before building
an extension.

## Compatibility policy

Every mapping decision identifies the content, owning mod, selected provider
and backend, strategy, confidence, degradation, complete reason chain,
resources, warnings, and failure details. Discovery and allocation are sorted
by canonical identifiers. Existing allocations remain stable when unrelated
content is added.

Native Polymer behavior wins by default. Declarative rules are data only: they
cannot load arbitrary classes, execute scripts or shell commands, or download
runtime code. Unsafe shapes, ambiguous resources, unmodelled block-entity
rendering, and unsupported interactive behavior fail closed. Read the
[compatibility model](docs/compatibility-model.md) for decision ordering and
limits.

## Testing and verification

The project separates JUnit, Fabric GameTest, dedicated-server smoke,
single-client, multi-client, external-mod matrix, upgrade, soak,
reproducibility, release-artifact, and provenance layers. A result from one
layer is never presented as proof of another. Compatibility results are
feature-scoped to the exact tested versions.

Start with [docs/verification/README.md](docs/verification/README.md) and
[docs/testing.md](docs/testing.md). RC-specific evidence and known gaps are in
[docs/verification/0.4.0-rc.2.md](docs/verification/0.4.0-rc.2.md); the README
intentionally does not duplicate run or artifact inventories.

## Documentation

- [Architecture](docs/architecture.md)
- [Development](docs/development.md)
- [Compatibility model](docs/compatibility-model.md)
- [Compatibility profile schema](docs/compat-profile-schema.md)
- [Resource-pack pipeline](docs/resource-pack-pipeline.md)
- [Migration from PolyMc](docs/migration-from-polymc.md)
- [Project history](docs/project-history.md)
- [Repository migration](docs/repository-migration.md)
- [Roadmap](docs/roadmap.md)

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) and the accepted
[architecture decisions](docs/adr/) before opening a pull request. New
compatibility work must be deterministic, explainable, conservative, covered
by positive and negative tests, and reflected in diagnostics and focused
documentation.

## Security

Please report vulnerabilities privately through GitHub Security Advisories as
described in [SECURITY.md](SECURITY.md). Do not publish tokens, player data,
worlds, production paths, or a weaponized proof of concept in an issue.

## History and source

The active source is
[fadeway37/PolyMc-Reborn](https://github.com/fadeway37/PolyMc-Reborn). The
former fork and its GitHub-hosted historical records are preserved at
[fadeway37/PolyMc-Reborn-Archive](https://github.com/fadeway37/PolyMc-Reborn-Archive).
See [project-history.md](docs/project-history.md) for ancestry and the
independent-repository transition.

## License and attribution

PolyMc Reborn is licensed under `LGPL-3.0-or-later`. Reused or adapted PolyMc
files retain their original notices; new Java files carry an SPDX identifier.
See [LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The original logo is not
reused because its artwork license has not been independently confirmed.
