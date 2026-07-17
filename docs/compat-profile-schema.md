# Declarative compatibility profile schema

Compatibility profiles are strict, versioned JSON data. They describe narrow
matching and action rules; they cannot load Java classes, execute scripts or
shell commands, or download code.

Administrator profiles are read from:

```text
config/polymc-reborn/compat.d/*.json
```

The mod also ships built-in safety profiles under
`polymc-reborn/compat/`. Administrator and bundled files pass through the same
decoder and validation rules. The authoritative v1 schema is
[`compat-profile-v1.schema.json`](../src/main/resources/polymc-reborn/schema/compat-profile-v1.schema.json).

## Version 1 document

All top-level fields are required:

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | integer | Must equal `1`. Future versions require an explicit migrator. |
| `id` | string | Stable, unique profile ID. A namespaced ID is recommended. |
| `target_mod` | string | Fabric mod ID whose content is targeted, or `*` for a built-in global policy. |
| `target_version` | string | Fabric version predicate, or `*`. |
| `optional_dependencies` | string array | Canonical mod IDs that must be loaded. Version predicates are not supported in this field in v1; `target_version` applies only to `target_mod`. |
| `priority` | integer | `-10000..10000`; higher-priority profiles are evaluated first, then ID. |
| `description` | string | Non-empty operator-readable purpose. |
| `rules` | array | One or more objects containing exactly `match` and `action`. |

Unknown fields are errors at every object level. This matches `config.json` and
prevents a misspelled safety field from being ignored. Duplicate profile IDs
are errors. Operator files are enumerated in filename order, then all validated
profiles sort by descending priority and ID.

## Match object

A rule's `match` object must contain at least one predicate. All predicates that
are present must match:

- `exact_id`: exact namespaced registry ID;
- `namespace`: exact registry namespace;
- `glob`: safe whole-identifier glob using only lowercase identifier literals,
  `*`, and `?`;
- `registry_type`: `item`, `block`, `entity`, or `gui`;
- `block_properties`: property/value pairs matched against normalized block
  descriptor properties;
- `owner_mod`: exact owning Fabric mod ID.

The glob engine is anchored to the whole canonical identifier, limits a pattern
to 256 characters and input to 1024 characters, and uses bounded dynamic
programming instead of a regular expression. There is no catastrophic-
backtracking regex escape hatch. Prefer `exact_id`; namespace/glob rules should
have negative tests proving their scope.

For `block_properties`, discovery stores each property's allowed values in
sorted order. A rule value matches one allowed value; `"*"` matches property
presence. The 0.1 planner still creates one decision per block registry ID, not
one carrier per state.

## Action object

Every action contains exactly these required fields:

- `type`: one of the v1 action types below;
- `value`: a string; use `""` only for `disable_auto_mapping`;
- `override_native_polymer`: Boolean, normally `false`.

The v1 action types are:

| Type | `value` meaning |
| --- | --- |
| `disable_auto_mapping` | Empty string; classify the match as profile-disabled/unsupported. |
| `item_carrier_category` | `food`, `drink`, `tool`, `armor`, `bow`, `crossbow`, `shield`, `throwable`, `block_item`, or `material`. |
| `block_strategy` | `textured-full-cube`; rejected unless the source is a stable full cube without a block entity. |
| `vanilla_fallback_state` | Exact vanilla, block-entity-free full-cube state; the backend parses and validates it. |
| `entity_replacement` | Records a vanilla replacement request as unsupported/future; it does not install an entity backend in 0.1. |
| `gui_classification` | Records an explicit menu classification as unsupported/future; it does not install a generic GUI. |

Diagnostic suppression/promotion is postponed: `diagnostic_level` is rejected by
the v1 Java parser and is not listed by the JSON Schema. This avoids accepting a
configuration value that would have no runtime effect.

An `override_native_polymer: true` action is considered by the administrator-
forced tier only when `config.json` also sets `override_native_polymer` to true.
Both gates and the result are written to the decision trace. A profile never
gets a generic “execute this class” action.

## Example

```json
{
  "schema_version": 1,
  "id": "polymc-reborn:example-cubes",
  "target_mod": "examplemod",
  "target_version": ">=2.0.0 <3.0.0",
  "optional_dependencies": ["examplelib"],
  "priority": 100,
  "description": "Conservative carriers for Example Mod content",
  "rules": [
    {
      "match": {
        "exact_id": "examplemod:charged_ingot",
        "registry_type": "item",
        "owner_mod": "examplemod"
      },
      "action": {
        "type": "item_carrier_category",
        "value": "material",
        "override_native_polymer": false
      }
    },
    {
      "match": {
        "namespace": "examplemod",
        "glob": "examplemod:*_machine",
        "registry_type": "block",
        "owner_mod": "examplemod"
      },
      "action": {
        "type": "disable_auto_mapping",
        "value": "",
        "override_native_polymer": false
      }
    }
  ]
}
```

## Main configuration

The authoritative schema is
[`config-v1.schema.json`](../src/main/resources/polymc-reborn/schema/config-v1.schema.json).
The generated v1 defaults are:

```json
{
  "schema_version": 1,
  "enabled": true,
  "generate_resource_pack": true,
  "persistent_mappings": true,
  "safe_mode": true,
  "log_decision_chains": true,
  "packet_fallback_enabled": false,
  "creative_reverse_mapping_enabled": false,
  "override_native_polymer": false,
  "report_formats": ["json", "markdown"],
  "cache_limits": {
    "max_entries": 4096,
    "max_bytes": 67108864
  },
  "resource_extraction_limits": {
    "max_files": 10000,
    "max_single_file_bytes": 8388608,
    "max_total_bytes": 268435456
  }
}
```

`enabled: false` skips every Reborn overlay, keeps independently native Polymer
content visible as `NATIVE`, and reports other discovered entries as disabled.
`generate_resource_pack: false` omits custom item-model references and warns
that automatic block textures are unavailable. `persistent_mappings: false`
does not read or write `mappings-v1.json`, so carrier stability across restarts
is intentionally not promised. `safe_mode` is supplied to providers, but even
when false it does not disable the hard no-scripts/no-client-trust/no-complex-
shape boundaries. `log_decision_chains` emits the frozen chains at debug level.

`packet_fallback_enabled` is a Boolean that defaults to false. Setting it true
records a warning, but the 0.1 backend remains a disabled no-op; it does not
activate packet conversion. Native override also defaults false. Creative
reversal is not wired to a runtime conversion path in 0.1: setting
`creative_reverse_mapping_enabled` to true is a fail-fast startup error rather
than permission to trust unsigned Polymer restoration data.
Writers use a sibling temporary file, force its bytes to disk, and replace the
target atomically where supported.

Validation errors identify the source and logical JSON path. Reports should
sanitize an absolute server path before exposing it to an operator or support
bundle.

## Validation and migration

Run:

```text
/polymcreborn config validate
```

The command parses current files and reports errors, but does not apply rules to
an already frozen plan. Restart after changing a mapping-affecting profile.

An unknown future `schema_version` is rejected with a migration-required
message. `CompatProfileMigrator` reserves explicit `fromVersion`, `toVersion`,
and deterministic JSON transformation. A future migrator must validate output,
write a backup, and atomically replace the source; v1 does not silently accept
future fields.
