# ADR 0007: Extend state mappings in place and stage rollback for restart

- Status: Accepted
- Date: 2026-07-18
- Decision owners: PolyMc Reborn maintainers

## Context

0.1 persisted one assignment per safe full cube even though schema 1 already
contained a state field. 0.2 needs stable per-state visuals without reordering
old allocations. Operators also need impact inspection and recovery without
making the persisted file disagree with already installed live overlays.

## Decision

Retain schema `1` and algorithm `reborn-2`. Canonical per-state records use the
existing state field with property names sorted. Property-free blocks retain an
empty key. A legacy empty default-state assignment can seed the canonical
default and is retired only after successful planning.

Resolve every safe state and bounded resource graph before allocation. Replay
existing carriers in persisted order and verify the exact canonical carrier.
Append new registry-ID/state keys in stable order. Missing/ambiguous variants,
resource errors, capacity exhaustion, duplicate carriers, or replay mismatch
fail the affected mapping instead of remapping silently.

Generate a stable `MappingPlanDiff` with `ADDED`, `REMOVED`, `PRESERVED`,
`REASSIGNED`, `INVALIDATED`, `RESOURCE_CHANGED`, and `CAPACITY_RISK` flags.
Reassigned, invalidated, and capacity-risk entries are incompatible. Dry run
serializes/hashes the in-memory proposal only.

Backups are bounded, path-safe, checksum-protected data/metadata pairs recording
Minecraft/project/schema/algorithm versions. Rollback is restart-only:

1. validate backup ID, path, metadata, size, checksum, JSON, schema, algorithm,
   and exact Minecraft version;
2. create a safety backup of the current store;
3. atomically stage a pending data/metadata pair;
4. leave the frozen live plan unchanged;
5. validate and activate the pair before planning on the next startup.

An incomplete or invalid pending pair blocks startup. It is never replaced with
an empty store.

## Consequences

- Existing valid assignments remain stable as content/states are added.
- State support needs no artificial structural migration.
- Operators can inspect impact and create validated backups before restart.
- Rollback changes the next plan/pack; it cannot restore missing mod content.
- Backup files are operational data, not source-control artifacts.

## Alternatives rejected

- Bump schema without structural need: needless migration risk.
- Allocate in registry/map iteration order: unstable assignments.
- Accept replay mismatch: client/world visual corruption risk.
- Hot rollback: persisted bytes, live plan, overlays, and pack would diverge.
- Delete corrupt state and regenerate: silent data loss.
- Accept arbitrary backup paths: traversal risk.
