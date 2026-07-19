# Beta release process

Development occurs on `reborn/0.3.0-beta+26.1.2`. Run every command in
`AGENTS.md`, inspect all structured evidence and screenshots, check dependency
locks/verification and release JAR contents, then push the branch normally.

`release-beta.yml` is manual-only. It accepts exactly
`v0.3.0-beta.1+26.1.2`, runs build/API/server/upgrade/single-client/
multi-client/pack-policy/external-Mod/reproducibility gates, and only then
creates an annotated tag plus GitHub Draft Release. A failed or missing P0 gate
means no tag and no draft. The workflow never uploads worlds, external Mod
JARs, creative keys, or runtime caches.

The draft contains the main/API binary, sources, Javadoc, SBOM, checksums,
provenance/build manifest, API signature, license, notices, and these notes.
