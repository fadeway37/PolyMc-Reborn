# Compatibility model

Compatibility is decided per canonical registry entry and per client profile.
It is not a Boolean attached to an entire mod. A mod can have native items,
explicitly adapted blocks, unsupported entities, and a fallback menu in the
same report.

## Provider resolution order

The default tiers, highest first, are:

1. **Native Polymer implementation**
2. **Administrator forced rule**
3. **Explicit PolyMc Reborn Java adapter**
4. **Legacy PolyMc adapter**
5. **Bundled declarative compatibility profile**
6. **Automatic heuristic provider**
7. **Safe fallback**
8. **Unsupported**

An administrator rule does not normally displace tier 1. It can do so only if
the matching rule explicitly requests native override **and** the main
configuration has `override_native_polymer: true`. Otherwise the native result
wins and the forced-provider candidate trace explains that the global gate is
disabled when a forced rule matched.

Provider instances within a tier sort by stable provider ID. Inside the
declarative provider, profiles sort by numeric priority descending and then
profile ID; rules retain their explicit JSON array order. Discovery order is
canonical registry ID followed by content kind. Provider registration order
and map iteration never break a tie.

## Statuses

| Status | Meaning |
| --- | --- |
| `NATIVE` | The registered content already supplies a Polymer implementation/overlay. |
| `EXPLICIT` | A new Java adapter explicitly handles the entry. |
| `LEGACY` | A recompiled legacy `polymc` adapter supplied the mapping. |
| `PROFILE` | A validated declarative rule supplied the decision. |
| `HEURISTIC` | Conservative automatic analysis selected a representation. |
| `FALLBACK` | A safe but materially degraded representation is used. |
| `UNSUPPORTED` | No safe representation was found; the entry is reported. |
| `ERROR` | Invalid configuration, corrupt persistence, backend failure, or another error prevented a valid decision. |

A status is not a quality score. `NATIVE` describes origin, while confidence
and degradation describe the selected behavior.

## Decision record

Every `MappingDecision` records at least:

- registry ID and registry/content type;
- owner mod ID;
- selected provider and backend IDs;
- strategy and client carrier/state;
- status, confidence, and degradation level;
- complete ordered candidate/reason chain;
- required model, texture, or other resources;
- warnings and failure reason;

The `why` command renders both accepted and rejected candidates. Machine reports
keep stable field/array ordering so changes are reviewable.

## Content rules in 0.1

### Items

The heuristic may select a food/drink, tool, armor, bow/crossbow-like,
shield-like, throwable, block-item, or generic material carrier only when
server-visible item behavior/components justify it. At the pre-freeze overlay
boundary, unbound 26.1 components temporarily use a generic floor. At
`SERVER_STARTING`, before players can join, Reborn locks the bound semantic
carrier into the overlay, final plan, report, and persistent mapping store.
Packet serialization performs no carrier discovery or filesystem access.
Client-only renderers are never inferred. Unsupported custom
data components are removed from the client projection, while safe display
name, lore, count, damage, item-model reference, and supported visual effects
are preserved where the Polymer/Minecraft API permits.

### Blocks

Automatic block mapping requires full collision and outline cubes, no unsafe
block-entity client contract, and state properties that can be represented
deterministically. Complex or dynamic shapes, doors, beds, fences, stairs,
multi-block structures, and special redstone behavior are not generic MVP
targets. Their report explains the failed predicate.

### Entities and menus

Discovery and native/explicit decisions are supported. Automatic visual entity
selection and generic menu projection are not. An entry without an explicit
safe implementation is `UNSUPPORTED` or a clearly described `FALLBACK`.

## Persistence interaction

A persisted assignment is loaded under strict schema/algorithm validation.
Item floor carriers are replayed only when the referenced vanilla item still
exists. Existing textured-block assignments are replayed through Polymer's
shared pool in stable order and must reproduce the exact stored state; otherwise
startup records an error instead of remapping. Current resource hashes and last
validation version are updated without changing a valid carrier. The v1 automatic
block algorithm uses an empty per-state key. Adding an entry cannot reorder old
allocations, and pool exhaustion is a backend capacity error rather than reuse
of an occupied state.

## Client profiles

`VANILLA` is the only profile for which 0.1 makes full mapping decisions.
`REBORN_COMPANION` and `TRUSTED_MODDED` are API values for future negotiation.
Unknown clients are `VANILLA`, and Fabric presence never enables raw registry
passthrough. A future trusted profile must authenticate exact registry and mod
fingerprints before allowing any passthrough.

## Safe failure

The planner prefers explainable absence over protocol corruption. Examples:

- corrupt mapping JSON: startup/config error, no silent replacement;
- unknown future schema: migration-required error;
- native override requested without both gates: native retained plus warning;
- forged creative marker: the guard rejects reverse conversion; runtime
  enablement is unavailable in 0.1 and `true` fails startup, while Polymer's
  ordinary stack metadata is never accepted as authentication;
- missing model texture: decision/report warning or unsupported resource path,
  never a traversal outside the pack root;
- no full-cube carrier capacity: explicit capacity error;
- packet outside a supported fallback policy: no-op backend does not guess.
