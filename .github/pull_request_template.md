## Summary

Describe the bounded change and its user-visible outcome.

## Motivation

Explain the problem, evidence, and why this approach fits the project.

## Compatibility impact

- Registry/content types affected:
- Native Polymer behavior preserved:
- Mapping or persistence impact:
- Unsupported/fail-closed behavior:

## Tests

List exact commands actually run and their results. Identify anything not run;
do not infer one test layer from another.

## Documentation

List the README, focused documentation, report shape, migration note, and
changelog updates required by this change.

## Security

Describe input validation, path/data handling, client trust, inventory/entity
authority, packet behavior, and any new failure mode. State “No new security
boundary” only when that has been reviewed.

## Checklist

- [ ] The server keeps real modded objects and behavior authoritative.
- [ ] Native Polymer behavior is not silently overridden.
- [ ] Positive and negative coverage matches the change.
- [ ] Persistent/reportable output remains deterministic.
- [ ] No secret, private path, world, local JAR, or build evidence is committed.
- [ ] License and attribution requirements are satisfied.
