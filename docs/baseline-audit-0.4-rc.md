# 0.3 Beta baseline audit for the 0.4 release candidate

This audit was completed on 2026-07-20 before RC implementation began. It
uses Git, GitHub API metadata, the existing Draft Release, and downloaded
workflow artifacts. A workflow conclusion by itself was not treated as proof
that a gameplay scenario passed.

## Identity and immutable baseline

- Repository: `fadeway37/PolyMc-Reborn`, a fork of
  `TheEpicBlock/PolyMc`.
- Development baseline: `reborn/0.3.0-beta+26.1.2` at
  `bfe99049ffeb9da60a700a32282102278e6c3bba`.
- Annotated tag: `v0.3.0-beta.1+26.1.2`.
- Tag object: `3f5ed14b7dad37714f90ead9650d68b2cea466cc`; the tag peels to the
  baseline commit above.
- Existing release: Draft and prerelease, unpublished, with 16 assets. The RC
  work does not edit or replace this release.

## Authoritative final workflow runs

| Workflow | Run ID | Head SHA | Result |
| --- | --- | --- | --- |
| CI | `29702813044` | `bfe99049ffeb9da60a700a32282102278e6c3bba` | success |
| Production Client Playtest | `29702812951` | `bfe99049ffeb9da60a700a32282102278e6c3bba` | success |
| Production Multi-Client Playtest | `29702812981` | `bfe99049ffeb9da60a700a32282102278e6c3bba` | success |

The downloaded final evidence artifacts are `8447008845`, `8446985229`,
`8446982561`, `8446980472`, `8446971014`, `8446992483`, and `8447008740`.
Their retained copies are ignored build output under
`build/github-artifacts-0.3/`.

## Artifact and scenario inspection

The release Mod JAR is 393,559 bytes with SHA-256
`1b57ce122a4a2c623a33a366c5c1956f459329b1e55f762e468f5b407a523441`.
The standalone API JAR is 63,558 bytes with SHA-256
`9649606f3381705e5b7548886c332002fc93c338ae1ac70cfd9aa523f0498fe3`.
The API signature baseline has 351 lines.

Downloaded structured evidence was parsed and representative screenshots were
visually inspected. It records:

- root JUnit: 108 tests, 107 passed and one Windows symlink-capability skip;
- API JUnit: one passed test;
- server GameTest: seven passed tests;
- single-client: 39/39 scenarios and 56/56 aggregate checks;
- multi-client: 26/26 scenarios with independent run roots and Mod lists;
- external matrix: 82/82 scenarios across Immersive Armors and Many More Ores
  and Crafts, with the content Mods absent from the client;
- upgrade: 12/12 checks; Mod-set expansion: 8/8 checks;
- Windows/Linux byte-identical main/API binary, source, and Javadoc archives;
- CycloneDX SBOM plus SHA-256/SHA-512 checksum files; and
- no fixture, client driver, external Mod JAR, world, or secret in the release
  artifact set.

## Known incomplete evidence carried into the RC

The 0.3 bounded soak is an infrastructure failure, not gameplay evidence. Its
orchestrator opened `build/playtest/soak/iteration-1.log`, while the nested
production-client task cleaned the parent `build/playtest` directory. Windows
therefore rejected deletion of the open file and zero of five iterations ran.
There is no successful long-soak evidence, GitHub artifact attestation, pure
zero-Mod vanilla-client smoke, or live creative reverse-mapping attack test.
Creative reverse mapping remains fail closed. These gaps must not be described
as passing baseline functionality.
