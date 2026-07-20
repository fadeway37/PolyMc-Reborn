# Safe standard-container GUI projection

PolyMc Reborn provides an explicit, bounded projection to vanilla generic containers. It
is not a generic converter for arbitrary mod screens.

## Registration and authority

Extensions register `GuiProjectionAdapter`s through the initialization-only
`GuiProjectionRegistry`. Each adapter names a real registered server menu type
and returns a `GuiProjection` with:

- the actual authoritative server `Container`;
- a row count from one through six;
- a complete bijection from all visible `9 * rows` slots to distinct bounded
  source-container slots;
- an explicit `GuiInteractionPolicy`.

Duplicate IDs/targets, invalid/unregistered targets, malformed slot maps, and
late registration fail. The frozen registry is sorted by menu ID and provides
O(1) identity lookup. Unknown menus remain unsupported.

The projected `GENERIC_9xN` menu creates slots that point directly at the real
container and appends Minecraft's normal player inventory/hotbar. It never
copies content into a `SimpleContainer` or creates a second authority.

## Interaction policy and reconciliation

The safe standard policy explicitly gates ordinary pickup, outside pickup,
quick move, ordinary quick-craft drag, number-key hotbar swap (0 through 8),
explicit offhand swap (button 40), throw, and pickup-all. Creative clone is
denied. Encoded buttons and slot IDs are bounds checked. Invalid/disallowed
input requests a full resync rather than being guessed.

Minecraft remains the normal server transaction authority. For integrations
that can provide a complete prediction, `GuiTransactionValidator` additionally
checks container/state IDs, monotonic sequence, changed-slot bounds/stacks,
carried stack, and exact authoritative post-click deltas. Replayed, stale,
malformed, or forged claims are rejected and resynchronized; a client claim is
never copied into the authoritative inventory.

## Lifecycle and limits

Sessions have a configured hard capacity and generation. Capacity exhaustion
rejects a new projection instead of evicting a live one. Normal close calls
`stopOpen` once and removes the matching generation; disconnect cleanup removes
all remaining sessions for the player.

The internal 27-slot fixture and client scenario exercise the projection and
independent inventory-conservation observations. The retained 2026-07-18 local
Playtest passed the GUI open, normal/Shift click, hotbar swap, drag safety,
close/reopen, and inventory-integrity checks. GitHub Actions Client Playtest
`29642433900` repeated the complete scenario successfully; see
[testing.md](testing.md).

| Capability | 0.4 RC implementation |
| --- | --- |
| explicit Java adapter | implemented |
| vanilla generic 9x1 through 9x6 presentation | implemented API |
| real server container + player inventory/hotbar | implemented |
| pickup, quick move, drag, hotbar/offhand policy | implemented |
| bounded generation-safe sessions and cleanup | implemented |
| optional strict prediction validator/resync | implemented |
| furnace/hopper/progress properties | not implemented |
| pagination/server buttons/dynamic layouts | not implemented |
| arbitrary slot subclass/custom screen | unsupported |
| automatic projection from slot count | deliberately unsupported |

The existence of a row-count API does not mean every similarly sized menu is
compatible. Adapters must document and test truthful transaction semantics.
Runtime creative clone and global creative reverse-item mapping remain disabled.

## Explicit property specialization

`GuiProjection.furnace` adds one reviewed specialization over the same
server-authoritative session machinery. It accepts exactly three real slots and
four bounded properties and presents a vanilla `FurnaceMenu`. No arbitrary
menu is inferred. See [property-gui.md](property-gui.md).

The RC upgrade fixture persists the three real slots in one barrel, exposes
four real bounded properties, and verifies the same seven-item result and
progress value across the exact 0.3 Beta-to-RC world restart. Abnormal client
disconnect and duplicate-close tests require the session count to reach zero
without affecting another player's session.
