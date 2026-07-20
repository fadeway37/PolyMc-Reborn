# Security policy

PolyMc Reborn is pre-release software that processes mod resources, local JSON
configuration, registry metadata, item data, and client-originated creative
inventory input. Treat it as security-sensitive server infrastructure.

## Supported versions

Only the latest commit on `reborn/0.4.0-rc+26.1.2` and the most recent
published release line receive security fixes. Preserved 0.1/0.2/0.3 branches
are audit baselines, not the active development line. No
release currently promises support for Minecraft versions other than exactly
26.1.2.

## Reporting a vulnerability

Use GitHub's private security-advisory mechanism for the PolyMc-Reborn
repository if it is enabled. Otherwise contact the repository owner privately
before opening a public issue. Include the affected commit/version, impact,
minimal reproduction, and whether exploitation requires operator-controlled
configuration, a malicious mod JAR, or an untrusted client.

Do not include access tokens, player personal data, full production paths,
world data, or a weaponized public proof of concept. Maintainers will
acknowledge a complete report when available, assess affected versions, and
coordinate disclosure after a fix or mitigation exists. There is no bug bounty
commitment.

## Security boundaries

- Compatibility profiles are data only. Java class loading, scripts, shell
  execution, and runtime remote-code downloads are forbidden.
- Resource paths are normalized and must remain inside the logical pack root;
  archives and model references must not enable Zip Slip or unbounded
  extraction.
- Mapping corruption, an unknown future schema, and capacity exhaustion are
  explicit errors. Existing state is never silently discarded.
- Creative reverse mapping defaults off, and this RC rejects startup when an
  operator sets it true. No partial packet path is exposed. Any future
  implementation requires authenticated, player/session-bound, expiring and
  replay-safe markers plus an allowlisted component set and attack Playtests;
  Polymer's ordinary restoration metadata is not authentication.
- Unknown clients are `VANILLA`. Merely detecting Fabric is not authentication.
- Packet fallback is disabled by default and the 0.4 RC contains no broad packet
  rewrite engine.
- Reports sanitize local filesystem paths by default. Operators should still
  inspect reports and logs before sharing them.

Mods execute server-side code with the server process's privileges. PolyMc
Reborn cannot sandbox an installed mod and should not be described as doing so.

Use `/pmcr support bundle` when possible. It creates a bounded local whitelist
archive and never uploads it; review its manifest/redaction report before
sharing. Security/corruption/signature/path diagnostics cannot be downgraded by
display policy. Creative reverse mapping and packet fallback remain
fail-closed/disabled in the 0.4 RC.
