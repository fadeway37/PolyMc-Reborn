# Resource-pack pipeline

The resource pack is a client presentation artifact. It never replaces real
server objects and it is not evidence that every behavior of an entry is
compatible.

## Inputs

Inputs come from selected mapping decisions, explicit `ResourceContributor`s,
bundled Reborn assets, and approved assets in installed mod containers.
Polymer's resource-pack API is the integration/output backend. AutoHost may
consume the resulting Polymer pack but is optional and is not a Reborn runtime
dependency.

Only logical resource paths are placed in reports/manifests. The pipeline does
not report the absolute mod JAR or game directory by default.

## Collection and validation

For owners of selected decisions and registered resource contributors, the
collector:

1. sorts owner mod IDs, mod root paths, contributor owner/class keys, and final
   normalized destination paths;
2. converts `\` to `/`, rejects absolute/drive/UNC paths, removes `.` segments,
   and rejects `..`, NUL, empty-segment, or root-escape paths;
3. enforces per-file, file-count, total-uncompressed-size, and bounded-cache
   limits from `config.json`, plus a fixed model-parent depth guard;
4. reads assets without following an archive entry outside its logical root;
5. parses item/block model parents and texture references and traverses them
   with a visited set and depth limit;
6. checks automatic item mappings for their 26.1 namespaced item definition;
7. records missing models/textures/item definitions, malformed JSON,
   dependency cycles, and unsupported references in compatibility diagnostics;
8. hashes normalized file bytes and records their source IDs.

When an item definition is missing, the overlay omits its custom `ITEM_MODEL`
component and retains the safe carrier's built-in model. Reborn does not claim
to translate a client-only renderer.

This is the Zip Slip boundary: an archive entry such as
`../../server.properties`, an absolute path, or a normalized root escape is an
error and is never extracted/written.

## Duplicate policy

Identical bytes at the same destination are deduplicated and recorded. Different
bytes at the same destination are a conflict unless a documented, deterministic
override layer explicitly owns that path. A conflict includes both logical
source IDs and hashes; it does not include sensitive absolute paths.

Filesystem enumeration order never determines a winner.

## Deterministic output

The pack writer uses:

- UTF-8 JSON with canonical key/entry ordering;
- lexicographically sorted normalized ZIP entry names;
- one entry per destination path;
- normalized ZIP timestamps and stable compression settings;
- generated metadata/manifest derived only from normalized content and pinned
  format versions;
- SHA-256 content hashes (and Polymer-required transport hash where applicable).

Polymer's current default ZIP generator already sorts entry paths and uses a
normalized zero timestamp. Given the same mapping store, configuration,
resource bytes, and implementation version, two Reborn builds must therefore
produce byte-identical output and the same hash. Tests compare archive bytes
and manifest bytes, not just extracted contents.

The active Polymer pack is built to a temporary file. Reborn reuses the bounded
cache artifact only when that complete newly generated ZIP is byte-identical;
the cache is rebuildable, while persistent mappings are not.

## Manifest and reports

The embedded Reborn manifest records schema version and sorted normalized input
entries with size, SHA-256, and logical source ID. The separate resource-pack
report records the final Polymer-pack SHA-256, entry and byte counts, cache-hit
state, and Polymer generation issue summary. Collection-time dependency
warnings remain in the compatibility report. Identical duplicate paths are
deduplicated internally; conflicting bytes abort the build and therefore do not
produce a new successful resource-pack report.

Latest reports are written to:

```text
config/polymc-reborn/reports/resource-pack-latest.json
config/polymc-reborn/reports/resource-pack-latest.md
```

Compatibility reports reference the same resource IDs/hashes so an operator
can trace a missing texture back to a mapping decision.

## Building and distributing

An administrator can request a build with:

```text
/polymcreborn pack build
```

The command builds from the frozen plan to a sibling temporary path, validates
the result, then replaces the published path atomically where supported. This
wrapper is necessary because Polymer's direct `build(Path)` removes the target
first. The command cannot discover/remap new registry content online. If
configuration/profile changes would affect mappings, restart before building.

Polymer AutoHost can be installed and configured separately to host/send the
main Polymer pack. Without AutoHost, the operator must host and configure the
pack using normal server facilities. A vanilla client is not required to
install a mod, but mapped textured content may look degraded or be unavailable
if the client rejects a required pack.

Operators are responsible for redistribution rights for Minecraft and mod
assets. The pipeline's ability to extract an asset is not a license grant.

## Failure behavior

Traversal, size-limit violations, conflicting output, invalid model dependency
graphs, interrupted writes, and hash mismatches are explicit diagnostics. The
pipeline does not publish a partially written pack as the latest successful
artifact. A previous successful cache artifact is reused only after a fresh
build is byte-identical to it.
