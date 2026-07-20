# RC release process

Development occurs on `reborn/0.4.0-rc+26.1.2`. Run every command in
`AGENTS.md`, inspect structured evidence/screenshots, dependency locks, both
release JARs, SBOM and checksums, then push the branch normally.

GitHub registers manual workflows only after their definition exists on the
default branch. The RC workflow is therefore introduced through the dedicated
`codex/register-rc-release-workflow` branch and a default-branch PR whose diff
contains only `.github/workflows/release-rc.yml`. Product source remains on the
RC branch. The workflow is then dispatched on the exact product ref and checks
that `GITHUB_SHA`, `release_ref`, and the checked-out commit all agree.

`release-rc.yml` accepts `release_ref`, exact `expected_version`, and a boolean
`create_draft_release` that defaults false. It runs the full build/API/server,
legacy and RC Consumers, single/multi-client, pack policy, three-Mod matrix,
upgrade/expansion, Linux short/long Soak, reproducibility, SBOM, checksum, and
release-content gates. Its second job creates official GitHub Artifact
Attestations, independently verifies five subjects with `gh`, and requires a
one-byte-tampered JAR to fail verification.

Because later client harnesses intentionally reset their bounded evidence root,
the release job uploads each Consumer, single-client, multi/pack-policy,
external-Mod, upgrade/expansion, short-Soak, and long-Soak bundle immediately
after its gate. This preserves failure and success evidence without weakening
the nested cleanup contract.

The first successful dispatch uses `create_draft_release=false` so hosted
artifacts and attestation evidence can be inspected. Only after Windows Soak,
all other P0 workflows, downloaded evidence, and attestation checks pass may a
dispatch use `true`. That path creates the exact annotated tag and a Draft
Pre-release; it never publishes publicly. Any failed or missing P0 means no tag
and no draft.

The draft allow-list contains main/API binaries, sources, Javadocs, SBOM,
SHA-256/SHA-512 lists, API signature, release notes, build manifest, licenses,
notices, and the real attestation verification record. It excludes worlds,
screenshots, test drivers/fixtures, third-party Mod JARs, caches, and secrets.
