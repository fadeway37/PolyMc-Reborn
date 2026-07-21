# Security policy

PolyMc Reborn processes mod resources, strict local configuration, registry metadata, item data, and client-originated input. Treat it as security-sensitive server software.

## Supported versions

Only the latest commit on `main` and the most recent release line receive security fixes. No current release promises support beyond its exact documented Minecraft version.

## Report privately

Use the active repository's private [security advisory form](https://github.com/fadeway37/PolyMc-Reborn/security/advisories/new). If that form is unavailable, contact the repository owner privately before opening a public issue.

Include the affected version or commit, impact, a minimal reproduction, and whether exploitation requires operator configuration, a malicious mod JAR, or an untrusted client. Do not publish access tokens, private keys, player data, worlds, production paths, private logs, or a weaponized proof of concept. There is no bug-bounty commitment.

Ordinary installation and compatibility problems belong in the [troubleshooting guide](https://polymc-reborn-docs.pages.dev/#/en/troubleshooting/start-here), not a security advisory.

## Safe diagnostic sharing

`/pmcr support bundle` creates a bounded local archive and does not upload it. Inspect every file before sharing. The [support-bundle guide](https://polymc-reborn-docs.pages.dev/#/en/administration/support-and-security) explains its contents, and [ask for help](https://polymc-reborn-docs.pages.dev/#/en/troubleshooting/ask-for-help) lists the minimum information for an ordinary bug.

Never submit tokens, keys, worlds, or private data in a public issue.

## Security boundaries

Compatibility profiles cannot load classes, run scripts or shell commands, or download executable code. Resource paths and extraction remain bounded. Corrupt or future mapping data fails closed instead of being discarded. Unknown clients are treated as vanilla. Creative reverse mapping, broad packet fallback, and trusted-modded passthrough remain closed.

Installed mods execute with the server process's privileges; PolyMc Reborn does not sandbox them.
