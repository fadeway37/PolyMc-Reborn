# Local support bundle

`/pmcr support bundle` creates
`config/polymc-reborn/support/polymc-reborn-support.zip`. Nothing is uploaded.
The builder reads only a bounded whitelist of Reborn configuration, policy,
reports, and a generated runtime summary. Entry names and timestamps are
deterministic; writes are adjacent-temporary and atomic.

Worlds, player identifiers, environment variables, JARs, raw logs, caches,
absolute paths, authorization values, tokens, passwords, and HMAC material are
excluded or redacted. A manifest and redaction report explain every included
entry. Symlinks, oversized sources, traversal, unsafe names, or an oversized
bundle fail closed.

`/pmcr support bundle status` reports the most recent local size/hash/entry
count without uploading it.
