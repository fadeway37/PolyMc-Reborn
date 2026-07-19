# 0.2 to 0.3 upgrade testing

`runUpgradePlaytest` verifies the audited baseline commit
`e15714e0cb922bb4551442a63b3ad192534dde45`. The harness extracts that commit
with `git archive`, builds its 0.2 release JAR outside tracked source, and runs
an API-neutral real content fixture. It then starts the 0.3 production JAR over
the same server/config directory.

The gate executes five clean server-process legs over the same world and
configuration:

1. audited 0.2 JAR plus the API-neutral Mod A fixture and an isolated client;
2. 0.3 JAR plus Mod A and a second isolated client;
3. 0.3 plus an independent Mod B item/block fixture and a client;
4. 0.3 with Mod B removed and no client;
5. 0.3 with Mod B re-added and another client.

Every client accepts the normal required resource-pack prompt from a
loopback-only host, checks a pack resource, observes the persisted hotbar item,
captures screenshots, and disconnects normally. The server records the exact
loaded Reborn version/JAR hash, mapping/pack hashes, real block-state and barrel
contents, and Minecraft 26.1.2 player data below `world/players/data`.

The gate compares canonical keys, strategies, and carriers. Empty regeneration,
lost keys, or remapping fails. Mod B removal retains dormant allocations in the
persistent store while the startup active-plan diff reports them as `REMOVED`;
re-addition must restore the exact expanded mapping bytes. Both public Gradle
entrypoints execute the same 20-check evidence contract.

Only selected sanitized logs/mappings are copied to
`build/playtest/upgrade/` and `build/playtest/modset-expansion/`; the built 0.2
JAR, source extraction, and world remain ignored work data.
