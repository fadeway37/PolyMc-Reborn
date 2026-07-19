# Compatibility model

Compatibility is decided per canonical registry entry and client profile, not
as one Boolean for an entire mod. One mod can contain native items, explicitly
adapted menus, heuristic full cubes, and unsupported entities in the same
report.

## Provider resolution order

The deterministic tiers, highest first, are:

1. native Polymer implementation;
2. administrator forced rule;
3. explicit PolyMc Reborn Java adapter;
4. legacy PolyMc adapter;
5. bundled declarative compatibility profile;
6. automatic heuristic provider;
7. safe fallback;
8. unsupported.

Tier 2 cannot normally displace tier 1. Native replacement requires both a
matching rule that explicitly requests it and
`override_native_polymer: true`. Otherwise native wins and the full candidate
trace records why the administrator candidate was rejected.

Providers inside a tier sort by stable provider ID. Profiles sort by priority
descending then ID; profile rules retain explicit array order. Discovery sorts
canonical registry ID then content kind. Registration or map iteration never
breaks a tie.

## Statuses and decision records

| Status | Meaning |
| --- | --- |
| `NATIVE` | registered content already has Polymer behavior |
| `EXPLICIT` | a reviewed Reborn Java adapter handles the entry |
| `LEGACY` | a recompiled legacy adapter supplied the candidate |
| `PROFILE` | a validated declarative rule supplied the decision |
| `HEURISTIC` | conservative automatic analysis chose a representation |
| `FALLBACK` | a safe but materially degraded presentation is used |
| `UNSUPPORTED` | no safe representation was found |
| `ERROR` | invalid input, persistence, capacity, or backend failure prevented a valid decision |

A status is not a quality score. Each `MappingDecision` separately records the
registry/content IDs, owner mod, provider/backend, strategy, carrier/state,
confidence, degradation, complete ordered reason/candidate chain, resources,
warnings, and failure reason. `/why` and machine reports preserve accepted and
rejected candidates in stable order.

## Content rules in 0.3 Beta

### Items

The heuristic chooses a food/drink, tool, armor, bow/crossbow, shield,
throwable, block-item, or generic material carrier only when bound server data
supports that category. The carrier is fixed before players join. Packet-time
projection performs no discovery or filesystem access. Safe display data,
count, damage, model reference, and supported effects are retained where the
Minecraft/Polymer APIs permit; unknown custom client components are filtered.
Client-only renderers are never inferred.

### Stateful full-cube blocks

Automatic mapping requires complete stable collision/outline cubes, no unsafe
block-entity presentation contract, and an unambiguous bounded `variants` model
graph. Property names/values are canonicalized and each safe state receives a
persisted carrier. Existing assignments replay first; new registry-ID/state
keys append without reordering valid assignments.

Complex/dynamic shapes, ambiguous or multipart resources, doors, beds, fences,
stairs, multi-block structures, special redstone behavior, and unsafe block
entities are not automatic targets. Their failed predicate is reported. A
protocol-safety quarantine overlay for unsupported/error blocks remains
explicitly `UNSUPPORTED`/`ERROR`; it is not a claim that visuals or behavior are
compatible.

### Explicit entities

An entity is projected only by native Polymer behavior or a reviewed explicit
adapter. The adapter names a registered vanilla virtual surrogate, a bounded
offset/distance, and approved callbacks. The real entity remains authoritative.
One explicitly declared vanilla passenger and bounded explicit vanilla
equipment can be synchronized by a reviewed adapter. Automatic surrogate,
equipment, or passenger choice, leash synchronization, broad metadata, and
dimension specialization are not implemented.

An unsupported/error custom entity type is registered server-only at freeze
time and receives an invisible marker quarantine solely for registry safety.
It remains `UNSUPPORTED`/`ERROR`; no render or interaction compatibility is
claimed.

### Explicit standard-container menus

A GUI adapter exposes the real server `Container`, a complete bijective mapping
for a vanilla generic 9xN screen, and an interaction policy. Transactions remain
server-authoritative and sessions are bounded/cleaned. A reviewed furnace
specialization can expose a real three-slot `Container` and four bounded
`ContainerData` properties. Custom buttons, paging, arbitrary slot
types/layouts, and automatic slot-count inference are not implemented.

An unsupported/error custom menu type is marked server-only at freeze time.
That quarantine prevents an unknown menu registry entry from reaching a
vanilla client, but does not make the menu openable or infer a layout.

## Persistence interaction

`mappings-v1.json` is loaded under strict schema/algorithm validation. Item
carriers replay only when their vanilla targets still exist. Full-block carriers
replay through Polymer's shared pool in stable order and must reproduce the
stored state exactly. The v1 store uses canonical per-state keys; a legacy empty
default-state key can seed the canonical default without reallocating it.

Adding content/state cannot reorder valid old allocations. Capacity exhaustion
or replay mismatch is an explicit backend error, not permission to reuse or
silently remap a carrier.

Status/validation/diff/dry-run commands do not alter the frozen plan. Backups
are checksum-protected and path-bounded. Rollback stages a validated pending
pair for activation before planning on the next restart; it is never a live
remap.

## Client profiles

`VANILLA` is the only active 0.3 profile. `REBORN_COMPANION` and
`TRUSTED_MODDED` are future API values. Unknown clients are vanilla; Fabric
presence never enables raw registry passthrough. Any future trusted path must
authenticate exact registry and mod fingerprints.

## Safe failure examples

- corrupt/unknown mapping data: startup/migration error, never empty recovery;
- native override without both gates: native retained plus visible warning;
- forged creative marker: rejected, while runtime reverse mapping remains
  disabled and unsigned Polymer restoration data is not authentication;
- missing/unsafe resource: diagnostic or unsupported decision, never root
  escape or unresolved custom renderer claim;
- full-block capacity exhaustion/replay mismatch: explicit error;
- unmapped menu/entity: unsupported and server-only quarantined rather than
  guessed;
- packet outside a reviewed fallback policy: disabled no-op does not guess.
