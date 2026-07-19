# Mapping migration, backup, and rollback

The persistent mapping store is operational server data:

```text
config/polymc-reborn/mappings-v1.json
```

0.2 retains schema `1` and algorithm `reborn-2`. The existing `state` field now
stores canonical per-state full-cube keys, so no cosmetic structural migration
is required. Records sort by content type, registry ID, and state.

## Operator commands

All mapping commands require administrator permission. `mapping` and
`mappings` spellings are accepted under `/polymcreborn`, `/pmcr`, and the
conditional `/polymc` alias.

```text
/pmcr mappings status
/pmcr mappings validate
/pmcr mappings diff
/pmcr mappings dry-run
/pmcr mappings backup
/pmcr mappings rollback <backup-id>
```

None changes the current immutable `MappingPlan`.

### Status and validation

`status` reports existence, entry count, SHA-256, schema, algorithm, pending
rollback state, and backups. `validate` strictly parses the current store.
Unknown fields, duplicate keys, unsupported schema/algorithm, invalid IDs,
states, carriers or hashes, and malformed JSON are errors. Reborn never
recovers corruption by creating an empty store.

The primary store is bounded to 32 MiB before parsing. Registry IDs must be
canonical namespaced IDs; state keys use bounded canonical `name=value` pairs
and are valid only for block records; carriers must be valid namespaced item
IDs or canonical block-state strings. Strategy and version fields are bounded
as well. Backup metadata failures are normalized to path-specific mapping-store
errors instead of leaking parser exceptions.

### Stable diff

Startup compares the store loaded before planning with the final active proposal and
atomically writes:

```text
config/polymc-reborn/reports/mapping-diff-latest.json
config/polymc-reborn/reports/mapping-diff-latest.md
```

Entries have stable ordering and may contain:

| Flag | Meaning |
| --- | --- |
| `ADDED` | proposed key did not previously exist |
| `REMOVED` | prior key is absent from the proposal |
| `PRESERVED` | strategy/carrier are unchanged |
| `REASSIGNED` | strategy or carrier changed |
| `INVALIDATED` | backend validation rejected a present assignment |
| `RESOURCE_CHANGED` | dependency/model hash changed |
| `CAPACITY_RISK` | proposal exceeds the supplied strategy capacity |

Reassigned, invalidated, and capacity-risk results are incompatible. A
resource-only change is visible but does not itself mean carrier reassignment.

Allocations belonging to a temporarily absent Mod remain dormant in the
persistent store so a later re-add cannot recycle or reorder them. They are
nevertheless absent from the active proposal and therefore appear as
`REMOVED` in the startup diff. Re-adding the same valid content replays the
retained assignment.

### Dry run

`dry-run` serializes/hashes the already computed proposal in memory and reports
its stable diff, byte count, and SHA-256. It does not write the primary store,
create a backup, rebuild registries, change overlays, or apply newly edited
profiles. Mapping-affecting configuration still needs restart.

### Backup

`backup` reads a bounded current store, validates it, and writes an atomic
data/metadata pair below:

```text
config/polymc-reborn/backups/mappings/
  <timestamp>-<hash-prefix>.json
  <timestamp>-<hash-prefix>.meta.json
```

Metadata includes creation time, SHA-256, size, Minecraft/project versions,
schema, and algorithm. Backup IDs have a fixed pattern and resolved paths must
remain under the configured root.

### Restart-only rollback

`rollback <backup-id>`:

1. rejects unknown/traversal IDs and incomplete pairs;
2. validates metadata, size, SHA-256, exact Minecraft `26.1.2`, schema, and
   `reborn-2` algorithm;
3. creates a safety backup of the current store when present;
4. atomically stages:

```text
config/polymc-reborn/mappings-v1.rollback-pending.json
config/polymc-reborn/mappings-v1.rollback-pending.meta.json
```

The live frozen plan remains unchanged and the command reports that a restart
is pending. Before the next static plan, startup validates the pending pair
again, backs up a differing current file, activates it atomically, removes the
pending pair, and plans normally.

A partial pending pair, checksum mismatch, wrong version, corrupt JSON, or
incompatible algorithm blocks startup. Do not delete the primary file to make
startup continue.

## Stateful compatibility

Per-state keys are canonical strings such as `active=false` or `axis=x` with
properties sorted by name. A property-free 0.1 record keeps its empty key. If a
property-bearing block has a legacy empty default record, that exact carrier
seeds the canonical default state; additional states append without reordering
valid allocations.

Persisted textured carriers replay through Polymer's shared pool in carrier
order. A replay mismatch, missing variant/resource, or capacity exhaustion
fails the affected mapping instead of silently allocating a new appearance.

## Recovery checklist

- preserve the current configuration, world, and mod list;
- run `status`, `validate`, and `diff` and retain their reports;
- confirm the chosen backup belongs to the intended Minecraft/mod set;
- run `rollback <id>` and record the safety-backup ID;
- restart once, then inspect startup, compatibility, diff, and pack reports
  before admitting players.

Rollback restores mapping records, not removed registry objects or mod assets.
A backup that cannot validate against the current server still fails closed.
