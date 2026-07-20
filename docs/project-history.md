# Project history

PolyMc Reborn began as a community modernization of
[TheEpicBlock/PolyMc](https://github.com/TheEpicBlock/PolyMc). The work retained
the upstream Git history, copyright notices, and LGPL licensing while replacing
the obsolete build and carefully redesigning compatibility around current
Fabric, official Minecraft names, and Polymer.

The project is not endorsed by the original PolyMc authors and is not an
official continuation maintained by them. “Successor” describes technical and
community intent; it does not imply ownership of the original project or a
guarantee that every historical extension remains compatible.

## Independent repository

The first Reborn releases were developed in a GitHub fork. Before RC2, that
fork was renamed to
[PolyMc-Reborn-Archive](https://github.com/fadeway37/PolyMc-Reborn-Archive),
and a new independent
[PolyMc-Reborn](https://github.com/fadeway37/PolyMc-Reborn) repository was
created outside the upstream fork network.

The independent repository started from the exact RC1 commit
`f571ac5243518387c5b1a81c818dd8ffdd8db2de`. Its initial tree
`93e86c4e57b5486d73d0080184649fe2941b7a09` is byte-for-byte identical to the
corresponding source tree in the historical fork. This preserved authorship
and provenance without importing unrelated historical branches into the active
repository.

The Archive retains the former fork's branches, tags, pull requests, Actions
records, and draft releases. The active repository carries only selected
Reborn release tags and current development branches. See
[repository-migration.md](repository-migration.md) for the audited boundary.

## Licensing and attribution

PolyMc Reborn is licensed under `LGPL-3.0-or-later`. Files adapted from PolyMc
retain their original copyright and license headers. New Java files use an
SPDX license identifier. [NOTICE.md](../NOTICE.md) and
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) contain the principal
attribution and dependency notices.

No source from PolyMc-Extra or another repository with unclear or incompatible
licensing was used. The original PolyMc logo is not reused because a separate
artwork license has not been established.
