# Repository migration

This document records the one-time transition from the original GitHub fork to
an independent project repository. It is a provenance record, not an
installation guide.

## Repository identities

| Role | Repository | GitHub repository ID | Fork state |
| --- | --- | ---: | --- |
| Historical fork | [fadeway37/PolyMc-Reborn-Archive](https://github.com/fadeway37/PolyMc-Reborn-Archive) | `1304319743` | Fork of `TheEpicBlock/PolyMc` |
| Active project | [fadeway37/PolyMc-Reborn](https://github.com/fadeway37/PolyMc-Reborn) | `1306637304` | Independent; no parent or source repository |

The historical repository was renamed in place. Its repository ID and fork
relationship did not change, so GitHub-hosted issues, pull requests, tags,
releases, Actions history, and other repository records stayed attached to the
same object. It receives a read-only archive setting only after the active
repository's RC2 release gates and provenance checks pass.

The canonical project name was then reused for a newly created empty
repository. Because that name now identifies a different repository object,
consumers must not rely on a rename redirect to locate historical records; use
the explicit Archive URL above.

## Immutable transfer baseline

The first push to the active repository contained exactly one branch,
`main`, at:

| Property | Value |
| --- | --- |
| RC1 commit | `f571ac5243518387c5b1a81c818dd8ffdd8db2de` |
| Git tree | `93e86c4e57b5486d73d0080184649fe2941b7a09` |
| RC1 annotated tag object | `c5fb2c9afa1d46d397d3fdd3fd03ad769d9135ca` |
| RC1 tag | `v0.4.0-rc.1+26.1.2` |

Before any remote mutation, the migration retained a complete mirror, a
verified Git bundle, remote-reference inventories, repository metadata, and
all 33 assets from the two historical draft prereleases. Every downloaded
asset matched both its API size and SHA-256 digest. Those operational records
are deliberately excluded from commits because they contain machine-specific
evidence locations.

After the first push, independently produced manifests compared the complete
Git tree, production Java sources, API Java sources, production resources, and
API resources. All seven comparisons were byte-identical. RC2 then began on
`release/0.4.0-rc.2`; Java, Mixin, Access Widener, mapping schema, mapping
algorithm, stable API descriptors, and behavioral defaults remain unchanged
from RC1.

## Selected references

Only these historical Reborn tags were transferred initially:

- `v0.3.0-beta.1+26.1.2`
- `v0.4.0-rc.1+26.1.2`

Upstream numeric tags, upstream development branches, previous Reborn
development branches, and migration-only branches remain in the Archive and
were not pushed to the active repository. After RC2 is merged, the active
repository's remote branch set returns to `main` only.

## Security boundary

The migration does not alter runtime compatibility logic. Changing repository
identity invalidates any assumption that old hosted workflow evidence proves a
new release, so RC2 repeats build, server, client, matrix, soak,
reproducibility, artifact, and GitHub provenance verification in the active
repository. RC1 provenance remains a historical Archive record and is not
represented as RC2 provenance.
