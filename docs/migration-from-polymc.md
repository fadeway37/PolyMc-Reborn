# Migrating from PolyMc

PolyMc Reborn preserves selected concepts and source names, not an old runtime.
Treat migration as a port to Minecraft 26.1.2 and Java 25.

## What cannot be carried over unchanged

A JAR compiled for Minecraft 1.20 or 1.21 cannot be made binary compatible by
adding `provides: ["polymc"]`. Its bytecode references older Minecraft classes,
method descriptors, mappings, Fabric/PolyMc APIs, and often implementation
Mixins. Recompile after porting to official 26.1.2 names and types.

Do not bundle the old PolyMc JAR beside Reborn. Reborn fails startup when a
distinct mod container has the actual ID `polymc`, and it declares
`polymc-extra` incompatible.

## Recommended migration

1. Create a branch of the extension and change the toolchain to Java 25,
   Minecraft 26.1.2, Loader 0.19.3, Loom 1.17.16, and official names. Remove
   Yarn mappings.
2. Depend on PolyMc Reborn `0.2.0-alpha.1+26.1.2` and the exact Polymer modules
   needed by the server pack; do not copy dependency JARs into the source tree.
3. First keep the Fabric entrypoint key `polymc` and port the implementation to
   the bridged `io.github.theepicblock.polymc.api.PolyMcEntrypoint`. This gives a
   smaller behavior diff.
4. Replace old Minecraft imports and then address semantic API differences.
   Compilation succeeding is necessary but not sufficient—verify client
   serialization, shapes, resources, and reverse mapping.
5. Register mappings during initialization. Reborn freezes the plan; late
   registration and command-time mutation are rejected.
6. Run with the test fixture or a minimal server. Inspect `/polymcreborn why`
   and the JSON/Markdown reports for every registered entry.
7. Move new integrations to the `polymc-reborn` entrypoint and backend-neutral
   provider/resource API when practical. The legacy package is a migration
   surface, not the preferred API for new work.

## Entrypoint metadata

A bridged extension can retain:

```json
{
  "entrypoints": {
    "polymc": [
      "com.example.compat.ExamplePolyMcCompatibility"
    ]
  },
  "depends": {
    "polymc": "*"
  }
}
```

Fabric resolves the dependency because Reborn provides `polymc`. Pin a real
Reborn version in production metadata if the extension uses behavior introduced
by a particular alpha. For a new extension, use:

```json
{
  "entrypoints": {
    "polymc-reborn": [
      "com.example.compat.ExampleRebornCompatibility"
    ]
  },
  "depends": {
    "polymc-reborn": ">=0.2.0-alpha.1+26.1.2"
  }
}
```

## Registration changes

The bridge retains the familiar collection phase:

```java
public final class ExampleCompatibility implements PolyMcEntrypoint {
    @Override
    public void registerPolys(PolyRegistry registry) {
        registry.registerItemPoly(/* ported 26.1 item and adapter */);
        registry.registerBlockPoly(/* ported 26.1 block and adapter */);
    }
}
```

Exact adapter signatures use official 26.1 types. Registrations are translated
to Reborn candidate decisions and included in the immutable plan; they are not
installed as the original packet-Mixin implementation.

Global item transforms must be deterministic, safe for all items, and must not
restore arbitrary client data. Resource callbacks write normalized logical
paths through the controlled resource sink. Legacy entity/GUI registrations
remain classification/candidate inputs; they do not automatically become the
new 0.2 guarded Virtual Entity or authoritative standard-container adapters.
Port those behaviors explicitly through the new APIs and test their real
interactions. There is no broad automatic emulation.

## Operational migration

Do not copy an old generated resource directory or assume old block-state IDs
remain valid. Preserve the old files separately for rollback, let Reborn create
its versioned `mappings-v1.json`, inspect the first plan, and distribute the new
deterministic pack. Back up the new mapping file with the world thereafter.

Configuration is not automatically translated from old PolyMc. Create a strict
Reborn `config.json` and versioned profiles. An unknown/misspelled field is an
error by design.

## Verification checklist

- Dedicated server starts without client-class linkage.
- Native Polymer items/blocks remain `NATIVE` in the report.
- Explicit legacy registrations appear as `LEGACY` with their reason chain.
- Unsupported entity, GUI, shape, renderer, and block-entity cases are visible.
- Item components and the disabled reverse-mapping guard pass hostile-input
  tests; runtime creative reversal remains off.
- Explicit GUI adapters preserve inventory across click/shift-click/drag/swap,
  close, and disconnect tests.
- Explicit entity adapters validate tracking/generation/distance and invoke the
  real server entity.
- Stateful full-cube additions preserve existing per-state assignments.
- Two equivalent clean pack builds have the same hash.
- Restart reuses the same mapping bytes/assignments.
- `runPlaytest` uses the isolated client allow-list and produces complete
  `build/playtest` evidence. The 2026-07-18 local release-candidate run passed
  53/53 checks and 35/35 client steps; GitHub Actions Client Playtest
  `29642433900` repeated the result successfully.

The detailed compatibility matrix is in
[legacy-api-compatibility.md](legacy-api-compatibility.md).
