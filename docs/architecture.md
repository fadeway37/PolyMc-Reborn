# Architecture

PolyMc Reborn plans and applies vanilla-facing presentations without replacing
authoritative server registrations. A mod's `Item`, `Block`, entity, menu type,
container, and gameplay logic remain real server objects.

## Compatibility flow

```text
Fabric registries + loaded-mod metadata
                |
      stable ContentDescriptor discovery
                |
     ordered CompatibilityProvider candidates
                |
            MappingPlanner
      validation + persisted replay
                |
       immutable final MappingPlan
                |
       CompatibilityBackend dispatch
       /        |         |         \
    items   state blocks  pack   explicit GUI/entity
                |
       diagnostics and reports
```

Providers decide and explain; backends apply. A provider proposes a candidate
with strategy, confidence, degradation, reason chain, resources, warnings, and
failure information. It does not mutate registries or write files. The planner
resolves candidates in deterministic priority order and publishes an immutable
plan with O(1) lookup.

Discovery sorts canonical registry IDs and content kind. Provider ties use
stable IDs, never registration order or map iteration. Native Polymer overlays
remain first priority unless both an explicit administrator rule and the
dangerous `override_native_polymer` switch authorize replacement.

## Lifecycle

During initialization Reborn validates configuration/container conflicts,
registers built-in providers, loads `polymc-reborn` extensions, adapts selected
recompiled `polymc` entrypoints, and registers lifecycle/command hooks.

A narrow registry-freeze hook performs stable discovery while Polymer can still
mark server-only registrations. It installs provisional overlays and publishes
an immutable plan. At `SERVER_STARTING`, after Minecraft has bound default item
components, a registry-read-only pass finalizes semantic carriers, persistent
records, resource inputs, and reports before players join. Commands can inspect
that plan but cannot remap the live server.

Pending rollback activation is even earlier: a complete pending data/metadata
pair is validated and atomically activated before static planning. A partial or
invalid pair blocks startup rather than being ignored.

## Polymer backend

Direct Polymer types stay in `io.github.polymcreborn.backend.polymer`. The
backend attaches overlays to already registered objects, uses Polymer Blocks'
shared full-block carrier pool, contributes normalized assets to Polymer's pack
pipeline, marks server-only registrations, and drives explicit Virtual Entity
projections. The public API remains backend-neutral.

Unsupported/error block registrations that would otherwise leak an unknown
state are quarantined with a conservative barrier presentation for protocol
safety. Their compatibility status stays `UNSUPPORTED`/`ERROR`, with the
quarantine backend and degradation visible; quarantine is not a claim that the
block behavior or visuals are supported.

The same freeze-time safety pass marks unsupported/error custom entity types
as Polymer server-only types with an invisible marker quarantine, and marks
unsupported/error custom menu types as server-only. These decisions retain
their unsupported status and carry an explicit `polymer-quarantine` backend
reason. The pass does not invent an entity surrogate or menu layout; it only
prevents unknown registry entries from leaking to a vanilla client.

The separate `PacketFallbackBackend` remains experimental and disabled. Its
no-op/audit boundary does not cancel arbitrary packets or disable registry
validation.

## Items

Item analysis selects a semantic carrier only when server-visible behavior and
bound data components justify a category such as food/drink, tool, armor,
bow/crossbow, shield, throwable, block item, or generic material. The selected
carrier is fixed before players join. Safe display information, count, damage,
and supported visual effects are projected; unregistered/custom client
components are filtered. Client-only renderers are never inferred.

Creative reverse mapping is a separate trust boundary and remains disabled.
Polymer's ordinary restoration data is not a Reborn authenticity signature.
Malformed, stale, forged, wrong-ID, or disallowed-component markers are rejected
by the guard, but 0.2 does not connect that guard to the live creative packet
path.

## Stateful full-cube blocks

For a candidate block, discovery examines all states, collision/outline shape,
block-entity association, and properties. Automatic state projection is limited
to stable complete cubes with an unambiguous bounded `variants` model graph.

`BlockStateKey` serializes property names/values canonically. Every safe state
gets an immutable O(1) mapping. Persisted carriers replay first in stable order;
new registry-ID/state keys append without reordering valid existing assignments.
A legacy empty default-state record can seed the canonical default state.

Missing/ambiguous variants, multipart or unsafe paths, capacity exhaustion,
carrier replay mismatch, complex shapes, doors, beds, fences, stairs, dynamic
geometry, and unmodelled block-entity presentation fail closed. Associated
block items use the selected block mapping where safe.

## Safe standard-container GUI projection

GUI support is explicit, not inferred. `GuiProjectionRegistry` freezes adapters
in stable menu-ID order. An adapter supplies the real authoritative server
`Container`, a one-to-six-row vanilla generic-container shape, a complete
bijective slot mapping, and an explicit interaction policy.

The projected vanilla menu points every content `Slot` directly at the real
container and adds Minecraft's standard player inventory/hotbar. There is no
shadow inventory. Normal pickup, quick move, bounded drag, hotbar/offhand swap,
throw, and pickup-all are policy-gated; creative clone is denied. Illegal slot
or button values request full resynchronization.

Generation-safe sessions have a configured capacity and clean up on normal
close/disconnect. The optional strict transaction validator checks container/
state IDs, monotonic sequence, changed slots, carried stack, and exact
authoritative post-click deltas. It never trusts a client prediction as
inventory state.

Furnace/property menus, custom buttons, paging, dynamic layouts, arbitrary slot
subclasses, and custom client screens are not implemented. See
[gui-projection.md](gui-projection.md) and
[ADR 0005](adr/0005-safe-gui-projection.md).

## Explicit virtual-entity projection

Entity support is likewise explicit. An adapter names one custom target type,
a registered vanilla surrogate, a finite visual offset and interaction radius,
and approved use/attack callbacks. Native Polymer behavior and vanilla targets
are not replaced.

The Polymer backend anchors a virtual vanilla element to the real entity and
synchronizes position/tracking, rotation, custom name/name visibility, and
glowing state. The real entity retains health, AI, persistence, damage, and all
game mechanics. Interactions require a current generation, live same-level
source/player, active tracking, finite hit data, and bounded distance. Holders
are removed on unload/replacement/stop.

Equipment, passengers, leashes, arbitrary tracked data, animations, and generic
surrogate inference are not implemented. See
[entity-projection.md](entity-projection.md) and
[ADR 0006](adr/0006-explicit-entity-projection.md).

## Mapping persistence and operations

`mappings-v1.json` retains schema `1` and algorithm `reborn-2`. It validates
strictly, sorts records canonically, writes through an adjacent temporary file,
and replaces atomically where supported. Corruption never causes silent empty
regeneration.

Startup produces a stable mapping diff. Operator commands expose status,
validation, diff, dry run, checksummed backup, and rollback preparation. Dry
run hashes an in-memory proposal and does not write. Rollback validates path,
metadata, checksum, size, schema, algorithm, and Minecraft version, creates a
safety backup, and stages pending files for restart. See
[mapping-migration.md](mapping-migration.md) and
[ADR 0007](adr/0007-mapping-migration-and-rollback.md).

## Resource pack and diagnostics

Filesystem work occurs only during startup or explicit pack build, never in a
packet hot path. Resource paths are normalized and bounded; model dependencies
are traversed with cycle/depth/size guards; conflicts, missing assets, and
traversal attempts are diagnostics. Stable ZIP order/timestamps and canonical
manifests make equivalent input byte-identical.

JSON and Markdown reports use logical IDs, not sensitive absolute paths.
Counters include compatibility statuses plus GUI/entity operational metrics and
mapping/resource state where available. `/why` renders accepted and rejected
provider candidates.

## Client profiles and dedicated-server safety

`VANILLA` is the only active profile. `REBORN_COMPANION` and `TRUSTED_MODDED`
are future values; Fabric presence is not authenticated trust.

`fabric.mod.json` declares `environment: server`; production initialization
does not reference client-only Minecraft classes. The Client GameTest driver is
a separate non-release project with no dependency on Reborn, Polymer, or server
fixtures.

## Two-process production playtest

The server process runs the final official-namespace production JAR plus pinned runtime
dependencies and a separate fixture JAR. The client process is a real Minecraft
26.1.2 client with only the minimal Fabric Client GameTest/resource modules and
automation driver. It rejects Reborn, Polymer, fixtures, and content definitions
on its classpath.

Both communicate only through loopback multiplayer/resource-pack protocols and
filesystem evidence. The driver uses real keyboard/mouse input APIs; the server
records independent observations. Passing requires both processes, assertions,
clean shutdown, and the complete `build/playtest/` evidence contract. This is
not a pure zero-mod vanilla-client test. See
[client-playtest.md](client-playtest.md) and
[ADR 0004](adr/0004-client-playtest-architecture.md).

## 0.3 Beta boundaries

The public API is also published as `io.github.polymcreborn:polymc-reborn-api`
without backend implementation classes. Furnace projection, explicit entity
composition, pack state, diagnostic policy, and support bundling remain narrow
services around the immutable plan. Two-client state is per player/connection;
clients share no statics or private plan access. Upgrade testing runs audited
0.2 and current JARs against one persistent store.
