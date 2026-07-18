# ADR 0006: Require explicit entity projection through virtual surrogates

- Status: Accepted
- Date: 2026-07-18
- Decision owners: PolyMc Reborn maintainers

## Context

A vanilla client cannot decode an unknown entity type. Automatically choosing a
similar vanilla type cannot preserve interaction, hitbox, metadata, lifecycle,
or gameplay semantics. A real vanilla replacement would also violate server
authority.

## Decision

Entity projection is accepted only through initialization-time
`EntityProjectionRegistry` adapters. Each adapter names one registered custom
target, registered vanilla surrogate, finite offset, bounded interaction
distance, and explicit use/attack callbacks. Duplicate IDs/targets, invalid
bounds, non-vanilla surrogates, and late registration fail. Native Polymer
behavior and vanilla targets are not replaced.

For a real mapped entity, the Polymer backend creates a virtual vanilla element
anchored through a ticking attachment and hides the custom anchor packets. It
synchronizes position/tracking, rotation, custom name/name visibility, and
glowing state. The real entity retains health, AI, persistence, damage, and all
server logic.

Before forwarding use/attack, the backend verifies active/current generation,
source/player liveness, recorded level, holder tracking, finite hit data, and
bounded distance. Failure does nothing; adapter exceptions fail closed. The
holder is destroyed on unload, generation replacement, or server stop.

## Implemented alpha boundary

The internal fixture explicitly maps a real custom entity to an armor stand and
records real use/attack callbacks. Equipment, passengers, leashes, poses,
animations, arbitrary tracked data, dimension specialization, automatic
surrogate inference, and an independent configured population cap are not
implemented.

## Consequences

- Vanilla clients receive only a registered vanilla presentation.
- Public adapter contracts stay backend-neutral.
- Server mechanics/authorization remain attached to the real entity.
- Higher fidelity requires a reviewed explicit adapter and tests.
- A surrogate is not a promise of identical hitbox/animation.

## Alternatives rejected

- Send the custom type: unsafe for a vanilla client.
- Guess a similar type: misleading and behaviorally unsafe.
- Spawn a real vanilla replacement: duplicates mechanics and breaks authority.
- Forward stale/untracked interactions: authorization vulnerability.
- Port broad legacy entity Mixins: excessive unreviewed protocol surface.
