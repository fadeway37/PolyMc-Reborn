# Testing

PolyMc Reborn uses layered tests. No single unit test proves that an arbitrary
mod is compatible, and a dedicated-server start is not a substitute for a real
vanilla-client login.

## Commands

Run Java first and record its output:

```text
java -version
./gradlew clean build
./gradlew test
./gradlew runGameTest
./gradlew runDedicatedServerSmoke
git diff --check
```

Use `gradlew.bat` on Windows. `clean build` already executes the JUnit test task;
running `test` separately is useful for a focused rerun. GameTest and the server
smoke run are separate Loom processes and are not considered passed merely
because compilation succeeded.

## Unit tests

The JUnit 5 suite is organized around deterministic, game-independent logic
where possible:

| Area | Required assertions |
| --- | --- |
| main config | defaults are safe; required fields/types/ranges parse; unknown and misspelled fields fail with a JSON path |
| compatibility profiles | schema/version/target/rules parse; safe globs are bounded; code/script/class/unknown actions fail |
| provider resolution | all tiers follow stable order; provider ID breaks ties; traces include accepted and rejected candidates |
| native Polymer | native item/block decisions win unless both explicit override gates are true |
| discovery | shuffled equivalent descriptor inputs produce the same ordered plan |
| mapping store | strict round trip, corrupt/unknown schema rejection, temporary-file replacement, backup-before-migration, and capacity errors |
| deterministic mappings | identical input produces byte-identical `mappings-v1.json`; adding content preserves valid assignments |
| resource pack | traversal/conflict/missing-resource diagnostics and byte-identical ZIP/manifest output |
| item conversion | ordinary and semantic carriers, safe component preservation, unsupported component filtering, namespaced `ITEM_MODEL`, deterministic output |
| reverse-conversion guard | unit-level signed-marker round trip; malformed, stale, forged, wrong-ID, and disallowed-component markers rejected; runtime enablement remains unavailable in 0.1 |
| block conversion | full cube, multi-state full cube, corresponding block item, deterministic assignment, and unsupported shape/block entity |
| legacy bridge | a `polymc` entrypoint registration appears as `LEGACY` and contributes normalized resources |
| dedicated-server linkage | common entrypoints and APIs load without client-only Minecraft classes |

Tests should use temporary directories supplied by JUnit and validate resolved
targets before deleting them. They must not write developer-global
`config/polymc-reborn` state.

## Internal test mod and GameTest

The `gametest` source set is not shipped in the release JAR. It registers:

- a basic item, food-like item, and tool-like item;
- a simple full cube and a stateful full cube;
- a non-full-cube unsupported block and a block entity case;
- a custom entity type and menu/screen-handler type for classification;
- item/block content with an existing native Polymer implementation;
- a recompiled legacy adapter under the `polymc` entrypoint.

GameTest verifies registry discovery and real Fabric/Polymer lifecycle wiring
that a plain JUnit process cannot cover. A successful run requires the server
to reach the GameTest phase, execute all registered tests, write the configured
report, and exit with success. A process that only accepts the EULA, creates a
world, or reaches an idle prompt is not sufficient.

## Dedicated-server smoke test

The smoke run uses Loom's server-only Minecraft JAR and the release mod source
set. It is intended to detect:

- client-only class linkage during mod discovery/initialization;
- incorrect `fabric.mod.json` dependencies/environment;
- startup guard/config/report failures;
- Polymer API linkage errors for the exact pinned version.

The test is successful only when logs show Reborn initialization and the server
reaches its ready state without a client-class error, followed by a controlled
shutdown. If automation cannot stop the process cleanly, report the run as
manual/incomplete rather than silently killing it and calling it passed.

## Vanilla-client scope

This repository does not currently claim an automated, real vanilla-client
end-to-end login test. Unless a test run actually launches an unmodified 26.1.2
client, connects, accepts/declines the pack, interacts with fixtures, and
validates observations, the final test report must say it was **not run**.

The MVP substitutes focused server-side evidence:

- direct Polymer item-stack projection and hostile reverse-mapping tests;
- Polymer overlay/non-override integration tests;
- GameTest fixture behavior;
- dedicated-server class-loading/startup smoke.

These reduce risk but do not prove every client rendering or interaction path.

## Reproducibility checks

For mappings and packs, run equivalent builds in two new temporary directories
with the same normalized input and compare bytes/SHA-256. Avoid testing only in
one warmed cache. Archive reproducibility checks also inspect timestamps and
entry order.

The distributable JAR must contain `fabric.mod.json`, the license/notice, and a
manifest with PolyMc Reborn version, Minecraft version, Git commit, and dirty
state. It must not contain GameTest classes, cache/reports, secrets, local JARs,
or absolute developer paths.

## Reporting results

A release or handoff report lists exact commands, exit codes, number of tests,
failures/skips, integration-task status, JAR path/hash, Git commit, and dirty
state. Include the reason for every skipped task. Do not turn “not automated”
into “passed by inspection.”
