# Research notes

Research was performed as a clean-room architecture review before the 26.1.2
rewrite. The implementation sources consulted were limited to the licensed
upstream PolyMc history, Polymer's official repository, and Fabric's official
example project. No PolyMc-Extra source file was opened, copied, translated, or
adapted.

## Revisions inspected

- TheEpicBlock/PolyMc at
  `a3eaae6a56522a830b6e9a244e2bade0431a8c59` (`upstream/master`, commit date
  2025-01-28).
- Patbox/polymer branch `dev/26.1` at
  `0498b3ad7987f4c1ccc61053e143033ad728ce67` (inspected 2026-07-18).
- Patbox/polymer published `0.16.5+26.1.2` tag at
  `2253f43add91b9253d927b665e8d49288047d246`; public signatures were also
  verified from the published JAR rather than assuming the development branch.
- FabricMC/fabric-example-mod branch `26.1.2` at
  `08d183d4f86901d39762fbf1da3cc68bc635cb83` (inspected 2026-07-18).
- Published Maven metadata from Fabric and Nucleoid was used to pin the exact
  versions recorded in `gradle.properties`; no dynamic selector remains in the
  Reborn build.

Official verification points:

- [Fabric's Minecraft 26.1 development notice](https://fabricmc.net/2026/03/14/261.html)
- [Fabric Loader 0.19.3 release](https://github.com/FabricMC/fabric-loader/releases/tag/0.19.3)
- [Fabric API 0.155.2+26.1.2 release](https://github.com/FabricMC/fabric-api/releases/tag/0.155.2%2B26.1.2)
- [Fabric Loom 1.17 release line](https://github.com/FabricMC/fabric-loom/releases/tag/1.17)
- [official Fabric 26.1.2 example](https://github.com/FabricMC/fabric-example-mod/tree/26.1.2)
- [Polymer 0.16.5+26.1.2 release](https://github.com/Patbox/polymer/releases/tag/0.16.5%2B26.1.2)
- [Gradle Java toolchains documentation](https://docs.gradle.org/current/userguide/toolchains.html)
- [Foojay resolver convention 1.0.0](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention)

Fabric API `0.155.2+26.1.2` was the newest stable artifact explicitly targeting
26.1.2 at verification time; numerically newer 26.2/26.3 artifacts are not
compatible substitutes. Loom is fixed to release artifact `1.17.16`, not the
example's moving `1.17-SNAPSHOT` selector. The Gradle wrapper is fixed to 9.5.1.

The temporary Polymer and example-mod clones were created outside the project
working tree. Polymer is consumed as normal Maven dependencies, not vendored or
shaded.

## Original PolyMc architecture

The original `PolyMcEntrypoint` offered `registerPolys(PolyRegistry)` plus a
mod-specific resource callback. `PolyRegistry` accumulated mutable maps for
`ItemPoly`, `BlockPoly`, `EntityPoly`, and `GuiPoly`, an ordered list of global
item transformers, and shared resource helpers. Calling `build()` copied those
registrations into an effectively immutable `PolyMapImpl`.

`PolyMap` was the connection-facing conversion surface. It converted server
item stacks and block states, looked up entity/menu mappings, generated a
resource pack, filtered registry-backed values, and provided a creative-mode
reverse item conversion. `PolyMapProvider` allowed a connection-specific map.
The main map was created after the registries were considered closed, because
allocation order and generated assets could not safely track arbitrary dynamic
registration.

The automatic generators filled registrations not supplied by extensions:

- item conversion used item-specific `ItemPoly` plus global component
  transformers;
- block conversion allocated client block states and offered specialized
  behavior for shapes and “wizard” packet entities;
- entity conversion selected replacement entities or packet-backed helpers;
- GUI conversion attempted a chest-style representation;
- resource generation copied model/texture/sound dependencies from combined
  mod resources into a generated pack.

The useful concepts are retained—extension registration, immutable lookup,
server/client separation, resource contribution, and explainable unsupported
cases—but not the old runtime type signatures or automatic fidelity claims.

## Original packet, registry, and tag patches

The inspected upstream Mixin configuration contained a broad set of patches for
registry codecs, registry synchronization, tags, custom payloads, particles,
sounds, item components, creative restoration, block breaking/resync, entities,
GUIs, and packet-backed “wizards.” Examples include:

- `FabricRegistrySyncDisabler`, which cancelled Fabric's registry sync
  configuration path;
- `RegistryElementCodecMixin` and `RegistryFixedCodecMixin`, which replaced
  item/block/sound registry entries during network serialization;
- `SynchronizeTagsMixin`, which removed tags for registries unknown to vanilla;
- `CustomPacketDisabler`, which broadly cancelled non-vanilla custom payloads
  for vanilla-like maps;
- block/entity/GUI Mixins that changed tracking, packets, collisions, breaking,
  and menu behavior.

These patches explain both the reach of the old implementation and its update
risk. Reborn does not bulk-port that Mixin set. Polymer's supported overlay and
registry-sync mechanisms are the default boundary; the packet fallback is a
separate, disabled, no-op SPI in 0.1.

## Original limitations carried forward as explicit policy

The old documentation already warned that compatibility varied by mod, custom
renderers could not be reproduced generically, block mappings were constrained
by shape/state capacity, GUI projection was incomplete, and automatic entity
choices could be wrong. Reborn turns those warnings into planner outcomes:
lossy cases become `FALLBACK`; cases without a safe representation become
`UNSUPPORTED` rather than receiving a broad guessed mapping.

The original resource pipeline also reminded operators that generated packs
can redistribute third-party assets. Reborn retains that warning and adds path,
size, conflict, hashing, and deterministic-output controls.

## Polymer 0.16.5 for Minecraft 26.1.2

The `dev/26.1` source and published `0.16.5+26.1.2` artifacts use Minecraft's
official names and Java 25-era APIs. The published artifacts were treated as
authoritative where their surface differed from the branch. Relevant supported
mechanisms are:

- `PolymerItem` converts a real server `ItemStack` to a client item/stack, and
  published `PolymerItemUtils.registerOverlay` attaches it to an existing item;
- `PolymerBlock` converts a real server `BlockState`, and published
  `PolymerBlockUtils.registerOverlay` attaches it to an existing block;
- `PolymerSyncedObject.getSyncedObject(registry, object)` detects an interface
  implementation or previously installed registry overlay, which lets the
  planner preserve native Polymer behavior;
- the overlay registrations mark entries as server-only through Polymer's
  Registry Sync Manipulator API rather than requiring Reborn to disable all
  registry synchronization;
- `PolymerResourcePackUtils` owns the global resource-pack creator and exposes
  initialization/creation/finished events, mod asset sources, the main output
  path/UUID, and pack-state helpers;
- `ResourcePackCreator` accepts mod or path asset sources and builds through a
  `ResourcePackBuilder`/`OutputGenerator`;
- Polymer Blocks builds on Core and Resource Pack to support textured block
  representations;
- Resource Pack Extras supplies typed item-model, blockstate, model, atlas,
  font, and sound format objects. It was reviewed but is not a direct 0.1
  dependency because the current MVP does not call that API;
- Polymer Networking can negotiate optional Polymer-aware connections, but
  detecting it is not sufficient for Reborn's future `TRUSTED_MODDED` profile;
- Polymer Virtual Entity supplies a future implementation option for explicit
  entity adapters; it is not a license to auto-map arbitrary entities;
- AutoHost is an optional pack hosting/sending module and is not required by
  Reborn core.

Additional boundaries found in the published artifacts affect Reborn's safety
design:

- Polymer's default item conversion can store the full original stack in
  `$polymer:stack` custom data, but that is not an authenticated marker. Creative
  reverse mapping therefore defaults off unless Reborn's own signature/target/
  component validation is active.
- Item semantic analysis on 26.1 should use official data components such as
  `CONSUMABLE`, `EQUIPPABLE`, `BLOCKS_ATTACKS`, `TOOL`, and `WEAPON`; an old
  Yarn-era class hierarchy (including assumptions about `ArmorItem`) is not a
  reliable migration source.
- Textured full blocks must use
  `PolymerBlockResourceUtils.requestBlock(BlockModelType.FULL_BLOCK, ...)` and
  its process-wide pool. A `null` result is capacity exhaustion; a private
  allocator would collide with Polymer/other mods.
- Polymer's default ZIP output already sorts paths and normalizes timestamps,
  but building directly to the target deletes/replaces it and duplicate paths
  can be overwritten. Reborn still needs conflict detection, bounded/path-safe
  collection, a temporary output plus atomic move, and its own SHA-256 manifest.
- `PolymerSyncedObject.setSyncedObject` marks the real registry entry as
  server-only and therefore must run before the vanilla registry is frozen.
  Reborn installs overlays through one narrow injection immediately before the
  relevant registry-freeze boundary. The initialized resource-pack event is
  diagnostic only.
- Minecraft 26.1 binds some item default components during registry freeze.
  Pre-freeze registration therefore uses a conservative provisional carrier;
  `SERVER_STARTING` then locks the bound semantic carrier into the already
  registered overlay, immutable final plan, report, and mapping store. Packet
  serialization does not perform discovery.
- The published `polymer-blocks` metadata contains a stale legacy `polymc`
  entrypoint whose class is absent. Legacy entrypoint loading must enumerate
  containers, filter Reborn and Polymer provider IDs, and instantiate entries
  defensively rather than blindly invoking every declared `polymc` entrypoint.

The direct MVP dependencies are therefore Polymer Core, Blocks, and Resource
Pack at `0.16.5+26.1.2`. Public Reborn API contracts remain backend-neutral;
Polymer types are isolated in `backend.polymer`.

## Fabric 26.1.2 build and lifecycle findings

The official 26.1.2 example pins Loader 0.19.3, uses the
`net.fabricmc.fabric-loom` plugin, compiles with release 25, and does not declare
Yarn mappings. Reborn additionally uses `serverOnlyMinecraftJar()` because its
published environment is `server`, a Java 25 toolchain, reproducible archive
settings, and separate Loom server runs for GameTest and dedicated-server
smoke testing.

Fabric entrypoints are used at initialization time to collect both the new
`polymc-reborn` extensions and the recompiled legacy `polymc` bridge. Fabric
server lifecycle and command-registration callbacks are used for server checks
and Brigadier commands. The exact callback names are kept in implementation and
tests rather than reconstructed from older Yarn-era names.

## Design conclusions

1. Use Polymer overlays rather than replacing registered objects.
2. Detect native Polymer behavior before evaluating administrator/adaptor/
   heuristic candidates.
3. Sort discovery and persisted/report data by canonical identifier.
4. Freeze a fully explainable plan before hot-path conversion.
5. Persist allocations and fail visibly on corruption, migration uncertainty,
   or capacity exhaustion.
6. Keep entity, GUI, and packet expansion behind explicit SPIs until their
   invariants can be tested.
7. Offer selected legacy names for source migration only; old JAR binary
   compatibility is impossible across this Minecraft naming/runtime boundary.

These conclusions are formalized in ADRs 0001–0003.
