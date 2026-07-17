# ADR 0001: Project direction

- Status: Accepted
- Date: 2026-07-18
- Decision owners: PolyMc Reborn maintainers

## Context

The historical PolyMc project converted a broad range of server content for
vanilla-like clients, but its behavior was coupled to old Minecraft types, Yarn
names, and a large Mixin surface. A version-number-only port would preserve
implicit ordering, hard-to-explain guesses, and packet/registry patches whose
safety cannot be assumed on Minecraft 26.1.2.

The successor must preserve the central invariant—real mod objects and game
logic stay on the server—while making compatibility decisions deterministic,
inspectable, persistent, and conservative. It must also provide a realistic
migration route for ecosystem extensions without claiming binary compatibility
across incompatible Minecraft runtimes.

## Decision

PolyMc Reborn is a community-maintained successor, not a drop-in version bump.
It targets exactly Minecraft 26.1.2, Fabric, Java 25, and official Minecraft
names.

The architecture separates discovery, candidate production, plan selection,
backend application, persistence/resources, and diagnostics. Discovery is
sorted by canonical registry identifier. Planning produces an immutable
`MappingPlan`; the live packet path performs O(1) lookups and no filesystem
access. Every entry has an explainable outcome, including unsupported entries.

The 0.1 implementation is intentionally narrow: items and safe full-cube
blocks are the automatic focus; entity and GUI support are explicit SPI/
classification only; packet fallback is disabled and no-op. Declarative JSON
profiles cannot run code. Mapping and pack output are deterministic and
versioned.

The repository preserves upstream Git history, uses LGPL-3.0-or-later, retains
notices on adapted files, and does not use PolyMc-Extra source.

## Consequences

- Adding a mod may produce visible `UNSUPPORTED` results rather than an
  attractive but unsafe guess.
- Mapping stores become world-adjacent operational data that should be backed
  up and migrated deliberately.
- Provider order and report schema are compatibility surfaces and need tests.
- Public APIs avoid Polymer types so a future backend can be added without
  changing the planning model.
- This approach does not promise all-mod compatibility or old-JAR compatibility.

## Alternatives rejected

- **Mechanical Yarn-to-official-name port:** it would hide semantic API and
  lifecycle changes and preserve an unreviewed patch surface.
- **Replace server registrations with vanilla objects:** it breaks server-side
  mod behavior and interoperability.
- **Cancel unknown packets until vanilla login succeeds:** login alone is not
  safe behavior and blanket cancellation hides corruption/desynchronization.
- **Build a broad entity/GUI emulator in 0.1:** the interaction and transaction
  invariants are too large for a safe MVP.
