# ADR 0014: Diagnostic policy and support bundles

Status: accepted for 0.3 Beta

## Decision

Diagnostic policy is a strict, versioned, restart-only JSON contract. Rules use
exact codes or bounded safe globs and can suppress or elevate presentation
severity without deleting the original event, decision chain, source severity,
or audit disposition. Regular expressions, scripts, class loading, remote
content, and late mapping mutation are forbidden.

Support bundles are built from a fixed allow-list below the Reborn config and
report roots. They normalize paths and timestamps, sort entries, bound each
entry and total size, and redact tokens, credentials, HMAC material, home paths,
and external-Mod JAR bodies. Symlinks and path escape fail closed.

## Consequences

Policy cannot make unsupported content supported or hide release-gate failures.
The bundle manifest records included, redacted, and rejected sources so support
staff can audit what was omitted.
