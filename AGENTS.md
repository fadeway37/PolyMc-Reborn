# Repository automation notes

This file provides a short repository-wide checklist. The complete contributor
workflow is in `CONTRIBUTING.md`, implementation details are in
`docs/development.md`, and architectural decisions are in `docs/adr/`.

## Invariants

- Target exactly Minecraft 26.1.2, Fabric Loader 0.19.3, Java 25, official
  Minecraft names, and the exact dependency pins in `gradle.properties`.
- Keep real modded registry objects and mechanics authoritative on the server;
  client forms are overlays or serialization transforms only.
- Preserve native Polymer behavior unless both a narrow rule and the dangerous
  global override explicitly authorize replacement.
- Fail closed for unsafe content. Never claim universal mod compatibility.
- Do not add Yarn, dynamic dependencies, local dependency JARs, unrelated
  mirrors, runtime downloads, scripts in compatibility profiles, or broad
  packet cancellation.
- Common and server initialization must not reference client-only classes.

## Package boundaries

Backend-neutral public contracts belong in `io.github.polymcreborn.api`.
Lifecycle and planning belong in `core`; provider resolution in `compat`;
persistence in `mapping`; pack work in `pack`; reports in `diagnostics`; and
Polymer types only in `backend.polymer`. The historical
`io.github.theepicblock.polymc.api` package contains only deliberate
source-migration adapters with preserved notices.

## Required checks

Run `java -version` before Gradle. Use `gradlew.bat` on Windows.

```text
./gradlew javaToolchains
./gradlew clean build
./gradlew test
./gradlew checkApiSignature
./gradlew runGameTest
./gradlew runDedicatedServerSmoke
./gradlew verifyPlaytestClientIsolation
./gradlew runProductionClientPlaytest
./gradlew runProductionMultiClientPlaytest
./gradlew runExternalModMatrix
./gradlew verifyReproducibleArchives
./gradlew assembleRcArtifacts verifyReleaseArtifacts
./gradlew dependencies
git diff --check
```

Run the additional upgrade, expansion, and soak gates when their documented
scope applies. A skipped, timed-out, force-terminated, or uninspected layer is
not a pass.

## Change discipline

- Register providers and adapters before plan freeze; commands never mutate a
  frozen plan.
- Persist and report in stable order. Corruption never triggers silent state
  replacement.
- Add positive and negative coverage for behavior changes and update reports,
  focused documentation, and `CHANGELOG.md` together.
- New Java files start with `/* SPDX-License-Identifier: LGPL-3.0-or-later */`;
  adapted files retain upstream copyright and license headers.
- Never copy PolyMc-Extra source or reuse the original logo without a confirmed
  compatible artwork license.
- Keep build output, test evidence, worlds, local paths, secrets, fixtures, and
  client-driver classes out of release artifacts and commits.
