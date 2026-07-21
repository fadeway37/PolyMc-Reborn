# PolyMc Reborn

A server-side Fabric compatibility layer that presents supported modded content to clients that do not install the same content mods.

[![CI](https://github.com/fadeway37/PolyMc-Reborn/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/fadeway37/PolyMc-Reborn/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/fadeway37/PolyMc-Reborn?include_prereleases&sort=semver)](https://github.com/fadeway37/PolyMc-Reborn/releases)
[![License](https://img.shields.io/github/license/fadeway37/PolyMc-Reborn)](LICENSE)

## Project status

PolyMc Reborn is pre-release software. The current candidate is still a draft on GitHub, so there is no ordinary public download yet. Back up a server and verify the exact content you intend to use before admitting players.

## What it solves

The Fabric server keeps the real modded registry objects and game mechanics. PolyMc Reborn plans safe client-facing forms and uses Polymer to show them to ordinary clients. These forms are overlays or serialization transforms; they never replace the authoritative server objects.

Players using this compatibility path do not install PolyMc Reborn, Polymer, or the server's content mods. A server resource pack can provide names, textures, and models.

## Core capabilities

- Preserves a mod's native Polymer behavior by default.
- Provides conservative item and safe block representations with persistent, explainable mappings.
- Supports explicit, server-authoritative GUI and entity projections.
- Builds deterministic resource packs and compatibility reports.
- Provides mapping diff, dry-run, backup, and restart-only rollback operations.
- Exposes a backend-neutral extension API and selected source-migration adapters for old PolyMc extensions.

## Limits

PolyMc Reborn does not guarantee that an arbitrary mod is compatible. Client-only renderers cannot be recreated automatically. GUI and entity support is explicit and limited, and complex blocks need individual validation. High-risk creative reverse mapping, broad packet fallback, and trusted-modded passthrough remain closed.

See the [known limitations](https://polymc-reborn-docs.pages.dev/#/en/reference/known-limitations) before choosing a production mod set.

## Download

Use the [GitHub Releases page](https://github.com/fadeway37/PolyMc-Reborn/releases). A draft release is not a public supported download; do not obtain similarly named JARs from reposting sites.

## Documentation

**Official documentation:** [简体中文](https://polymc-reborn-docs.pages.dev/#/zh-cn/) · [English](https://polymc-reborn-docs.pages.dev/#/en/)

- Installation: [简体中文](https://polymc-reborn-docs.pages.dev/#/zh-cn/getting-started/install-from-scratch) · [English](https://polymc-reborn-docs.pages.dev/#/en/getting-started/install-from-scratch)
- Farmer’s Delight walkthrough: [简体中文](https://polymc-reborn-docs.pages.dev/#/zh-cn/getting-started/farmers-delight-tutorial) · [English](https://polymc-reborn-docs.pages.dev/#/en/getting-started/farmers-delight-tutorial)
- Troubleshooting: [简体中文](https://polymc-reborn-docs.pages.dev/#/zh-cn/troubleshooting/start-here) · [English](https://polymc-reborn-docs.pages.dev/#/en/troubleshooting/start-here)
- Development guide: [简体中文](https://polymc-reborn-docs.pages.dev/#/zh-cn/development/setup) · [English](https://polymc-reborn-docs.pages.dev/#/en/development/setup)
- Adapter and API entry: [简体中文](https://polymc-reborn-docs.pages.dev/#/zh-cn/development/write-an-adapter) · [English](https://polymc-reborn-docs.pages.dev/#/en/development/write-an-adapter)

Documentation issues belong in the [PolyMc-Reborn-Docs repository](https://github.com/fadeway37/PolyMc-Reborn-Docs).

## Development

Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing product code. Maintainer-only repository workflow is in [docs/development.md](docs/development.md), and accepted design decisions are in [docs/adr/](docs/adr/).

## Project history

PolyMc Reborn began as a fork of [TheEpicBlock/PolyMc](https://github.com/TheEpicBlock/PolyMc) and is now independently maintained. This project is not endorsed by, or an official continuation maintained by, the original authors. Polymer and PolyMc-Extra are separate projects; PolyMc-Extra is incompatible with this mod.

## License

PolyMc Reborn is licensed under `LGPL-3.0-or-later`. Adapted files retain their upstream notices. See [LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
