# ADR 0003: Legacy API compatibility is source-oriented

- Status: Accepted
- Date: 2026-07-18
- Decision owners: PolyMc Reborn maintainers

## Context

PolyMc extensions historically used the `polymc` Fabric entrypoint and types
under `io.github.theepicblock.polymc.api`, especially `PolyMcEntrypoint`,
`PolyRegistry`, `PolyMap`, and item/block/entity/GUI/resource registration
contracts. Preserving useful names lowers migration cost.

Minecraft 26.1 uses official names and different runtime types from 1.20/1.21
PolyMc builds. An old compiled extension embeds references to classes and
method descriptors that do not exist on the target runtime. Pretending binary
compatibility would turn linkage errors into an operator surprise.

## Decision

Reborn provides a deliberately selected legacy surface in the original API
package and loads recompiled extensions from the `polymc` entrypoint in
addition to the new `polymc-reborn` entrypoint.

`PolyRegistry`-style registrations are collected during initialization and
adapted into candidates/resource contributions for the new immutable plan.
Supported registrations include item, global item, block, GUI, entity, and
mod-specific resource contribution concepts. Entity and GUI registrations are
retained as explicit mappings/classifications; their presence does not enable
unsafe generic automation.

The bridge targets **source migration after porting and recompilation for
26.1.2**. It does not promise binary compatibility with any older JAR. Legacy
types may be adapted or deprecated where the old semantics depended on removed
Minecraft APIs or the broad Mixin implementation. Impossible behaviors are
documented instead of emulated silently.

Reused/adapted upstream API files retain their original LGPL copyright and
license headers. New bridge implementation files use the Reborn SPDX header.

## Consequences

- Downstream authors can keep familiar registration structure while migrating
  official Minecraft types and semantics.
- `fabric.mod.json` advertises `polymc`, but startup rejects a distinct real
  `polymc` mod container so two implementations cannot run together.
- Every bridged registration enters the same provider priority and diagnostic
  model as native Reborn registrations.
- Some old methods are compile-time deprecations, semantic adaptations, or
  postponed; the compatibility matrix is normative.

## Alternatives rejected

- **No bridge:** needlessly forces every extension to redesign at once.
- **Claim old-JAR binary compatibility:** technically false across Minecraft
  names, descriptors, loader/library versions, and removed implementation hooks.
- **Port every old class and Mixin:** expands 0.1 beyond its safety boundary and
  retains APIs whose behavior cannot be honored.
- **Load old code reflectively:** moves failure to runtime, breaks hot-path and
  dedicated-server safety requirements, and weakens type checking.
