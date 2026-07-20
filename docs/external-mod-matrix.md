# External content-Mod matrix

The 0.4 RC matrix runs three exact third-party artifacts one at a time on the
production server. The isolated client keeps its eight-Mod allow-list and never
installs the tested content Mod. A passing row describes only the named
scenario; it is not a whole-Mod compatibility claim.

The executable lock is
[`playtest/external-mods.lock.json`](../playtest/external-mods.lock.json).
Downloads are bounded to 8 MiB, must match exact size, SHA-256, and SHA-512,
remain under ignored `build/external-mods/`, and are never copied into release
or evidence artifacts.

| Mod | Exact artifact | License/source | Server scenario | Expected classification |
|---|---|---|---|---|
| Immersive Armors | `1.8.0+26.1.2`, Modrinth version `pNvZzj5v`, SHA-256 `1ca08b0d30fa860eae0b0451c3348f9a3bc88ced6b3f9ca3c06e1f994ac117cf` | GPL-3.0-only, source commit `4cd05c99a7a61b8f0e34825cf1a667f6db404a85` | acquire real `immersive_armors:bone_helmet`, verify a vanilla carrier, equip it through normal use | `FULL_FOR_TESTED_SCOPE` only if every named item assertion passes |
| Many More Ores and Crafts | `2.0.1`, Modrinth version `sZLzO94K`, SHA-256 `aebcb5c7763300ecafd588eb7e22b8f665815ad9d534d1aa1d90eecd30fe08f2` | MIT, source commit `941837076ee80e0e616e74330508d496ca10b10a` | acquire, place, and break real `many_more_ores_and_crafts:adamantite_block`; verify stable mapping | `FULL_FOR_TESTED_SCOPE` only if every named block assertion passes |
| Farmer's Delight Refabricated | `26.1-3.6.7+refabricated`, Modrinth version `5UrcSJDx`, SHA-256 `25adee6361b37f1e559373bf6aedc90fa62b2da8ab084e3dee53f037ffcac636` | MIT, source commit `86ec999a067d68670ecbd4faa79463f67de0b689` | acquire and consume one real `farmersdelight:tomato`, prove server food semantics, reconnect, and verify stable mapping | `FULL_FOR_TESTED_SCOPE` only if every named food assertion passes |

Run:

```text
./gradlew runExternalModMatrix
```

The task downloads from the locked publisher URL, launches a fresh production
client/server run per entry, and writes sanitized results below
`build/playtest/external-mods/`. Infrastructure/download failures are recorded
as failures, never reclassified as Mod incompatibility. A zero-scenario client
startup failure with no crash, decoder, or registry-sync signature receives one
bounded retry after host resources settle; both attempt bundles remain in the
evidence. Content assertion failures are never retried.

The RC adds Farmer's Delight because its real Java registrations and semantic
food behavior are materially different from the existing armor and full-cube
cases. The focused local Windows RC run completed 41 client scenarios and
consumed one real tomato while the client remained isolated from the content
Mod. It also exposed auxiliary static registry IDs and dynamic registry entries
whose encoded data referenced those hidden IDs. Reborn now uses Polymer
Registry Sync Manipulator for a deterministic server-only static registry view,
then applies two narrow filters to dependent dynamic-registry and recipe-book
entries. Real server registrations are unchanged. This is not a broad packet
cancellation and is not evidence for untested Farmer's Delight blocks, menus,
entities, recipes, or world generation.

The 0.3 Windows matrix previously completed the two original rows with
`FULL_FOR_TESTED_SCOPE`: Immersive Armors proved real helmet
acquisition/equipment and Many More Ores and Crafts proved real custom-block
placement and breaking. The final RC report must come from a fresh three-row
matrix on the candidate commit; historical rows do not substitute for that
gate.

Improved Crystals was researched but excluded: its public project/repository
license metadata and packaged Fabric metadata did not agree. PolyMc-Extra was
not inspected or used as an implementation source.

Allowed reporting terms are `FULL_FOR_TESTED_SCOPE`, `PARTIAL`, `FALLBACK`,
`UNSUPPORTED`, `CRASH`, and `INFRASTRUCTURE_FAILURE`. The RC never uses a
passing scoped classification to mean every feature of a Mod.
