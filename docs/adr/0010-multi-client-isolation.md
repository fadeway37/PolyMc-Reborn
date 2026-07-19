# ADR 0010: Multi-client isolation

Status: accepted for 0.3 Beta

## Decision

The production multi-client gate launches one dedicated server and two distinct
Minecraft processes. Each client receives its own game directory, UUID,
username, report tree, screenshot tree, log, and minimal allow-listed Fabric
driver modules. Clients coordinate only through bounded marker files owned by
the harness; neither client can read server plans or share in-process statics.

The scenario proves simultaneous joins, different per-player pack choices,
independent authoritative GUI transactions, guarded entity interactions,
Client B survival while Client A disconnects, and a clean Client A reconnect.
Missing markers, duplicate surrogates, timeouts, forced cleanup, or non-zero
process exits fail the gate.

## Consequences

Single-client success cannot substitute for this gate. The evidence must retain
both mod lists, process records, assertions, logs, and real screenshots.
