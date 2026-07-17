# Architecture

PolyMc Reborn is a planning layer around a maintained presentation backend. It
does not replace a mod's server registrations. A server `Item`, `Block`, entity
type, or menu type remains the authoritative object; only the value serialized
to a particular client is overlaid or transformed.

## Data flow

```text
Fabric registries and loaded-mod metadata
                 |
        stable ContentDescriptor discovery
                 |
      CompatibilityRegistry providers
                 |
      candidates + complete reason chains
                 |
             MappingPlanner
        / validation / persistence
                 |
       immutable final MappingPlan
                 |
       CompatibilityBackend dispatch
        |          |           |
   item overlay block overlay resource pack
        |          |           |
        +---- diagnostics/reports ----+
```

The key separation is between deciding and applying. Providers inspect a
normalized descriptor and propose a mapping candidate. The planner resolves
all candidates and emits an immutable proposed plan. The backend validates
persisted allocations/capacity, installs selected overlays, and publishes the
immutable final plan.

## Core model

### ContentDescriptor

A descriptor identifies one server registration using its registry type,
canonical registry ID, owner mod, content kind, state/property information,
shape/block-entity facts where applicable, and normalized behavior/component
hints. It contains no client-only classes. Discovery sorts descriptors by
registry ID and then content kind, never by registry/`HashMap` iteration order.

### CompatibilityRegistry and providers

The registry holds providers in explicit priority tiers. A
`CompatibilityProvider` is a pure candidate producer: it explains whether it
can handle a descriptor and what backend strategy/carrier/resources it needs.
Providers do not write files or mutate registries. Their deterministic order is
defined in [compatibility-model.md](compatibility-model.md).

### MappingPlanner and MappingPlan

The planner combines provider candidates and administrator policy in stable
order, while preserving every accepted/rejected candidate explanation. The
Polymer backend then loads the mapping store, replays valid item carriers and
the shared full-block pool, checks carrier existence/capacity, and refuses a
persisted replay that would silently change a block carrier.

The output is an immutable `MappingPlan` keyed for O(1) lookup. A
`MappingDecision` includes status, selected provider/backend/strategy,
confidence, degradation, the complete reason chain, resources, warnings, and a
failure reason. Diagnostics derive counters and per-mod summaries from the plan.

### Backends

`CompatibilityBackend` applies already-selected decisions. The default
implementation lives in `backend.polymer` and is the only package that should
expose Polymer types. It installs overlays on existing item/block objects and
connects resource contributions to Polymer's pack API.

`PacketFallbackBackend` is a separate experimental SPI. The 0.1 implementation
is a disabled no-op that reports its state. It does not cancel or rewrite
arbitrary packets. Future implementations must classify a packet as allow,
transform, or reject-with-reason and remain bounded to explicitly supported
protocol cases.

## Lifecycle

### Phase 1: static planning

During Fabric mod initialization, Reborn:

1. validates its own mod/container conflicts and main configuration;
2. registers built-in and Java providers;
3. loads `polymc-reborn` extensions;
4. loads recompiled legacy `polymc` extensions through the bridge;
5. registers commands and lifecycle callbacks.

After all Fabric entrypoints have run, a narrow Mixin observes the head of the
first relevant `MappedRegistry.freeze()` call for items, blocks, entity types,
or menus. While registry writes are still legal, its idempotent guard:

1. discovers static registries in stable order;
2. resolves the plan, validates/persists allocations, and installs overlays;
3. collects the normalized resource snapshot and writes compatibility reports;
4. publishes the final immutable plan.

The injection does not inspect, cancel, or transform packets. It installs
server-only overlays immediately before the first relevant registry freezes.
Minecraft binds item default components later, so `SERVER_STARTING` performs a
second, registry-read-only pass that replaces the provisional immutable plan
with the final immutable plan, persists exact semantic carriers, and refreshes
reports before players can join.

The exact ordering is enforced by implementation tests. Extensions must not
expect a command to alter the plan later.

### Phase 2: server validation

Polymer's resource-pack initialized event records a diagnostic; its creation
event consumes the already frozen resource snapshot. When the server is fully
started, Reborn enumerates the available server registry views and records a
server-dependent diagnostic. Compatibility reports were already written at
static freeze, while a resource-pack report is written after an actual pack
build. This phase does not silently remap the frozen plan.

## Polymer backend

Polymer 0.16.5+26.1.2 supports overlays on already-registered objects through
`PolymerItemUtils.registerOverlay` and `PolymerBlockUtils.registerOverlay`.
Native support is detected with
`PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM/BLOCK, object)` and
an `instanceof PolymerItem`/`PolymerBlock` check, not only by checking the real
object class. Consequently the mod object remains real in inventories, worlds,
recipes, tags, and calls from other server mods, while Polymer supplies the
vanilla-facing representation at its supported serialization boundaries.

The backend focuses on:

- conservative item carriers and safe component projection;
- simple full-cube block states with matching collision/outline assumptions;
- corresponding block-item decisions;
- resource contribution and registry-server-only integration supplied by
  Polymer;
- diagnostics for unsafe shapes, block entities, entities, and menus.

Native Polymer is not silently replaced. An administrator must both write an
explicit override rule and enable `override_native_polymer`; the resulting
dangerous decision remains visible in reports.

## Items and creative reverse mapping

The item planner chooses a semantic carrier only when observable server data
supports that category: food/drink, tool, armor, bow/crossbow-like, shield-like,
throwable, block item, or material. Minecraft 26.1 binds some item default
components after Polymer requires server-only entries to be marked. The
pre-freeze overlay therefore starts with a conservative material floor. At
`SERVER_STARTING`, Reborn reads the now-bound component map, locks one carrier
into the overlay, final immutable plan, report, and `mappings-v1.json`, and then
never changes that carrier in a packet hot path. Client serialization retains
safe display information, count, damage, and supported effects while dropping
unregistered/custom components that a vanilla client cannot decode.

Reverse mapping is a separate trust boundary. Polymer's default `$polymer:stack`
custom data contains restoration information but is not a Reborn authenticity
signature. Reborn therefore defaults creative reversal to disabled. An enabled
implementation may restore only when a Reborn-generated marker verifies
against the current mapping, names a valid target ID, and contains only allowed
components. A missing, malformed, stale, or forged marker is rejected; Reborn
does not infer a server item from its visual carrier.

## Blocks

Block discovery inspects every possible state, then records aggregate full-cube,
stable-shape, block-entity, and state-count facts. Automatic 0.1 support is
limited to safe complete cubes. The automatic backend allocates and persists
one visual full-block carrier per registry ID; the v1 store's optional state key
is reserved for a future per-state algorithm. Distinct state visuals require a
native or explicit adapter. The associated `BlockItem` receives an item mapping.

Doors, beds, fences, stairs, multi-block structures, special redstone behavior,
height-dynamic blocks, non-full cubes, and unhandled block-entity presentations
are fallback/unsupported unless an explicit native or Java adapter safely
handles them. Reborn does not manually claim a legacy note-block state pool when
Polymer Blocks offers the maintained abstraction. Textured full blocks request
a carrier from Polymer Blocks' process-wide `PolymerBlockResourceUtils` pool.
Reborn does not create a private allocator that could collide with other mods;
a `null` allocation is capacity exhaustion and persisted assignments must be
replayed and validated in stable order.

## Entities and menus

Entity types and screen-handler/menu types are discovered and included in
reports. Native or explicit adapters can contribute a decision, but 0.1 has no
generic entity or GUI generator.

The entity SPI is designed for future Polymer Virtual Entity implementations,
equipment, passengers, metadata, interaction proxying, and explicit vanilla
replacement types. The GUI SPI reserves standard container projection, slot
mapping, progress/properties, paging, server buttons, and transaction
validation. Shift-click, drag, offhand/hotbar actions, and desynchronization
recovery are required before a generic implementation can be called safe.

## Persistent and generated state

`mapping.PersistentMappingStore` owns `mappings-v1.json`. It uses schema and
algorithm versions, strict validation, canonical sorting, an adjacent temporary
file, and atomic replacement where the filesystem supports it. An incompatible
migration creates a backup before changing the primary file. Corruption is fatal
and capacity exhaustion is an explicit diagnostic.

The resource pipeline reads files only during startup or an administrator pack
build. It normalizes logical paths, bounds extraction/caches, traverses model
dependencies, detects duplicates/conflicts/missing textures, and writes stable
archives and manifests. Packet conversion never performs filesystem I/O.

## Client profiles

- `VANILLA`: the only fully acted-on MVP profile; unknown clients use it.
- `REBORN_COMPANION`: reserved for an optional future quality-of-life client,
  never required for login.
- `TRUSTED_MODDED`: reserved for authenticated exact registry and mod
  fingerprints. Fabric presence alone is not trust.

The client profile is part of mapping context. The only active 0.1 profile is
`VANILLA`; its per-overlay semantic item cache is bounded to one carrier and
reports hits/misses. Generated resource-pack cache reuse is byte-for-byte.

## Dedicated-server safety

`fabric.mod.json` declares `environment: server`; shared initialization imports
no client classes. The build uses Loom's server-only Minecraft JAR and provides
a dedicated-server run. Optional client concepts are values/protocol policy,
not references to client implementation classes.

## Observability

Startup logs one concise status summary. Structured diagnostics drive JSON and
Markdown reports and the `why` command, so log wording is not the only evidence
for a decision. Report serialization uses logical mod/registry/resource IDs and
does not emit absolute local paths by default. A narrow `SystemReport` Mixin adds
a path-free PolyMc Reborn version/plan/error summary to Minecraft crash reports;
it does not alter crash handling.
