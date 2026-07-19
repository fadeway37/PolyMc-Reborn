# ADR 0012: Creative reverse capabilities

Status: accepted fail-closed boundary for 0.3 Beta

## Decision

The Beta does not expose a runtime creative reverse-mapping capability. Setting
`creative_reverse_mapping_enabled` to true is a startup error with a specific
diagnostic. Pure marker validation remains covered by hostile-input tests, but
it is not wired into the creative slot packet path because the project does not
yet have a server-issued, session-bound, replay-resistant capability with a
narrow component allow-list.

## Consequences

There is intentionally no `runCreativePlaytest` task and no apparent-but-dead
configuration. Survival mappings remain available. A future implementation
requires an ADR amendment, explicit item allow-list, authenticated session and
slot binding, replay and expiry checks, real-client attack tests, and duplicate
or privilege-escalation proof.
