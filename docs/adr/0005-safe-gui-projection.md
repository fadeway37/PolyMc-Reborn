# ADR 0005: Limit GUI projection to explicit standard containers

- Status: Accepted
- Date: 2026-07-18
- Decision owners: PolyMc Reborn maintainers

## Context

A menu is a server transaction boundary, not merely a slot layout. Guessing a
screen can duplicate/delete items or desynchronize pickup, quick move, drag,
hotbar/offhand, carried stacks, and close behavior. Polymer can hide a custom
menu registration but does not make arbitrary transactions safe.

## Decision

0.2 accepts only initialization-time adapters in `GuiProjectionRegistry`. An
adapter supplies the real authoritative `Container`, one-to-six-row vanilla
generic-container shape, complete bijective slot mapping, and explicit
interaction policy. Registrations are validated, deduplicated, frozen in stable
target-ID order, and queried in O(1). Unknown menus remain unsupported.

The projected menu points every visible `Slot` directly at the real container
and appends standard player inventory/hotbar slots. It never copies content into
a shadow inventory.

The safe policy gates pickup, outside pickup, quick move, ordinary quick-craft,
hotbar/offhand swap, throw, and pickup-all. Creative clone is denied. Encodings
and indices are bounded. Invalid/disallowed input requests full resync.

Minecraft remains the normal server authority. Where a complete prediction is
available, an additional validator checks container/state IDs, monotonic
sequence, changed slots/stacks, carried stack, and exact post-click deltas. A
client prediction is never inventory authority.

Sessions are capacity-bounded and generation-safe. Close calls `stopOpen` once;
disconnect removes remaining sessions. Capacity exhaustion rejects rather than
evicting a live projection.

## Supported boundary

The API supports explicit generic 9x1 through 9x6 projections and an internal
27-slot fixture. It does not generically support furnace/hopper/anvil/merchant
properties, custom buttons, paging, dynamic layouts, arbitrary slot subclasses,
custom screens, or automatic inference by slot count.

## Consequences

- The owning mod's real container behavior remains authoritative.
- Vanilla clients see only standard menu types.
- Adapter authors must model truthful semantics and test hostile transactions.
- Unsupported breadth remains visible instead of guessed.

## Alternatives rejected

- Copying into `SimpleContainer`: creates two authorities.
- Guessing from slot count: ignores restrictions/properties/buttons.
- Accepting every input: enables unsafe clone/encodings.
- Broad packet Mixin: expands protocol risk unnecessarily.
- Evicting live sessions at capacity: risks orphaned close state.
