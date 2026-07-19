# ADR 0015: Per-player resource-pack policy

Status: accepted for 0.3 Beta

## Decision

Use a bounded per-player state service with REQUIRED, OPTIONAL, and DISABLED
policy. Observe vanilla response packets through one narrow server-only Mixin;
do not cancel or replace protocol handling. Only APPLIED optional clients see
custom-resource models. REQUIRED relies on vanilla's required-pack disconnect.
DISABLED always uses safe vanilla carriers.

Terminal counters are transition-based and idempotent. Disconnect removes the
live state. The mapping plan and global deterministic pack do not change per
player.

## Consequences

Packet queries remain O(1) and filesystem-free. Optional decline can reduce
fidelity but cannot leak a custom identifier. Native Polymer still wins.
