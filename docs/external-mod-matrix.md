# External content-Mod matrix

The 0.3 Beta matrix runs exact third-party artifacts one at a time on the
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

The local Windows execution for this Beta completed both locked rows with
`FULL_FOR_TESTED_SCOPE`: 41 checks per row, 42 screenshots total, separate
client/server logs and loaded-Mod lists, and clean reconnects. Immersive Armors
proved real helmet acquisition/equipment; Many More Ores and Crafts proved real
custom-block placement and breaking. The first Immersive Armors attempt exposed
three custom data-component registry entries during login; Reborn now registers
non-vanilla component types with Polymer's server-only registry filtering while
retaining their real server values. These statements apply only to the named
scenarios and exact locked artifacts.

Improved Crystals was researched but excluded: its public project/repository
license metadata and packaged Fabric metadata did not agree. PolyMc-Extra was
not inspected or used as an implementation source.

Allowed reporting terms are `FULL` for all explicitly enumerated scenarios,
`PARTIAL`, `FALLBACK`, `UNSUPPORTED`, `CRASH`, and `NOT_TESTED`. This Beta does
not use `FULL` to mean every feature of a Mod.
