# Diagnostic display policy

`config/polymc-reborn/diagnostics-policy.json` is a strict schema-1 local file.
Rules use exact IDs/namespaces or bounded safe globs; regular expressions,
scripts, class loading, commands, and downloads are forbidden. Stable rule
order makes the effective severity deterministic.

Rules may constrain `code`, `registry_id`, `mod_id`, `content_type`,
`provider_id`, `adapter_id`, `mapping_status`, `client_profile`, `pack_status`,
and `decision_id`. Omitted contextual fields are the safe `*` wildcard. The
machine-readable schema ships at
`schema/diagnostics-policy.schema.json` inside the Mod JAR.

Each record retains original and effective severity, matched rule ID, source,
reason, operator note, known-issue flag, and lifecycle stage. Security,
corruption, signature, traversal, authentication, and similar protected
diagnostics cannot be downgraded. Policy changes affect presentation only and
cannot mutate the frozen mapping plan.

Use `/pmcr diagnostics status`, `validate`, `why <code>`, and `list <glob>`.
