# ADR 0008: Publish one source-defined Beta API boundary

- Status: Accepted
- Date: 2026-07-19
- Decision owners: PolyMc Reborn maintainers

## Context

0.2 exposed useful Java packages only inside the production Mod JAR. Adapter
authors could not resolve a documented API coordinate, and an accidental
internal change had no deterministic compatibility gate. Copying API sources
into a second project would allow the production classes and published
contract to drift.

## Decision

The canonical new and legacy API sources live under `api/src/main/java`. The
standalone `api` Loom subproject compiles and publishes those sources as
`io.github.polymcreborn:polymc-reborn-api:0.3.0-beta.1+26.1.2`. The root
production source set compiles the same physical source directory into the Mod
JAR; there is no second source copy or bundled Polymer implementation in the
API artifact.

The backend-neutral planning package is `STABLE` for the 0.3 Beta series.
Explicit GUI/entity packages are `EXPERIMENTAL`. Deliberately selected legacy
packages are `LEGACY_ADAPTED_DEPRECATED`; this is source-migration support, not
old-JAR binary compatibility. `INTERNAL` elements are excluded from the
supported signature.

A Java 25 build-only generator records public/protected classes, records,
fields, constructors, methods, generic types, exceptions, and stability in a
stable text file. `checkApiSignature` compares bytes with the accepted
baseline. The baseline changes only through an explicit update task plus an
ADR/migration/changelog review. A negative JUnit test proves that deleting a
stable method makes verification fail.

An independent consumer resolves the API only from a temporary Maven
repository. It never uses `project(':api')`, and its server implementation is
not shipped in either release artifact.

## Consequences

- The API is bound to official Minecraft 26.1.2 server types where necessary.
- The main Mod and API JAR contain byte-equivalent API contracts compiled from
  one source tree, while the API JAR excludes Polymer, commands, Mixins,
  fixtures, and drivers.
- Beta compatibility changes are reviewable and deterministic, but this is
  not a 1.0 permanence promise.
