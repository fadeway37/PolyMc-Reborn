# Isolated client playtest

The production harness launches a real Minecraft 26.1.2 client against an independently
launched dedicated server and drives gameplay with Fabric Client GameTest input
APIs. It is not a pure zero-mod vanilla-client test.

## Isolation model

The production server process loads the final official-namespace Reborn distribution JAR,
Minecraft/Fabric, pinned Fabric API/Polymer modules, and a separate non-release
fixture JAR. The client process permits only Minecraft/Java, Fabric Loader and
its loader support, the exact Fabric Client GameTest/resource modules, and the
client driver.

The driver rejects Reborn, `polymc`, Polymer modules, fixture/content mods,
PolyMc-Extra, and any unexpected client mod. It does not depend on the root
project, know fixture registry definitions, implement a Reborn protocol, or
read the server's `MappingPlan`. Client and server cannot share statics.

Both gameplay and resource-pack endpoints bind to dynamically selected loopback
ports. The server uses offline mode only for this local one-player harness. No
account credentials or tokens are required.

## Gradle entrypoints

```text
./gradlew verifyPlaytestClientIsolation
./gradlew runClientPlaytest
./gradlew runProductionClientPlaytest
./gradlew runPlaytest
./gradlew runProductionMultiClientPlaytest
./gradlew runPackPolicyPlaytest
./gradlew cleanPlaytest
```

Use `gradlew.bat` on Windows. `runPlaytest` is the canonical aggregate. It
builds the release and fixture JARs, creates a clean bounded runtime, selects
ports, starts the server, waits for both structured readiness and the port,
starts the real client, validates both reports/evidence, requests normal server
shutdown, and fails on any non-zero exit or cleanup fallback.

Running a client task by itself is not a full playtest unless an independently
ready server and all required properties/evidence paths are supplied.

## Scenario contract

Every scenario records an ID, preconditions, real client input, client and
server assertions, timeout, screenshot, diagnostics, and cleanup. The combined
flow exercises:

1. multiplayer connection and the server resource-pack flow;
2. pack application verified through a known client resource;
3. camera rotation, movement, and number-key/hotbar input;
4. holding and using a semantic custom item;
5. dropping a mapped custom item into the world and picking it back up with
   the same count, name, and component fingerprint;
6. placement, inactive/active state change, and breaking of a mapped full cube;
7. opening a projected container and exercising click, shift-click, drag,
   hotbar/offhand operations, close, and reopen while preserving inventory;
8. seeing an explicit vanilla entity surrogate, observing movement/metadata,
   then sending real use and attack input;
9. normal disconnect, reconnect, and pack/state validation in the new session.

The server fixture records independent joins/disconnects, item use, the basic
item inventory transition `1 -> 0 -> 1`, block placement/state/break, GUI
open/close and inventory conservation, and entity use/attack. A client
screenshot or client-side state alone cannot satisfy a server assertion.

## Resource-pack contract

The fixture builds the actual Reborn/Polymer pack, serves the exact bytes on
loopback, and sends Minecraft's normal pack request with the required transport
hash. Each connection receives a distinct deterministic push UUID while the URL
and content hashes remain identical. The client must unload the first pack,
reconnect, perform a second HTTP GET, and retain two UUID-scoped cache files
whose bytes match exactly. Evidence records the Reborn SHA-256, transport hash,
prompt/application state, fixture resource availability, and reconnect
observation. Equivalent resource/mapping input is also checked separately for
deterministic bytes.

## Evidence contract

All generated evidence is ignored build output:

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

Reports contain relative/logical paths only. `loaded-client-mods.json` must show
the client allow-list and absence of every forbidden mod. `client-state.json`
contains real input/presentation observations; `server-state.json` contains
independent authoritative observations. `summary.json`, Markdown, and JUnit XML
are assembled from those actual results and cannot hard-code success.

Required screenshots are:

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

External-Mod runs additionally require `18-external-content.png`. Reusing the
numeric prefixes does not make screenshots interchangeable: the complete
filename allow-list and image bytes are validated.

Screenshots are review evidence, while semantic assertions remain authoritative
under Xvfb/software rendering. Empty images, a wrong filename set, missing
reports, absent input, timeout, forbidden client mod, process failure, or forced
cleanup fail the run.

## CI and reporting discipline

The dedicated Client Playtest workflow uses Java 25, Xvfb/software rendering,
bounded job/command timeouts, and a loopback-only server. It uploads only
sanitized evidence, not a world, runtime JAR cache, credentials, or secrets.
Release verification must inspect the JSON/JUnit data and uploaded artifacts;
a green compilation step is insufficient.

Local verification on 2026-07-18 completed `runClientPlaytest`,
`runProductionClientPlaytest`, and `runPlaytest` successfully. The retained
bundle contains 53/53 passing aggregate checks, 35/35 passing client steps, and
all 17 required screenshots. Its authoritative server observations include two
joins, two disconnects, three GUI opens/closes, two entity callbacks, and two
resource-pack pushes/GETs. GitHub Actions run
[`29642433900`](https://github.com/fadeway37/PolyMc-Reborn/actions/runs/29642433900)
also passed 53/53 checks and 35/35 client steps under Ubuntu 24.04/Xvfb. Its
5,218,411-byte evidence Artifact archive was downloaded, parsed, and all 17 screenshots were inspected;
standard CI run
[`29642433896`](https://github.com/fadeway37/PolyMc-Reborn/actions/runs/29642433896)
also passed and its distribution JAR was inspected.

## RC regression layers

A pure zero-mod client smoke contains no Fabric and no driver; it is not
implemented or claimed by the 0.4 RC. Multi-client, pack-policy, API-consumer,
upgrade, external-Mod, short-soak, and long-soak runs use their own evidence
roots and claims; none may be inferred from a single-client internal fixture
run. Runtime creative reverse mapping also remains fail closed and has no
online creative-slot result.

The 2026-07-20 RC candidate run completed 57/57 aggregate checks and 41/41
client steps, including the property GUI, richer entity composition, abnormal
GUI disconnect cleanup, and all required screenshots. This local evidence is
not a substitute for the final GitHub release-gate run.
