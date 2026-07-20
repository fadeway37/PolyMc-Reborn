# 0.3 Beta to 0.4 RC API compatibility

PolyMc Reborn 0.4 RC keeps two different records on purpose:

- `api/signatures/0.3.0-beta.1.txt` is the immutable compatibility input.
- `api/signatures/0.4.0-rc.1.txt` is the exact current public signature.

`:api:checkApiSignature` compares generated RC byte-level descriptors with the
RC file. `:api:checkApiCompatibility` independently classifies additions and
removals as Stable, Experimental, Internal, or Legacy and fails on any removed
Stable descriptor or unclassified removal. Its JSON and Markdown reports are
written under `api/build/reports/api-compatibility/`.

The audited files each contain 345 declaration lines. Their only textual
difference is the expected `version=` metadata line; no public constructor,
field, method, record component, annotation, or legacy descriptor changed.

## Binary Consumer gate

`runLegacyApiConsumerPlaytest` performs these bounded steps:

1. Obtain the exact published 0.3 API JAR from audited CI run `29702813044`,
   artifact `8446985229`, and verify its run commit, artifact digest, and SHA-256
   `9649606f3381705e5b7548886c332002fc93c338ae1ac70cfd9aa523f0498fe3`.
2. Compile the locked Consumer against only that 0.3 Maven coordinate.
3. Freeze and hash the resulting Consumer JAR.
4. Run that unchanged JAR with the 0.4 RC production server and an isolated
   real Minecraft 26.1.2 Client Driver.
5. Require Provider, Item, Block, GUI, Entity, resource contribution,
   disconnect/reconnect, and normal-cleanup evidence.

The test does not put the 0.3 API JAR in the RC runtime and does not recompile
the Consumer against the RC. Generated evidence is written to
`build/playtest/legacy-api-consumer/`. The newer Consumer remains a separate
Maven-coordinate-only test through `runApiConsumerPlaytest`.

The 0.3 release intentionally remains an unpublished Draft, which is not
readable by a least-privilege Actions `GITHUB_TOKEN`. The Consumer gate
therefore uses `actions: read` to fetch the exact audited workflow artifact.
That artifact is retention-bound. If it has expired or cannot be queried, the
gate checks out the audited Git commit
`bfe99049ffeb9da60a700a32282102278e6c3bba` in a temporary detached worktree,
builds only its API JAR, removes the worktree, and requires the bytes to match
the published SHA-256 above. A plain source export is deliberately insufficient
because release metadata includes the Git identity. The gate never accepts an
unverified rebuild, vendors the JAR, raises token privileges, or edits the 0.3
Draft.

This is source and binary compatibility for the published 26.1.2 Beta API. It
does not make extensions compiled for older Minecraft runtime types binary
compatible with Minecraft 26.1.2.
