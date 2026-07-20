# Testing

PolyMc Reborn uses independent test layers. Never report one layer as evidence
that another passed, and never infer a result from checked-in harness source.

## Commands

```text
java -version
./gradlew javaToolchains
./gradlew clean build
./gradlew test
./gradlew checkApiSignature
./gradlew runLegacyApiConsumerPlaytest
./gradlew runGameTest
./gradlew runDedicatedServerSmoke
./gradlew verifyPlaytestClientIsolation
./gradlew runClientPlaytest
./gradlew runProductionClientPlaytest
./gradlew runPlaytest
./gradlew runApiConsumerPlaytest
./gradlew runProductionMultiClientPlaytest
./gradlew runPackPolicyPlaytest
./gradlew runRcUpgradePlaytest
./gradlew runModSetExpansionPlaytest
./gradlew runExternalModMatrix
./gradlew runWindowsSoakPlaytest
./gradlew runLinuxSoakPlaytest
./gradlew runLongSoakPlaytest
./gradlew verifyReproducibleArchives generateSbom assembleRcArtifacts
git diff --check
```

Use `gradlew.bat` on Windows. `clean build` includes JUnit but not every external
process task. Each separate command must really finish before it is marked
passed.

## Test-layer matrix

| Layer | Process/artifact boundary | Claim allowed after success |
| --- | --- | --- |
| JUnit | JVM tests, mostly temporary data | deterministic/hostile logic cases |
| Server GameTest | development server plus internal fixture | registry, Fabric, and Polymer lifecycle behavior |
| Dedicated-server smoke | server-only production source set | startup and absence of client-class linkage |
| Isolated Client Driver Playtest | real client + independent server | multiplayer, pack, input, presentation, reconnect observations |
| Production Client Playtest | same two processes using the final official-namespace distribution JAR | artifact/classpath isolation plus the interactive scenario |
| Multi-Client Driver Playtest | two isolated clients plus one production server | concurrent sessions, pack state, disconnect isolation |
| API Consumer Playtest | independent Maven-coordinate consumer on production server | standalone API resolution/runtime registration |
| Upgrade/Mod-set Playtest | audited 0.3 Beta then 0.4 RC over one world/store, then independent Mod B add/remove/re-add | mapping/pack/player/block/Property GUI/entity/policy preservation, dormant allocation retention, byte-stable re-add |
| External-Mod matrix | three hash-locked third-party server Mods, absent from client | only named armor/full-cube/food scenarios |
| Short Soak | five isolated production iterations on one OS | repeated complete scenarios plus process/port/handle/session cleanup |
| Long Soak | ten stressed production iterations | aggregate operation floors and post-warmup resource-leak trends |
| Artifact Attestation | GitHub-hosted build provenance plus `gh attestation verify` | repository/workflow/commit/subject digest for exact release subjects, including tamper rejection |
| Pure zero-mod vanilla smoke | no Fabric/client driver | P1, `NOT_RUN` for the RC |

The Client Driver Playtest is a real Minecraft 26.1.2 client, but includes a
minimal Fabric automation mod. It must not be called an unmodified or pure
vanilla client.

## JUnit coverage

The JUnit 5 suite covers strict main/profile config, bounded safe globs,
provider priority/native preservation, stable discovery, mapping-store
roundtrip/corruption/atomic writes/determinism, deterministic resource packs,
semantic item conversion/filtering, full-cube and stateful-block allocation,
legacy entrypoint registration, dedicated-server class loading, GUI slot/
transaction/session safety, explicit entity registry/session/interaction
safety, and mapping diff/backup/rollback validation.

Deterministic tests compare canonical bytes/hashes. Filesystem tests use JUnit
temporary directories and never modify developer-global server configuration.

## Server GameTest

The non-release GameTest fixture registers real custom items, semantic food/
tools, simple and stateful full cubes, unsupported shape/block-entity cases,
native Polymer examples, custom entity/menu types, and a recompiled legacy
entrypoint. GameTest checks real registry and Polymer lifecycle wiring.

Success requires execution of the registered tests and a successful process
exit. Accepting the EULA, generating a world, or reaching an idle prompt is not
a passing GameTest.

## Dedicated-server smoke

The smoke task uses the server-only Minecraft JAR and production mod source to
detect client linkage, metadata/dependency mistakes, startup guard/config/report
failures, and Polymer API linkage. It passes only after the ready marker and a
controlled shutdown. A forced kill is incomplete/failure.

## Client and production playtests

`verifyPlaytestClientIsolation` checks the client dependency/mod allow-list and
that release archives exclude the driver/fixture/evidence. `runClientPlaytest`
and `runProductionClientPlaytest` exercise the isolated two-process harness;
`runPlaytest` is the canonical aggregate entrypoint.

The scenario contract includes:

- real multiplayer connection and resource-pack handling/application;
- movement, camera rotation, and hotbar input;
- semantic item use;
- a real bound-key world drop and pickup with client fingerprint and independent
  server inventory-transition assertions;
- placement, state change, and break of a mapped full cube;
- projected-container click, shift-click, drag/hotbar/offhand paths and
  inventory-conservation checks;
- explicit furnace property progress/result transfer;
- explicit virtual-entity spawn/movement/use/attack plus bounded equipment and
  one passenger;
- an abnormal disconnect while a projected GUI is open, followed by
  generation-safe cleanup;
- normal disconnect and reconnect;
- independent client assertions and server observations;
- bounded scenario/global timeouts and clean process shutdown.

The evidence root is:

```text
build/playtest/
  summary.json
  summary.md
  junit.xml
  loaded-client-mods.json
  server-state.json
  client-state.json
  screenshots/
  logs/client.log
  logs/server.log
  logs/orchestrator.log
```

The required screenshot contract is:

```text
01-connected.png
02-resource-pack-prompt.png
03-resource-pack-applied.png
04-item-held.png
05-item-after-use.png
06-block-placed.png
07-block-state-off.png
08-block-state-on.png
09-block-broken.png
10-gui-open.png
11-gui-after-shift-click.png
12-gui-after-hotbar-swap.png
13-gui-reopened.png
14-property-gui-start.png
15-property-gui-progress.png
16-property-gui-complete.png
14-entity-spawned.png
15-entity-moved.png
16-entity-interacted.png
17-reconnected.png
```

A missing/empty report, forbidden client mod, missing input assertion,
screenshot mismatch, timeout, non-zero process exit, or forced cleanup fails the
run. The summary/JUnit report must be derived from actual client/server results,
not hard-coded success. The final handoff records the exact command, exit code,
scenario counts, and evidence paths.

## Historical 0.2 release-candidate verification (2026-07-18)

The local Windows host completed `runClientPlaytest`,
`runProductionClientPlaytest`, and the canonical `runPlaytest` entrypoint in
fresh directories. The retained final run reports 53/53 aggregate checks,
35/35 client steps, 17/17 screenshot artifacts, client/server exit code zero,
no timeout, no forced termination, and a clean server stop. It observed exactly
two joins/disconnects, two resource-pack pushes/GETs, and two byte-identical
client pack-cache files. GitHub Actions Client Playtest run
[`29642433900`](https://github.com/fadeway37/PolyMc-Reborn/actions/runs/29642433900)
passed the same 53/53 checks and 35/35 client steps under Ubuntu 24.04/Xvfb;
standard CI run
[`29642433896`](https://github.com/fadeway37/PolyMc-Reborn/actions/runs/29642433896)
also passed. Their evidence and release-JAR artifacts were downloaded to the
ignored `build/github-artifacts/` directory and inspected.

## Resource-pack stability

Equivalent normalized inputs and mappings must generate byte-identical ZIP and
manifest bytes. The production playtest additionally records the served pack
hash, client application state, and reconnect state. A warm-cache result alone
does not prove determinism; compare independent builds when releasing.

## Release archive verification

The distributable JAR must contain `fabric.mod.json`, license/notices, and a
manifest with project version, Minecraft version, Git commit, and dirty state.
It must not contain Client GameTest/driver classes, fixtures, screenshots,
reports, worlds, caches, secrets, local JARs, or absolute developer paths.

## RC evidence roots

Single client, multi-client, API consumers, upgrade, mod-set expansion, external
Mods, short Soak, and long Soak evidence have separate children under `build/playtest/`.
Each gate uses structured summaries, process exits, loaded Mod lists, hashes,
redaction metadata, and manifests; harness source alone is not evidence.

## P1 test status

The pure zero-mod vanilla-client smoke and runtime creative reverse-mapping
client scenario are `NOT_RUN` for the RC. Creative enablement fails startup by
design. External results remain feature-scoped.
