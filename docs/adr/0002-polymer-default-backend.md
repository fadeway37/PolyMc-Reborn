# ADR 0002: Polymer is the default backend

- Status: Accepted
- Date: 2026-07-18
- Decision owners: PolyMc Reborn maintainers

## Context

Minecraft 26.1.2 needs a maintained mechanism for presenting server-only
registry content to vanilla clients. Polymer 0.16.5+26.1.2 supplies supported
item/block overlay APIs, server-only registry marking, resource-pack
generation, and optional ecosystem modules. Re-creating these mechanisms with
Reborn-owned Mixins would duplicate a maintained project and substantially
increase protocol risk.

Content mods may already implement Polymer themselves. Reborn must not replace
that higher-fidelity author-provided behavior unless an administrator makes a
specific, dangerous override decision.

## Decision

The default `CompatibilityBackend` is a Polymer adapter isolated in
`io.github.polymcreborn.backend.polymer`.

- Existing Polymer interfaces/registry overlays are detected first through
  Polymer's synced-object APIs and recorded as `NATIVE`.
- Reborn attaches `PolymerItem`/`PolymerBlock` overlays to supported existing
  registry objects through the published `PolymerItemUtils` and
  `PolymerBlockUtils` APIs; it does not substitute those objects on the server.
- Native Polymer wins by default. An administrator profile may override it only
  when the rule requests an override and `override_native_polymer` is true.
- Polymer Core, Blocks, and Resource Pack are exact runtime dependencies.
- AutoHost is optional. Networking and Virtual Entity are future/explicit
  integration points, not MVP automatic backends.
- Textured block carriers come from Polymer Blocks' global resource allocator;
  Reborn does not create an independent state pool that could collide with
  other Polymer mods.
- Polymer's default item restoration metadata is not treated as authenticated
  creative reverse mapping. Reborn keeps that feature disabled unless its own
  verification policy is active.
- Reborn's public planning and provider API does not expose Polymer types.

## Consequences

- Vanilla clients need no Reborn or Polymer installation, though mapped visual
  assets may require accepting the generated resource pack.
- Reborn follows Polymer's supported registry and network behavior instead of
  porting the original broad Mixin set.
- Native Polymer support can coexist with Reborn and is both preferred and
  visible in reports.
- Exact Polymer version changes require API review, integration testing, and a
  dependency/notice update.
- Polymer cannot automatically understand every existing mod; Reborn still
  needs providers, profiles, persistence, and unsupported classifications.

## Alternatives rejected

- **Own all packet/registry transformations:** too much duplicated protocol
  surface and update burden.
- **Shade or vendor Polymer:** obscures ownership and security updates, enlarges
  the artifact, and violates the external-dependency policy.
- **Require a companion client:** conflicts with the vanilla-client mission.
- **Always override native Polymer:** discards the content author's more
  informed implementation and creates avoidable incompatibilities.
