# ADR 0004: Use an isolated two-process production client playtest

- Status: Accepted
- Date: 2026-07-18
- Decision owners: PolyMc Reborn maintainers

## Context

JUnit, server GameTest, and dedicated-server smoke cannot prove that a real
client connects, handles a server pack, renders vanilla surrogates, sends player
input, and reconnects. A same-JVM test server also cannot prove artifact and
dependency isolation.

Unattended CI must not use credentials, expose a public server, or install
Reborn/Polymer/content definitions on the client.

## Decision

Use two independent Loom project/process boundaries:

1. The server-only root project builds the final official-namespace distribution JAR and a
   separate non-release fixture JAR, then starts a dedicated server on loopback.
2. `playtest/client-driver` uses the client-only Minecraft JAR plus exact Fabric
   Client GameTest/resource modules and the driver. It has no dependency on the
   root project, Polymer, or fixture.
3. The orchestrator chooses loopback gameplay/pack ports, creates a clean
   bounded runtime, waits for structured readiness and the port, launches the
   client, and enforces global/scenario timeouts.
4. The driver verifies its loaded-mod allow-list, connects through normal
   multiplayer networking, handles the real pack, sends keyboard/mouse input,
   makes client assertions, disconnects/reconnects, and writes evidence.
5. The fixture records independent server observations for joins, item use,
   the basic-item inventory transition `1 -> 0 -> 1`, block state/break, GUI
   integrity, entity interactions, and disconnect.
6. A stop-request asks the server to halt normally. Forced process termination
   is a failed/incomplete cleanup fallback and cannot turn the task green.

The external-client test disables Fabric's same-JVM network synchronizer because
its target is the independent server. It does not bypass multiplayer gameplay
or resource-pack protocols.

The canonical aggregate is `./gradlew runPlaytest`; isolation and focused
entrypoints are `verifyPlaytestClientIsolation`, `runClientPlaytest`, and
`runProductionClientPlaytest`.

Required evidence is rooted at `build/playtest/`: summary JSON/Markdown, JUnit
XML, loaded-client mods, independent server/client state, three logs, and the
bounded screenshots retained with the corresponding workflow evidence.

## Terminology and acceptance

This is a **real Minecraft client with an isolated Fabric Client GameTest
driver**. It is not a pure zero-mod vanilla client. The driver contains no
server content or Reborn protocol knowledge, but it remains a client mod.

Harness source is not evidence of success. A run passes only when both
processes exit successfully, all semantic assertions/evidence validate, every
required screenshot exists, no forbidden mod loaded, and shutdown is clean.
This ADR records no local or CI result.

## Consequences

- The server uses a production-shaped artifact, not a shared development source
  set.
- Client/server cannot use shared statics or private plan access.
- Client code, fixtures, worlds, logs, screenshots, and reports remain outside
  the release JAR.
- Semantic assertions are authoritative under Xvfb/software rendering;
  screenshots are bounded review evidence.
- The test requires a separately timed CI job and sanitized artifact upload.
- Pure zero-mod vanilla automation and external-mod testing remain independent
  P1 layers.

## Alternatives rejected

- Server GameTest only: no real client networking/rendering/input.
- Same-JVM server: weak artifact/classpath isolation.
- Reborn/Polymer on client: invalidates the vanilla-facing boundary.
- OS-coordinate-only automation: less stable than Fabric input APIs.
- Calling fixture methods: bypasses player/network behavior.
- Calling the driver a pure vanilla test: factually incorrect.
