#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later

set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
cd "$repository_root"

failures=0

report_failure() {
    printf '::error::%s\n' "$1"
    failures=$((failures + 1))
}

required_lockfiles=(
    'gradle.lockfile'
    'playtest/client-driver/gradle.lockfile'
)
for lockfile in "${required_lockfiles[@]}"; do
    if [[ ! -f "$lockfile" ]]; then
        report_failure "$lockfile is missing; every Gradle project must lock dependency resolution"
    elif ! git ls-files --error-unmatch "$lockfile" >/dev/null 2>&1; then
        report_failure "$lockfile exists but is not tracked"
    fi
done

mapfile -t tracked_jars < <(git ls-files | grep -Ei '\.jar$' || true)
for jar_path in "${tracked_jars[@]}"; do
    if [[ "$jar_path" != 'gradle/wrapper/gradle-wrapper.jar' ]]; then
        report_failure "Unexpected tracked JAR: $jar_path (dependencies must come from pinned repositories)"
    fi
done

mapfile -t tracked_runtime_outputs < <(
    git ls-files | grep -E \
        '^(build|\.gradle|run|config|cache)/|^playtest/client-driver/(build|\.gradle|run|config|cache)/' \
        || true
)
for output_path in "${tracked_runtime_outputs[@]}"; do
    report_failure "Generated/runtime path is tracked: $output_path"
done

mapfile -t sensitive_files < <(
    git ls-files | grep -Ei '(^|/)(\.env($|\.)|[^/]+\.(jks|p12|pfx|pem|key|keystore))$' || true
)
for sensitive_path in "${sensitive_files[@]}"; do
    report_failure "Potential secret-bearing file is tracked: $sensitive_path"
done

mapfile -t java_sources < <(git ls-files 'src/**/*.java')
for source_path in "${java_sources[@]}"; do
    if ! head -n 15 "$source_path" | grep -Fq 'SPDX-License-Identifier: LGPL-3.0-or-later'; then
        report_failure "Missing LGPL-3.0-or-later SPDX header near the top of $source_path"
    fi
done

if git grep -n -I -E \
    '([A-Za-z]:[\\/](Users|Documents and Settings)[\\/][^[:space:]]+|/home/[^/[:space:]]+|/Users/[^/[:space:]]+)' \
    -- . ':(exclude)scripts/ci/check-repository.sh' \
    ':(exclude)src/test/java/io/github/polymcreborn/pack/DeterministicResourcePackTest.java' \
    >/tmp/polymc-reborn-absolute-paths.txt 2>/dev/null; then
    sed 's/^/::error file=/' /tmp/polymc-reborn-absolute-paths.txt
    report_failure 'Tracked text contains a user-specific absolute filesystem path'
fi

if git grep -n -I -E \
    '(-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|github_pat_[A-Za-z0-9_]+|gh[opsu]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})' \
    -- . ':(exclude)scripts/ci/check-repository.sh' >/tmp/polymc-reborn-secrets.txt 2>/dev/null; then
    sed 's/^/::error file=/' /tmp/polymc-reborn-secrets.txt
    report_failure 'Tracked text appears to contain a credential or private key'
fi

if git grep -n -I -E 'net\.minecraft\.client(\.|/)' -- src/main/java >/tmp/polymc-reborn-client-imports.txt 2>/dev/null; then
    sed 's/^/::error file=/' /tmp/polymc-reborn-client-imports.txt
    report_failure 'Server/common production source references a client-only Minecraft package'
fi

if git grep -n -I -E '(flatDir|mavenLocal|files[[:space:]]*\([^)]*\.jar|yarn_mappings|net\.fabricmc:yarn|mappings[[:space:]]+.*yarn)' \
    -- '*.gradle' '*.gradle.kts' 'gradle.properties' 'settings.gradle' 'settings.gradle.kts' \
    >/tmp/polymc-reborn-forbidden-build-inputs.txt 2>/dev/null; then
    sed 's/^/::error file=/' /tmp/polymc-reborn-forbidden-build-inputs.txt
    report_failure 'Build configuration contains a forbidden local-JAR or Yarn mapping declaration'
fi

if [[ "${1:-}" == '--working-tree' ]]; then
    if ! git diff --quiet -- || ! git diff --cached --quiet --; then
        git status --short
        report_failure 'The build modified a tracked file'
    fi

    mapfile -t untracked_files < <(git ls-files --others --exclude-standard)
    if ((${#untracked_files[@]} > 0)); then
        printf '%s\n' "${untracked_files[@]}"
        report_failure 'The build created an unignored, untracked file'
    fi
elif [[ $# -gt 0 ]]; then
    report_failure "Unknown argument: $1"
fi

if ((failures > 0)); then
    printf 'Repository policy checks failed with %d issue(s).\n' "$failures" >&2
    exit 1
fi

printf 'Repository policy checks passed.\n'
