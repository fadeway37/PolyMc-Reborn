# Legacy API compatibility

This matrix describes the 0.2 migration contract for extensions ported and
recompiled for Minecraft 26.1.2. It is not a binary-compatibility promise for
old JARs.

## Supported

| Legacy element | 26.1.2 behavior |
| --- | --- |
| Fabric entrypoint key `polymc` | Loaded during initialization in addition to `polymc-reborn`. |
| `PolyMcEntrypoint` | `registerPolys` participates in the legacy adapter collection phase. |
| `PolyRegistry` | Mutable only during registration, then adapted/frozen into the new plan. |
| `registerItemPoly` | Creates an explicit legacy item candidate for the real registered item. |
| `registerGlobalItemPoly` | Registers a deterministic global item projection/filter in registration order, subject to server safety validation. |
| `registerBlockPoly` | Creates an explicit legacy block candidate; shape/resource safety is still reported. |
| `registerEntityPoly` | Retains a legacy mapping/classification candidate; it does not automatically register the new guarded Virtual Entity adapter. |
| `registerGuiPoly` | Retains a legacy menu mapping/classification candidate; it does not automatically supply the new authoritative standard-container adapter. |
| mod-specific resource contribution | Adapted to a normalized, deterministic resource sink. |

Legacy loading is container-aware. Reborn filters its own and Polymer's provider
containers before instantiating old `polymc` entrypoints, because published
dependency metadata can contain stale optional legacy entries. One broken
third-party declaration is diagnosed with its provider ID rather than making
Reborn blindly invoke every declared entrypoint.

## Adapted

| Legacy element | Adaptation |
| --- | --- |
| `PolyMap` concept | Represented by the immutable Reborn `MappingPlan` plus backend lookups and diagnostics; it is not the old Mixin-facing implementation. |
| item/block client conversion | Applied through Polymer overlays on the existing registration rather than old serialization Mixins. |
| resource-pack callback | Runs through the Reborn/Polymer pack pipeline with traversal, size, conflict, ordering, and hashing rules. |
| client-specific map idea | Expressed through `MappingContext`/`ClientProfile`; only `VANILLA` has implemented MVP semantics. |
| debug dump | Structured decision traces, JSON/Markdown reports, counters, and `/why`. |

Adapted means the source-level purpose is retained but observable details,
signatures, or timing can differ. Read compiler errors and reports; do not cast
Reborn objects to old implementation classes.

## Deprecated

- New code should prefer `PolyMcRebornEntrypoint`, `CompatibilityProvider`,
  `ResourceContributor`, and immutable decision types rather than extending the
  legacy package.
- Direct “get main map” globals and assumptions about a mutable map are
  deprecated; use the frozen plan/diagnostic API exposed by Reborn.
- APIs that expose implementation-specific allocation managers are deprecated
  in favor of declared strategies and persistent mapping allocation.

Deprecation does not mean removal during the first alpha, but no new feature
should depend on old implementation internals.

## Postponed

- General virtual entity/wizard helpers and tick schedulers.
- Generic entity selection and equipment/passenger/broad-metadata proxying. 0.2
  supports only separately registered explicit Virtual Entity adapters.
- Generic GUI inference, paging, progress properties, and custom buttons. 0.2
  supports only separately registered explicit standard-container adapters.
- Advanced per-player non-vanilla plans for `REBORN_COMPANION` and
  `TRUSTED_MODDED`.
- Broad sound, particle, recipe-book, enchantment, and custom dynamic-registry
  compatibility beyond Polymer/native or explicit adapters.
- A separately published API artifact; 0.2 ships one distributable mod JAR.
- Runtime creative reverse conversion. The signed-marker/component-allowlist
  guard and forged-input tests exist, but 0.2 fails startup if
  `creative_reverse_mapping_enabled=true` instead of trusting Polymer's
  unsigned restoration payload.

Postponed elements have architecture hooks or roadmap entries but should not be
treated as working features.

## Impossible on 26.1.2

- Loading an unmodified PolyMc extension JAR compiled for Minecraft 1.20/1.21.
- Preserving Yarn/intermediary Minecraft descriptors in source or bytecode while
  running the official-name 26.1.2 target.
- Reproducing arbitrary client-only renderers without client code.
- Safely inferring arbitrary entity behavior or GUI transaction semantics from
  a registry type alone.
- Treating the presence of Fabric or matching mod IDs as authenticated trusted
  client state.
- Guaranteeing compatibility with every content mod.

## License notes

Types adapted from the original package retain TheEpicBlock/PolyMc copyright
and LGPL headers. New bridge helpers carry the Reborn SPDX header. This bridge
does not include PolyMc-Extra source.
