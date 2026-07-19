#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
set -euo pipefail
project_root="$(cd "$(dirname "$0")/../.." && pwd)"
evidence="$project_root/build/reproducibility"

build_hashes() {
  local output="$1"
  "$project_root/gradlew" clean jar sourcesJar javadocJar \
    :api:jar :api:sourcesJar :api:javadocJar --no-daemon >&2
  : > "$output"
  while IFS= read -r archive; do
    printf '%s %s\n' "$(basename "$archive")" "$(sha256sum "$archive" | cut -d' ' -f1)" >> "$output"
  done < <(find "$project_root/build/libs" "$project_root/api/build/libs" -maxdepth 1 \
    -type f -name 'polymc-reborn-*.jar' | sort)
  test "$(wc -l < "$output")" -eq 6
}

first="$(mktemp)"
second="$(mktemp)"
trap 'rm -f "$first" "$second"' EXIT
build_hashes "$first"
build_hashes "$second"
diff -u "$first" "$second"
mkdir -p "$evidence"
python3 - "$evidence/archives.json" "$first" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
values = dict(line.split() for line in pathlib.Path(sys.argv[2]).read_text().splitlines())
data = {"schema_version": 1, "result": "passed", "archive_count": len(values),
        "first": values, "second": values}
path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
PY
