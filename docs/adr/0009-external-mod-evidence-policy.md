# ADR 0009: External-Mod evidence policy

Status: accepted for 0.3 Beta

## Decision

Compatibility claims require production-process evidence. External-Mod
evidence uses exact version, project/file identity, source URL, license, and
SHA-256 pins for at least two independently maintained content Mods. At least
one scenario must join the server with the client lacking that Mod and interact
with a real registered item or block. Evidence is scenario-specific,
structured, sanitized, and separated under `build/playtest/external-mods/`.

The external client does not install tested content Mods. A download, launcher,
hash, license, or process failure is infrastructure failure, not an
`UNSUPPORTED` classification. A passing named item/block scenario never means
every feature of that Mod works.

## Consequences

Beta gates are slower and remain separate workflows. They establish real
boundaries without claiming a zero-Mod vanilla client or universal Mod support.
