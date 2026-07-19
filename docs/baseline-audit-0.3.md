# 0.2 baseline evidence audit for 0.3

This audit was performed on 2026-07-19 before beginning 0.3 development. It
uses GitHub Actions API metadata and downloaded artifacts; a green workflow
conclusion alone was not treated as scenario evidence.

## Conclusion

The final 0.2 baseline is commit
`e15714e0cb922bb4551442a63b3ad192534dde45` on
`reborn/0.2.0-alpha+26.1.2`. Its authoritative workflow runs are:

| Workflow | Run | Artifact | Result |
| --- | --- | --- | --- |
| CI | `29642877438` | `8429164023` (`polymc-reborn-e15714e0cb922bb4551442a63b3ad192534dde45`) | success |
| Production Client Playtest | `29642877439` | `8429167853` (`client-playtest-e15714e0cb922bb4551442a63b3ad192534dde45`) | success |

The previously documented runs `29642433896` (CI, artifact `8429041375`) and
`29642433900` (Production Client Playtest, artifact `8429042820`) are genuine
successful runs, but both have head SHA
`5188dcfcb3dfb8ae24c6fa0b68062a56c6eec253`. They are evidence for the
immediately preceding commit, not the final `e15714e0` baseline. No workflow,
artifact, branch, or 0.2 commit was deleted or rewritten during this audit.

## API metadata

All four runs were push events on `reborn/0.2.0-alpha+26.1.2` and completed on
2026-07-18:

| Run | Head SHA | Created (UTC) | Completed (UTC) | Conclusion |
| --- | --- | --- | --- | --- |
| `29642433896` | `5188dcfcb3dfb8ae24c6fa0b68062a56c6eec253` | `11:22:04` | `11:24:47` | success |
| `29642433900` | `5188dcfcb3dfb8ae24c6fa0b68062a56c6eec253` | `11:22:04` | `11:25:00` | success |
| `29642877438` | `e15714e0cb922bb4551442a63b3ad192534dde45` | `11:37:14` | `11:39:34` | success |
| `29642877439` | `e15714e0cb922bb4551442a63b3ad192534dde45` | `11:37:14` | `11:40:07` | success |

## Downloaded-artifact inspection

The final CI artifact contains
`polymc-reborn-0.2.0-alpha.1+26.1.2.jar` (341,357 bytes, SHA-256
`6988eb742dc2b5286a6ef833a5cbaecb98f0c1c500eca00847c711a7745dda15`).
Its manifest records the final SHA, `Git-Dirty: false`, official mappings,
Minecraft 26.1.2, Loader 0.19.3, Loom 1.17.16, Gradle 9.5.1, and Java 25 class
major 69.

The final playtest artifact was parsed and visually inspected. It contains:

- `summary.json` and `summary.md` with result `passed`, 53 passed checks and
  zero failed checks;
- JUnit XML with 53 tests, zero failures/errors/skips;
- 35 passing client scenario records;
- all 17 named PNG screenshots, from connection/resource-pack handling through
  item, block, GUI, entity, and reconnect states;
- independent client/server/orchestrator state and sanitized logs;
- client and server exit code 0, no timeout, no forced termination, and a clean
  server shutdown;
- the same final production JAR SHA-256 recorded by the CI artifact; and
- resource-pack SHA-256
  `90ea291c8ccb9fce36fc45e3b5aef3c8741ce82e11918f2e9b2c77b6978e5517`.

The earlier artifacts were also downloaded. Their production JAR is 341,357
bytes with SHA-256
`f5a4421fcfef45598888a28e6b65d9075d32f7c23605a2c9f3b60e56d513141b`,
and its manifest correctly records `5188dcfc...`. The earlier playtest bundle
also reports 53/53 checks, 35 scenarios, and 17 screenshots. This confirms the
two groups describe successive commits rather than conflicting copies of the
same build.

Downloaded audit material remains ignored build output and is not committed or
included in a release artifact.
