# Roadmap

The roadmap separates implemented 0.1 foundations from future work. Items in a
future section are not product claims and must not be inferred from an
interface name alone.

## 0.1.0-alpha.1+26.1.2 MVP

The 0.1 branch is scoped to:

- one server-only Fabric JAR for exactly Minecraft 26.1.2 and Java 25 using
  official names;
- immutable, explainable, deterministic provider planning with native Polymer
  priority;
- conservative Polymer item overlays and simple stable full-cube block/block-
  item overlays;
- safe unsupported classification for complex blocks, entities, and menus;
- strict main configuration and versioned declarative compatibility profiles;
- deterministic, atomic persistent mappings and capacity/corruption errors;
- deterministic resource collection/pack output, manifest, cache, and reports;
- structured diagnostics, administrator inspection/build commands, counters,
  and per-mod summaries;
- selected source-oriented legacy `polymc` bridge and a fixture extension;
- disabled no-op packet fallback SPI;
- JUnit, internal test-mod/GameTest, dedicated-server safety checks, CI, and
  operational/developer documentation.

An alpha is accepted only for behavior actually exercised by its reported
tests. A checked-in interface or roadmap paragraph is not implementation.

## Near-term after the first alpha

- Exercise a scripted real vanilla 26.1.2 client login and fixture interaction
  matrix; until then, keep the explicit no-E2E-test disclaimer.
- Add more explicit, reviewed content adapters and built-in profiles based on
  sanitized compatibility reports.
- Harden mapping/profile migration tooling and add rollback/dry-run operator
  UX.
- Expand semantic item component preservation with adversarial serialization
  tests.
- Improve stateful full-cube model dependency handling without claiming complex
  geometry.
- Publish a stable, separately versioned API artifact after the single-JAR API
  has real downstream feedback.
- Add release signing/provenance and a manual release workflow; CI does not
  auto-publish the initial release.
- Add a versioned diagnostic suppression/promotion policy after its interaction
  with immutable decisions and report auditability is specified.

## Explicit entity work

Future entity support may use Polymer Virtual Entity, but only through explicit
adapters that define:

- visual entity composition and lifecycle;
- equipment and metadata synchronization;
- passengers/leashes/relative transforms;
- hit/interaction proxying and permissions;
- tracking, dimension change, unload, and reconnect behavior;
- bandwidth/cache limits and failure diagnostics.

There is no planned “choose a vaguely similar entity for everything” provider.

## Transaction-safe GUI work

A future generic or profile-driven GUI backend must model standard container
projection, exact slot remapping, data/progress properties, paging, server-side
buttons, and reconciliation. It must pass hostile tests for shift-click, drag,
double-click, hotbar swap, offhand, creative actions, rejected transactions,
disconnect, and desynchronization recovery before being enabled broadly.

## Packet fallback experiments

Packet fallback remains isolated from Polymer and disabled by default. A future
implementation starts with a narrow allow/transform/reject-with-reason policy
for named protocol cases. It must never become a blanket cancellation layer or
a way to conceal unsupported registry data.

## Optional client profiles

`REBORN_COMPANION` may later add optional quality-of-life metadata but will
never be required for vanilla access. `TRUSTED_MODDED` may later allow narrowly
scoped passthrough only after an authenticated handshake verifies exact registry
and mod fingerprints. Fabric presence or an asserted mod list is not enough.

## Out of scope

The project has no 0.1 commitment to Quilt, NeoForge, Geyser/Bedrock, arbitrary
client renderer simulation, runtime remote profile downloads, profile scripts,
forced client mods, unknown-mod passthrough, or a wholesale port of original
PolyMc Mixins.
