# ADR 0016: bounded vanilla registry sanitization

## Status

Accepted for 0.4 RC.

## Context

A server-only content Mod can register more than items and blocks. Farmer's
Delight Refabricated 26.1 registers auxiliary static types, and some dynamic
registry entries encode references to those types. Sending either identifier
to an unmodified 26.1.2 client causes login or configuration decoding to fail,
even when every visible item has a valid Polymer overlay.

Replacing or deleting the registrations would violate the project invariant:
the real Mod object and behavior must remain authoritative on the server.
Cancelling whole registry or recipe packets would also hide unrelated vanilla
content and create an unsafe compatibility claim.

## Decision

PolyMc Reborn directly depends on the exact Polymer Registry Sync Manipulator
version matching the other Polymer modules. Before built-in registries freeze,
it marks every non-vanilla static built-in-registry entry server-only for the
vanilla-client view. The server registries themselves are not modified.

Two bounded serialization filters complement that view:

- dynamic registry entries are excluded only when their encoded NBT contains
  an exact identifier that was marked server-only;
- recipe-book entries are excluded only when their registered display type or
  category is server-only.

Both filters preserve input order, return the original list when unchanged,
emit deduplicated diagnostics, and fail closed. No arbitrary payload is
cancelled, unknown packets are not swallowed, and packet fallback remains
disabled.

## Consequences

Vanilla clients can connect to the tested Farmer's Delight configuration while
the real tomato and food behavior remain on the server. A filtered dynamic
entry or recipe-book display is a deliberate presentation degradation and is
reported. This does not imply that every recipe, block entity, menu, particle,
entity, or world-generation feature of an arbitrary Mod is compatible.

The Mixin targets are deliberately limited to dynamic-registry packing and the
recipe-book add-packet constructor. Minecraft or Polymer upgrades must rerun
the three-Mod matrix, GameTest, single-client, and multi-client gates before
changing this decision.
