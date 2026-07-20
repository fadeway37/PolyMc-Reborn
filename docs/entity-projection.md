# Explicit virtual-entity projection

Entity support is explicit, experimental, and deliberately narrow. A
custom type without a registered adapter remains `UNSUPPORTED`; Reborn never
chooses a surrogate merely because it looks similar.

## Adapter contract

Extensions register during initialization. An adapter names a stable ID, real
registered target type, registered vanilla surrogate type, finite visual
offset, bounded interaction distance, and explicit use/attack callbacks.
Duplicate IDs/targets, non-vanilla surrogates, invalid bounds, and late
registration fail. Native Polymer behavior wins, and vanilla target types are
not replaced.

## Runtime model

For an explicitly mapped real entity, the Polymer backend creates a virtual
vanilla element, applies the adapter offset, and attaches it to the real entity.
The custom anchor packets are hidden from the vanilla client. The real entity
continues to own position, lifetime, health, damage, AI, persistence, and mod
logic.

The implemented synchronization surface is attachment position/tracking,
rotation, custom name/name visibility, and glowing state. The holder is removed
when the exact entity/level unloads, a new generation replaces it, or the server
stops.

## Interaction authorization

Use/attack callbacks run only when the session is active/current, the generation
matches, source/player are alive and in the recorded level, the player is
watching the holder, distance is finite and within the adapter bound, and use
hit data is finite. Rejected/stale input does nothing. Adapter exceptions are
logged and fail closed.

The fixture explicitly maps its real custom entity to an armor stand, exposes a
visible name/glow, and counts callbacks against the real entity. The isolated
client scenario observes spawn/movement and sends real use/attack input. The
retained 2026-07-18 local Playtest passed both callbacks and the corresponding
client screenshots; GitHub Actions Client Playtest `29642433900` repeated the
complete scenario successfully.

| Capability | 0.4 RC implementation |
| --- | --- |
| explicit registration and stable frozen lookup | implemented |
| native Polymer preservation | implemented |
| vanilla virtual surrogate | implemented |
| position/tracking, rotation, name, glowing | implemented |
| guarded use and attack callbacks | implemented |
| unload/replacement/server-stop cleanup | implemented |
| bounded explicit vanilla equipment | implemented in 0.3 Beta |
| one explicit vanilla passenger | implemented in 0.3 Beta |
| leashes, poses, animations | not implemented |
| arbitrary tracked-data mapping | not implemented |
| dimension-transfer specialization | not implemented |
| automatic surrogate heuristic | deliberately unsupported |

The active projection map is lifecycle-cleaned and has a configured hard cap;
capacity exhaustion skips new projections with an explicit diagnostic. Each
entity's interaction replay guard is also bounded to 1,024 keys.
The surrogate is only a presentation and does not promise an identical hitbox
or animation; adapters must document degradation.

## Explicit composition

An adapter may declare one vanilla passenger and bounded vanilla equipment.
Stacks are created only after component binding, and watcher packets are sent
per live session. The production client asserts one parrot passenger and a
golden helmet. Automatic surrogate/passenger/equipment choice, arbitrary
metadata, and leashes remain unsupported.

The RC upgrade fixture adds a persistent real custom entity with an explicit
armor-stand projection. The five-leg upgrade gate forces its owning chunk,
checks one stable UUID and server value across the exact 0.3 Beta-to-RC restart,
and requires each real client to observe the surrogate. Duplicate cleanup is
idempotent; stale generations and abnormal disconnects cannot invoke the real
entity or leave an active projection.
