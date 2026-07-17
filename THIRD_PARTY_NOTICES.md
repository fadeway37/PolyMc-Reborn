# Third-party notices

PolyMc Reborn does not vendor or shade its runtime dependencies into this
repository or the distributable mod JAR; they are resolved as external
artifacts. The repository does include the standard Gradle Wrapper JAR as build
tooling. Each project remains under its own copyright and license terms.

## Direct runtime dependencies

| Project | Pinned artifact/version | Upstream license/source |
| --- | --- | --- |
| Fabric Loader | `net.fabricmc:fabric-loader:0.19.3` | Apache-2.0; FabricMC/fabric-loader |
| Fabric API | `net.fabricmc.fabric-api:fabric-api:0.155.2+26.1.2` | Apache-2.0; FabricMC/fabric |
| Polymer Core | `eu.pb4:polymer-core:0.16.5+26.1.2` | LGPL-3.0; Patbox/polymer |
| Polymer Blocks | `eu.pb4:polymer-blocks:0.16.5+26.1.2` | LGPL-3.0; Patbox/polymer |
| Polymer Resource Pack | `eu.pb4:polymer-resource-pack:0.16.5+26.1.2` | LGPL-3.0; Patbox/polymer |

Polymer modules are resolved from the Nucleoid Maven repository. Polymer
AutoHost, Networking, Virtual Entity, Resource Pack Extras, and Registry Sync
Manipulator are discussed as optional or future integration points; they are
not vendored. Transitive Polymer modules are governed by Polymer's published
metadata and license.

## Build and test tooling

The build uses Fabric Loom `1.17.16`, Gradle Wrapper `9.5.1`, the Foojay
toolchain resolver convention `1.0.0`, and JUnit Jupiter `5.13.4`. These tools
are not part of PolyMc Reborn's public API and remain under their respective
upstream licenses.

## Minecraft

The project compiles against `com.mojang:minecraft:26.1.2`. Minecraft and its
libraries are subject to Mojang/Microsoft terms. This repository does not grant
rights to redistribute Minecraft or assets extracted from Minecraft or other
mods. Server operators are responsible for the right to redistribute assets in
a generated resource pack.

Exact coordinates are authoritative in `gradle.properties`, `build.gradle`,
and `gradle.lockfile`. If those files change, update this
notice in the same change.
