# Contributing to PolyMc Reborn

Thank you for helping build a conservative compatibility layer. The most useful
contributions are small, reproducible, and honest about fidelity limits.

## Before opening a change

- Read `AGENTS.md`, `docs/architecture.md`, and the ADRs.
- Search existing issues and compatibility profiles.
- Use Minecraft 26.1.2 and Java 25. Do not port by mechanically replacing Yarn
  names; this project uses official Minecraft names.
- For a mod compatibility report, include exact mod versions, the sanitized
  compatibility report, the relevant decision chain, and minimal reproduction
  steps. Do not attach a world or log containing secrets without reviewing it.

## Development workflow

Create a topic branch from the current maintained development branch (for 0.2,
`reborn/0.2.0-alpha+26.1.2`). Keep commits focused: bootstrap,
planning/API, backend behavior, persistence/resources, diagnostics/legacy,
tests, and docs should remain reviewable independently when practical.

Run:

```text
java -version
./gradlew clean build
./gradlew test
./gradlew runGameTest
./gradlew runDedicatedServerSmoke
git diff --check
```

Use `gradlew.bat` on Windows. If an integration task cannot run, state exactly
why and provide the unit or serialization coverage used instead. A pull request
must never imply that a real vanilla client logged in unless that test really
occurred.

## Compatibility changes

A provider or profile must be deterministic, explainable, and conservative.
Show that it preserves a pre-existing native Polymer implementation. Add
negative tests for unsupported shapes/components and for hostile or malformed
input. A new persistent representation needs a schema/algorithm migration and
byte-determinism tests.

Do not solve compatibility by dropping broad packet classes, trusting any
Fabric client, loading arbitrary classes from JSON, running scripts, or
downloading executable code. Do not copy PolyMc-Extra source.

## Pull request checklist

- [ ] Scope is limited and architecture boundaries are respected.
- [ ] New Java files have `SPDX-License-Identifier: LGPL-3.0-or-later`.
- [ ] Adapted upstream files preserve their original notice.
- [ ] Unit tests and applicable integration tasks were run and reported.
- [ ] Mapping/resource outputs remain deterministic.
- [ ] Diagnostics explain new decisions and reports omit sensitive paths.
- [ ] Documentation and `CHANGELOG.md` describe actual behavior.
- [ ] No generated build output, local JAR, secret, or temporary clone is added.

By contributing, you agree that your contribution is licensed under
`LGPL-3.0-or-later`.
