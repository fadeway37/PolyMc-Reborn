# 0.3 Beta to 0.4 RC upgrade testing

`runRcUpgradePlaytest` verifies the audited baseline commit
`bfe99049ffeb9da60a700a32282102278e6c3bba`. The harness extracts that exact
commit with `git archive`, builds the 0.3 Beta production JAR outside tracked
source, and runs one independently packaged content fixture. It then starts the
0.4 RC production JAR over the same server directory, configuration, mapping
store, and world. The fixture compiles against the current API for source
verification but is packaged without Reborn classes; each leg resolves the
exact production JAR supplied at runtime.

The gate executes five bounded server-process legs:

1. exact 0.3 Beta JAR plus Mod A and an isolated real client;
2. 0.4 RC JAR plus Mod A and a fresh isolated real client;
3. 0.4 RC plus independent Mod B item/block content and a client;
4. 0.4 RC with Mod B removed and no client;
5. 0.4 RC with Mod B re-added and another client.

Every client accepts the normal required resource-pack prompt from a
loopback-only host, checks a pack resource and the persisted hotbar item, opens
the explicit property GUI backed directly by three persisted barrel slots,
observes the explicit armor-stand projection for one persistent real custom
entity, captures six screenshots, and disconnects normally. Reborn, Polymer,
the fixture, and content Mods are absent from each client.

The server records the exact loaded Reborn version/JAR hash, mapping and pack
hashes, real block states and barrel contents, property values, persistent
entity value/UUID, and Minecraft 26.1.2 player data. The entity's owning chunk
is deliberately forced so a final count cannot be confused with an unloaded
entity; readiness and final-state captures must both find exactly one entity
with the same UUID.

The upgrade suite has 17 checks. It requires unchanged original keys,
strategies and carriers; seven persisted GUI items and progress `37/100`; one
entity with value `73`; byte-identical diagnostic policy; stable resource-pack
hash; both clients observing the GUI/entity; and a nonempty mapping store.
Schema remains `1` and algorithm remains `reborn-2`, so this is explicitly
compatibility validation and not a migration.

The mod-set expansion suite has eight checks. Mod B adds two independent
allocations without changing four existing allocations. Removal retains the
two dormant records while the active diff reports them as `REMOVED`; re-add
must restore the exact expanded mapping bytes without reuse or reordering.

Only selected sanitized logs, mappings, Mod lists, reports, and screenshots are
copied to `build/playtest/upgrade/` and
`build/playtest/modset-expansion/`. Extracted source, built baseline JARs,
runtime Mods, and the shared world remain ignored work data. On 2026-07-20 the
RC candidate completed all five legs, 17/17 upgrade checks, and 8/8 expansion
checks with 24 screenshots. Final release claims still depend on the matching
GitHub release-gate run.
