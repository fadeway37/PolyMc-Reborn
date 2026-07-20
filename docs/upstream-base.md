# Upstream base

PolyMc Reborn preserves the complete Git history of TheEpicBlock/PolyMc. The
modernization branch was created from this exact upstream commit:

```text
repository: https://github.com/TheEpicBlock/PolyMc.git
branch at selection: upstream/master
commit: a3eaae6a56522a830b6e9a244e2bade0431a8c59
author: TheEpicBlock <git.teb@theepicblock.nl>
author date: 2025-01-28T15:07:52+01:00
subject: Fix packet restrictions being way too aggressive on start
nearest description at selection: 5.6.1-72-ga3eaae6a
```

Local/remote topology after the independent-repository migration:

```text
origin   https://github.com/fadeway37/PolyMc-Reborn.git
archive  https://github.com/fadeway37/PolyMc-Reborn-Archive.git
upstream https://github.com/TheEpicBlock/PolyMc.git
default branch main
```

The base can be verified without relying on generated files:

```text
git cat-file -t a3eaae6a56522a830b6e9a244e2bade0431a8c59
git show --no-patch --format=fuller a3eaae6a56522a830b6e9a244e2bade0431a8c59
git merge-base f571ac5243518387c5b1a81c818dd8ffdd8db2de upstream/master
```

This document records ancestry, not an assertion that every file at the base
remains in the Reborn tree. The obsolete Yarn-era build, broad Mixin set, old
generated assets, and old test layout were intentionally replaced. Reused or
adapted files retain their upstream license notices.
