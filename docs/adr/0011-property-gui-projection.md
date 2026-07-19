# ADR 0011: Property GUI projection

Status: accepted for 0.3 Beta

## Decision

Property projection is explicit and uses a vanilla furnace menu backed by the
real server `Container` and `ContainerData`. The adapter owns no shadow
inventory and does not infer arbitrary menus from slot counts. Server-side
progress and transaction validation remain authoritative; a rejected client
claim requests resynchronization.

The Beta fixture demonstrates real progress-property synchronization, fuel and
result transitions, supported click paths, close/reopen behavior, and isolation
from another player's projected session.

## Consequences

This is evidence for the registered furnace adapter only. General buttons,
pagination, custom recipes, and arbitrary menu inference remain unsupported.
