# Explicit furnace property projection

The 0.4 RC API supports an explicit `GuiProjection.furnace` adapter only. It
requires the real server menu to expose exactly one three-slot `Container` and
one four-value `ContainerData`. Reborn projects those objects directly through
a vanilla `FurnaceMenu`; it does not copy inventory or infer a furnace from slot
count.

Property reads are clamped to vanilla-safe ranges. The existing GUI session,
generation, transaction, Shift-click, carried-stack, close/disconnect, and
resync guards remain active. The production test observes start/progress/result,
Shift-clicks the real result, closes, and reopens the same authority.

Arbitrary machines, recipes, custom buttons, paging, and non-furnace property
layouts remain unsupported unless a future reviewed adapter is added.

The RC upgrade fixture backs the projection directly with the first three slots
of a persisted barrel plus four bounded properties. Its five-leg test verifies
seven real items and progress `37/100` before and after the exact 0.3 Beta-to-RC
restart; the client never becomes inventory authority.
