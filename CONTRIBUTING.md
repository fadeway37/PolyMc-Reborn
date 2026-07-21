# Contributing to PolyMc Reborn

PolyMc Reborn keeps real modded objects authoritative on the server and fails closed when a client representation cannot be made safe. Contributions must preserve that boundary and describe their compatibility scope honestly.

## Choose the right repository

Product code, schemas, build logic, and runtime behavior belong here. User guides, troubleshooting, and Adapter tutorials belong in [PolyMc-Reborn-Docs](https://github.com/fadeway37/PolyMc-Reborn-Docs). Report a documentation error there and update both languages together.

The published [development guide](https://polymc-reborn-docs.pages.dev/#/en/development/setup) explains how an external developer consumes the API. This file covers contributions to the product itself.

## Before changing code

- Read `AGENTS.md`, the maintainer notes in `docs/development.md`, and the relevant [architecture decisions](docs/adr/).
- Start a focused branch from the active repository's `main`. Keep historical and upstream remotes read-only.
- Use Java 25, official Minecraft names, and the exact dependency pins in `gradle.properties`. Do not add Yarn, dynamic versions, local dependency JARs, or runtime downloads.
- Keep public API contracts backend-neutral and direct Polymer types inside `backend.polymer`.
- Preserve upstream copyright and license headers. New Java files start with `/* SPDX-License-Identifier: LGPL-3.0-or-later */`.

## Implementation and verification

Register providers and adapters before plan freeze. Preserve native Polymer behavior unless a narrow rule and the explicit dangerous gate both authorize replacement. Persistence and reports must use stable order, and corrupted state must never be silently replaced.

Add positive and negative coverage for every behavior change. Run `java -version` before Gradle, use `gradlew.bat` on Windows, and execute every check required by `AGENTS.md` and the change's documented scope. A skipped, timed-out, or uninspected layer is not a pass. State exactly what ran and what did not.

Never commit build output, test evidence, worlds, downloaded mod JARs, secrets, private paths, or client-driver classes.

## Pull request

A pull request must explain:

- the bounded user-visible or developer-visible outcome;
- compatibility, mapping, persistence, and security impact;
- exact positive and negative tests run;
- any applicable test not run and the reason;
- report, changelog, and documentation changes;
- rollback or migration needs.

Update `CHANGELOG.md` and focused product metadata when behavior changes. Send user-facing documentation changes to the documentation repository instead of recreating a second guide here.

By contributing, you agree that your contribution is licensed under `LGPL-3.0-or-later`.
