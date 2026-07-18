# PolyMc Reborn 0.2 research notes

These notes record the API and architecture research behind the 0.2
"Interactive Compatibility Alpha" work. They describe the implementation in
this repository, not a general promise that arbitrary content mods work.

## Baseline and invariants

The work continues from 0.1 on Minecraft 26.1.2, Fabric, Java 25, and official
Minecraft names. The real modded `Item`, `Block`, `Entity`, `Container`, and
`MenuType` remain authoritative on the server. Polymer or a vanilla menu is a
client presentation only. Native Polymer implementations continue to win over
Reborn adapters.

The dependency pins used by the implementation are:

| Component | Exact version |
|---|---|
| Minecraft | `26.1.2` |
| Fabric Loader | `0.19.3` |
| Fabric Loom | `1.17.16` |
| Fabric API | `0.155.2+26.1.2` |
| Polymer Core, Blocks, Resource Pack, Virtual Entity | `0.16.5+26.1.2` |
| Java toolchain/release | `25` |

No Yarn mapping layer, intermediary-named source, local JAR repository, or
dynamic dependency is involved.

## Fabric Client GameTest and Loom findings

The Fabric API artifact selected for 26.1.2 exposes the client test entrypoint
as `FabricClientGameTest#runTest(ClientGameTestContext)`. The implementation
uses the current `ClientGameTestContext` operations for client-thread work,
polling, screenshots, and live state queries, plus `TestInput` for key, mouse,
camera, and cursor input. The exact client-side modules resolved from the
Fabric API pin are:

- `fabric-client-gametest-api-v1` `5.1.0+0a5283664c`;
- `fabric-resource-loader-v1` `2.0.10+7c44c7324c`.

Fabric's client-test world builder can create a server in the client JVM. That
does not meet the isolation requirement. The 0.2 harness therefore uses two
Loom production run tasks instead:

- the root server-only project launches `ServerProductionRunTask` with the
  built Reborn JAR and a separately built fixture JAR;
- `playtest/client-driver` uses `clientOnlyMinecraftJar()` and
  `ClientProductionRunTask` with only the client driver and its two Fabric API
  modules;
- PowerShell and POSIX orchestrators allocate loopback ports, start the server,
  wait for a readiness file, run the client, request a normal server stop, and
  verify both reports and the exact evidence/screenshot contract under
  `build/playtest/`.

`ClientProductionRunTask.useXVFB` is enabled in CI environments. The client
test network synchronizer is explicitly disabled because the target server is
an independent process, not the synchronizer's same-JVM test server. This does
not bypass gameplay networking: connection, resource-pack download, world
state, inventory, menu, and entity assertions still cross the real client/
server connection.

The driver uses `ConnectScreen.startConnecting` to reach the external server.
It is a real Minecraft client, but not a zero-mod vanilla client. It contains
Fabric Loader, the two Fabric test/resource modules, and the driver itself.
It deliberately rejects Reborn, Polymer, `polymc`, the fixture mod, and any
unexpected client mod.

## Minecraft container findings

Minecraft 26.1.2 uses `ContainerInput` for `PICKUP`, `QUICK_MOVE`, `SWAP`,
`CLONE`, `THROW`, `QUICK_CRAFT`, and `PICKUP_ALL`. Standard chest presentations
are the six `MenuType.GENERIC_9xN` types. `AbstractContainerMenu` already owns
server-side slot mutation, quick-craft state, state IDs, remote hashed stacks,
and full-state resynchronization.

Polymer Core marks server-only menu types but does not provide a generic,
transaction-safe menu projection framework. Reborn therefore builds a narrow
vanilla `AbstractContainerMenu`:

- every visible content slot maps bijectively to one slot in the real
  `Container`;
- the player main inventory and hotbar use Minecraft's standard layout;
- an explicit interaction policy gates quick move, drag, hotbar/offhand swap,
  creative clone, throw, and pickup-all behavior;
- the ordinary packet path keeps Minecraft's state-ID and hashed-stack
  reconciliation; an additional explicit `transact` API can compare a complete
  client prediction with the authoritative post-click state and request a full
  resync.

This research ruled out guessing arbitrary slot subclasses, buttons, dynamic
layouts, and custom screens.

## Polymer Virtual Entity findings

The pinned Virtual Entity module supplies `ElementHolder`,
`EntityAttachment.ofTicking`, `SimpleEntityElement`, and guarded interaction
handlers. The safe pattern is to anchor a vanilla virtual element to the real
mod entity. Wrapping or sending the real custom entity type would expose an
unknown registry entry to a vanilla client.

The 0.2 backend therefore:

- accepts only explicitly registered adapters;
- hides the real entity's Polymer anchor packets;
- attaches a vanilla surrogate element to the real entity;
- synchronizes attachment position plus rotation, custom name/name visibility,
  and glowing state;
- validates active session, generation, entity/player liveness, dimension,
  tracking, finite hit position, and distance before invoking explicit use or
  attack callbacks;
- destroys the holder on unload or server stop.

Inspection of the pinned Virtual Entity bytecode confirmed that
`SimpleEntityElement.setRotation` accepts `(pitch, yaw)`. The backend therefore
passes Minecraft `XRot` before `YRot`; a focused regression test protects this
order because swapping them produces a visible but deceptively plausible
projection error.

The implementation does not generically infer surrogate entities. Equipment,
passengers, leashes, and arbitrary metadata remain outside the 0.2 backend.

## Stateful full-block findings

Polymer Blocks provides a process-wide full-block carrier allocator, not an
API for selecting an arbitrary previously used carrier. Deterministic replay
therefore validates allocations against the known reconstructable prefix and
refuses any carrier mismatch.

The existing mapping schema already has a `state` field. Per-state full-cube
support can use that field without an artificial schema or algorithm bump.
`BlockStateKey` sorts property names and serializes canonical values. The
backend allocates/replays one carrier for every server state and publishes an
immutable identity lookup for packet-time O(1) access. A legacy empty-state
record can seed a property-bearing block's default state and is retired when
the canonical state record is written.

Full-cube geometry alone is insufficient for a safe carrier. The discovery
heuristic also compares destruction progress semantics against Polymer's
full-block carrier and sets `breaking_semantics_safe=false` when the custom
block is materially faster or otherwise unsafe. Such blocks fail closed instead
of gaining a client-visible shape with server-inconsistent breaking behavior.

`BlockStateModelResolver` accepts only a bounded `variants` blockstate file,
requires exactly one matching variant per state, validates rotations and
weights, walks parent models and textures to a maximum depth of 32, and caps
resolved resources at 8 MiB. Multipart models, missing non-vanilla resources,
ambiguous variants, and unsafe paths fail closed.

## Mapping operations findings

Schema 1 and algorithm `reborn-2` remain current. `MappingPlanDiff` performs a
stable key-sorted comparison and reports added, removed, preserved, reassigned,
invalidated, resource-changed, and capacity-risk entries. Dry-run serializes a
proposed snapshot in memory and does not write it.

Backups contain the mapping bytes plus strict metadata, SHA-256, size,
Minecraft version, project version, schema, and algorithm. Backup IDs are
validated before paths are resolved. Rollback writes validated pending data and
metadata, creates a safety backup of the current store, and activates only
before planning on the next restart. A partial pending pair, checksum mismatch,
wrong Minecraft version, corrupt JSON, or incompatible algorithm stops startup
instead of discarding mappings.

## Scope decisions

The internal fixture and isolated client driver are the implemented test
vehicle. They are not third-party compatibility evidence. At the time these
notes were written, no pure zero-mod vanilla-client automation and no pinned
external-mod matrix had been executed. Runtime creative reverse mapping also
remains disabled: Polymer restoration data is not a Reborn-authenticated proof.
Those P1 items must remain marked skipped until their separate security and
execution gates are met.
