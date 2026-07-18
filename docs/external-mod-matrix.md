# External content-mod compatibility matrix

## 0.2 status: `NOT_TESTED`

Research was performed on 2026-07-18 against official Modrinth project/version
metadata. Two exact 26.1.2 artifacts were downloaded to the bounded ignored
`playtest/external-cache/`, verified against publisher-provided SHA-512, and
inspected without execution. Both contain zero Java classes and therefore do
not register the custom Minecraft objects this matrix must exercise. No
third-party compatibility result is claimed. The internal Reborn fixture is
project-authored and is not third-party evidence.

Exact inspection metadata is locked in
[`playtest/external-mods.lock.json`](../playtest/external-mods.lock.json).

| Candidate | Exact version | License/source | Client requirement | Inspection or exclusion | Result |
| --- | --- | --- | --- | --- | --- |
| [Bubblellaneous](https://modrinth.com/mod/bubblellaneous) | `3.0.3+mod` (`x8ZQ7W3e`) | CC-BY-SA-4.0; [source](https://github.com/bbfh-dev/bubblellaneous-pack) | optional | SHA-256 `70daf7d061281a54ec832e921bcd3f1f0424d86465da8e8ba5b6091dc4471b5f`; 0 classes, 5,795 data and 4,237 asset entries; data/resource-pack content, not custom registrations | `NOT_TESTED` |
| [Silly Eatables](https://modrinth.com/mod/silly-eatables) | `v4.3.1+mod` (`oVpaJYK7`) | MIT; [source](https://github.com/Classics-Craftworks/Silly-Eatables) | optional/unsupported | SHA-256 `a1a83db6cea2803e4f1fcb22d78a01dbfbcc8c58668952d22eb65b896fefc412`; 0 classes and 72 data entries; recipe/data content only | `NOT_TESTED` |
| [Immersive Armors](https://modrinth.com/mod/immersive-armors) | `1.8.0+26.1.2` (`pNvZzj5v`) | GPL-3.0-only; [source](https://github.com/Luke100000/ImmersiveArmors) | required | would install the tested content definitions on the client, violating the minimal-client matrix | `NOT_TESTED` |
| [Improved Crystals](https://modrinth.com/mod/improved-crystals) | `1.0.1` (`DWSYJIz0`) | MIT; [source](https://github.com/Thumpbacker/ImprovedCrystals) | required | content-rich, but its official metadata requires the mod on the client | `NOT_TESTED` |
| [Amethyst Addon](https://modrinth.com/mod/amethyst-addon) | project advertises 26.1.2 | all-rights-reserved; no source link | required | license/source and minimal-client requirements do not meet the matrix policy | `NOT_TESTED` |
| [Oxidizium](https://modrinth.com/mod/oxidizium) | 26.1.2 variants | MIT; [source](https://github.com/Tater-Certified/Oxidizium) | optional | Rust-backed Minecraft rewrite/optimization project, not a bounded content-registration fixture; variant/runtime surface is unsuitable here | `NOT_TESTED` |

Because no candidate met all gates (real custom registrations, exact 26.1.2,
clear license/source, and no client installation), no external JAR was enabled
in the production playtest and no external-matrix workflow was activated. This
is an explicit P1 deferral, not a passing or failing compatibility outcome.

Do not turn successful mapping of an internal fixture into a claim about a
similarly shaped external mod.

## Procedure for a future entry

1. Select a mod that explicitly supports Fabric and exactly Minecraft 26.1.2.
2. Use only a publisher-controlled GitHub release, official Modrinth project,
   or another documented official source.
3. Review and record artifact/source licenses; public download access is not
   permission to redistribute.
4. Record project/source/download URLs, exact version, size, SHA-256, and
   license in `playtest/external-mods.lock.json`.
5. Download to a bounded ignored cache, verify size/hash before use, and never
   commit/upload the JAR unless licensing/project policy explicitly permits it.
6. Install the mod on the isolated production server only. Keep the client
   allow-list unchanged and do not install the tested mod on the client.
7. Run login, pack application, item acquisition/use, safe block place/break,
   report inspection, reconnect, and screenshot scenarios.
8. Preserve sanitized per-mod evidence and classify each tested feature rather
   than only server startup.

Allowed results:

- `FULL`: every explicitly listed scenario passed, never all mod content;
- `PARTIAL`: named capabilities passed and named capabilities degraded/failed;
- `FALLBACK`: safe carriers were used with documented degradation;
- `UNSUPPORTED`: content was safely refused;
- `CRASH`: server/client failed with evidence retained;
- `NOT_TESTED`: no completed evidence-producing run.

## Safety and CI

When at least one artifact passes selection, its external matrix workflow must
be manual/scheduled, separately timed, and
must not fetch unbounded or unverified binaries. It may upload sanitized Reborn
reports/logs/screenshots, but not the third-party JAR, world, credentials,
external IPs, usernames, or absolute local paths.

Download/infrastructure failure is not a compatibility result. An artifact
whose exact 26.1.2 support, source, hash, or license cannot be verified remains
`NOT_TESTED`. Runtime compatibility-code download/execution is forbidden.

Pure zero-mod vanilla-client smoke is a separate P1 layer. Running an external
mod against the Fabric Client GameTest driver would not prove that layer.
