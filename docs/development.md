# Development

## Prerequisites

- A Git clone with full history and both `origin` (the PolyMc-Reborn fork) and
  `upstream` (TheEpicBlock/PolyMc).
- Java 25 available locally, or a Gradle JVM capable of starting the wrapper so
  the Foojay resolver can provision a Java 25 toolchain.
- Network access to Fabric Maven, Maven Central/Gradle Plugin Portal, and the
  narrowly scoped Nucleoid Maven repository.

Run this before Gradle:

```text
java -version
```

Java 21 is not a fallback. `build.gradle` sets both the Java toolchain language
version and compiler `--release` to 25.

## Pinned build

`gradle.properties` is the single readable list of target versions:

```text
minecraft_version=26.1.2
loader_version=0.19.3
loom_version=1.17.16
fabric_api_version=0.155.2+26.1.2
polymer_version=0.16.5+26.1.2
junit_version=5.13.4
mod_version=0.4.0-rc.1+26.1.2
```

The Gradle wrapper is 9.5.1. Dependencies must remain exact; do not introduce
`+`, ranges, snapshots, or locally copied JARs. Loom supplies Mojang's official
names directly for Minecraft 26.1.2; there is intentionally no `mappings`
dependency.

## Common commands

Use `./gradlew` on Unix-like hosts and `gradlew.bat` on Windows.

```text
./gradlew clean build
./gradlew test
./gradlew checkApiSignature
./gradlew runLegacyApiConsumerPlaytest
./gradlew runApiConsumerPlaytest
./gradlew runGameTest
./gradlew runDedicatedServerSmoke
./gradlew verifyPlaytestClientIsolation
./gradlew runClientPlaytest
./gradlew runProductionClientPlaytest
./gradlew runPlaytest
./gradlew runProductionMultiClientPlaytest
./gradlew runExternalModMatrix
./gradlew runRcUpgradePlaytest
./gradlew runWindowsSoakPlaytest
./gradlew runLinuxSoakPlaytest
./gradlew runLongSoakPlaytest
./gradlew dependencies
./gradlew dependencyInsight --dependency polymer-core
./gradlew javadoc sourcesJar
```

`clean build` runs compilation, unit tests, and archive tasks wired to the
build lifecycle. Server GameTest, dedicated-server smoke, and two-process
client playtests are distinct integration layers. Invoke and report them
separately unless a named aggregate wires them together. `runPlaytest` is the
canonical interactive aggregate; its evidence root is `build/playtest/`.
Check `docs/testing.md` for exact acceptance details.

## Source sets

- `src/main/java` and `src/main/resources`: distributable server mod.
- `src/test/java` and `src/test/resources`: JUnit 5 tests that can run without a
  live game where possible.
- `src/gametest/java` and `src/gametest/resources`: internal content fixtures and
  Fabric GameTest entrypoints, not included as a public API or release mod.
- `playtest/client-driver`: separate client-only Loom project containing the
  minimal Fabric Client GameTest driver. It has no root-project, Polymer, or
  fixture dependency and must never be packaged into the release JAR.
- production playtest server fixtures are built as a separate non-release JAR;
  generated worlds, logs, reports, and screenshots live under
  `build/playtest/` and are ignored.

Test fixtures include ordinary/semantic items, safe and unsafe block shapes,
block entity/entity/menu registrations, native Polymer content, and a legacy
`polymc` entrypoint.

## Coding rules

Use official Minecraft class/method names from the target sources. Do not infer
a 26.1 name by mechanically translating an old Yarn class. Keep direct Polymer
usage in `backend.polymer`; API/provider contracts should remain backend-neutral.

All new Java files begin with
`/* SPDX-License-Identifier: LGPL-3.0-or-later */`. Preserve full original
headers in adapted upstream files. Use UTF-8 and four-space Java indentation.

Favor immutable records/value objects after validation. Stable persisted output
must use an explicit comparator. Never rely on filesystem enumeration order,
ZIP input order, locale-sensitive case conversion, default charset/time zone,
or `HashMap` iteration.

## Adding or changing a provider

Follow `AGENTS.md` and `compatibility-model.md`. A provider returns a candidate
with reasons; it does not write a mapping or directly install a Polymer overlay.
Add positive and negative tests, a native-Polymer non-override test, stable
ordering, report assertions, and capacity/persistence coverage where relevant.

If behavior affects existing allocations, increment the mapping algorithm
version and implement a validated migration. Create a backup before an
incompatible rewrite; never regenerate silently.

## Configuration changes

The JSON Schema is the portable structural contract; the strict Java decoder is
the runtime authority and additionally enforces cross-field limits such as
`max_single_file_bytes <= max_total_bytes`. Keep field sets, primitive types,
action domains, and numeric bounds synchronized in tests. Add path-specific
decoder errors, update the example, and update `compat-profile-schema.md`.
Unknown fields use the project-wide strict policy. Mapping-affecting changes
apply only on restart.

## Reproducible artifacts

Archives disable file timestamps and use reproducible entry order. Generated
pack writers additionally normalize entry timestamps/content ordering. The main
JAR manifest records the project/Minecraft version, Git commit, and dirty flag;
it must not include absolute paths. Sources and Javadoc JARs are generated.

Before pushing:

```text
git diff --check
git status --short
git ls-files build .gradle run
```

Inspect the distributable JAR's manifest and `fabric.mod.json`, review dependency
output for local/unknown repositories, and ensure config/cache/report artifacts
and temporary research clones are not tracked.

## Git workflow

Work on `reborn/0.4.0-rc+26.1.2`, not the upstream default branch. Keep `upstream`
read-only and never open a Reborn pull request against TheEpicBlock/PolyMc.
Use focused commits whose messages explain the purpose. Push the development
branch only after the build is in a reasonably reliable state and report any
integration task that was skipped or failed.
