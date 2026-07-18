# Changelog

All notable project changes are recorded here. The format follows Keep a
Changelog principles; versions follow Semantic Versioning where practical for
an alpha mod tied to an exact Minecraft version.

## [Unreleased]

### 0.2.0-alpha.1+26.1.2 development

- Began the Interactive Compatibility Alpha on the preserved 0.1 history.
- The target remains Minecraft 26.1.2, Java 25, Fabric, and official Minecraft
  names; client playtest, explicit GUI/entity projections, stateful blocks, and
  mapping migration tooling are under active implementation.

### Added

- Minecraft 26.1.2 / Java 25 Fabric Loom bootstrap using official Minecraft
  names.
- Polymer-first compatibility planning model, deterministic persistence,
  declarative profile format, diagnostics, and legacy API migration surface.
- Focused unit, GameTest-fixture, and dedicated-server testing infrastructure.
- Architecture, operations, migration, security, and contributor documentation.

### Changed

- Replaced the historical broad-Mixin architecture with a conservative
  provider/plan/backend design. Existing native Polymer implementations have
  first priority.

### Removed

- Yarn mappings, obsolete build tooling, legacy blanket packet suppression,
  old artwork with an unconfirmed standalone license, and unsupported old
  source/test layouts.

## [0.1.0-alpha.1+26.1.2] - Unreleased

First planned PolyMc Reborn alpha. No release artifact has been declared
published by this file.
