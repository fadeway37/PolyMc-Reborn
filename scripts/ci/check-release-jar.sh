#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later

set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
cd "$repository_root"

mapfile -t release_jars < <(
    find build/libs -maxdepth 1 -type f -name '*.jar' \
        ! -name '*-dev.jar' \
        ! -name '*-sources.jar' \
        ! -name '*-javadoc.jar' \
        -print | sort
)

if ((${#release_jars[@]} != 1)); then
    printf 'Expected exactly one distributable JAR, found %d:\n' "${#release_jars[@]}" >&2
    printf '  %s\n' "${release_jars[@]:-<none>}" >&2
    exit 1
fi

release_jar="${release_jars[0]}"
entries_file="$(mktemp)"
manifest_file="$(mktemp)"
metadata_file="$(mktemp)"
trap 'rm -f "$entries_file" "$manifest_file" "$metadata_file"' EXIT

jar tf "$release_jar" >"$entries_file"

if grep -Eq '(^/|^[A-Za-z]:|(^|/)\.\.(/|$)|\\)' "$entries_file"; then
    printf 'Distributable JAR contains an unsafe or non-normalized entry path.\n' >&2
    exit 1
fi

for required_entry in META-INF/MANIFEST.MF fabric.mod.json NOTICE.md; do
    if ! grep -Fxq "$required_entry" "$entries_file"; then
        printf 'Distributable JAR is missing %s.\n' "$required_entry" >&2
        exit 1
    fi
done

if ! grep -Eq '^LICENSE(_polymc-reborn)?$' "$entries_file"; then
    printf 'Distributable JAR is missing its LGPL license file.\n' >&2
    exit 1
fi

# ZIP/JAR manifests use CRLF by specification. MSYS may transparently convert
# those line endings while Linux preserves them, so normalize before matching.
unzip -p "$release_jar" META-INF/MANIFEST.MF | tr -d '\r' >"$manifest_file"
unzip -p "$release_jar" fabric.mod.json | tr -d '\r' >"$metadata_file"

for manifest_key in Implementation-Version Minecraft-Version Git-Commit Git-Dirty; do
    if ! grep -Eq "^${manifest_key}: .+" "$manifest_file"; then
        printf 'Manifest is missing a non-empty %s attribute.\n' "$manifest_key" >&2
        exit 1
    fi
done

if ! grep -Eq '^Minecraft-Version: 26\.1\.2$' "$manifest_file"; then
    printf 'Manifest does not identify Minecraft 26.1.2.\n' >&2
    exit 1
fi

if ! grep -Fq '"id": "polymc-reborn"' "$metadata_file" || \
   ! grep -Fq '"minecraft": "=26.1.2"' "$metadata_file"; then
    printf 'fabric.mod.json does not contain the expected mod ID and exact Minecraft dependency.\n' >&2
    exit 1
fi

printf 'Validated distributable JAR: %s\n' "$release_jar"
