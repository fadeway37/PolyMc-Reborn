# 0.3 Beta research notes

Research on 2026-07-19 retained exact platform pins after checking Fabric's
official Maven/API/example sources and Polymer `dev/26.1` outside the worktree.
Fabric Loader 0.19.3, Fabric API 0.155.2+26.1.2, Loom 1.17.16, Polymer
0.16.5+26.1.2, Gradle 9.5.1, and Java 25 remain the selected stable inputs.
Polymer `dev/26.1` reference commit was
`0498b3ad7987f4c1ccc61053e143033ad728ce67`; the Fabric example `26.1.2`
reference was `27c9ae062374e59b9732d86ce229bfb42175408e`. No reference repository is
vendored.

Current APIs confirmed that server resource-pack responses can be observed
without replacing vanilla handling, Virtual Entity watcher hooks can supply
bounded composition packets, and official-named 26.1 menu/container types can
back an explicit furnace specialization. Item stacks cannot safely be created
for API entity equipment during early initialization because components are
not yet bound; the API therefore stores a vanilla `Item` and constructs the
visual stack at watcher-send time.

Real Mod selection used official Modrinth version metadata and publisher source
repositories. Immersive Armors and Many More Ores and Crafts met exact-version,
source/license, size/hash, and server-only-client-matrix requirements. Improved
Crystals was excluded for inconsistent packaged/public license metadata.
PolyMc-Extra source was not consulted.
