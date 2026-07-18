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

python_command=''
for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 \
        && "$candidate" -c 'import json, zipfile' >/dev/null 2>&1; then
        python_command="$candidate"
        break
    fi
done
if [[ -z "$python_command" ]]; then
    printf 'A working Python 3 interpreter is required to inspect release metadata safely.\n' >&2
    exit 1
fi

release_jar="${release_jars[0]}"
expected_git_commit="${GITHUB_SHA:-$(git rev-parse HEAD)}"
expected_git_dirty='false'
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    expected_git_dirty='true'
fi

export POLYMC_EXPECTED_GIT_COMMIT="$expected_git_commit"
export POLYMC_EXPECTED_GIT_DIRTY="$expected_git_dirty"

"$python_command" - "$release_jar" <<'PY'
import json
import os
import re
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath


def fail(message: str) -> None:
    failures.append(message)


def read_gradle_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def read_manifest(raw: bytes) -> dict[str, str]:
    logical_lines: list[str] = []
    for line in raw.decode("utf-8").replace("\r\n", "\n").split("\n"):
        if line.startswith(" "):
            if not logical_lines:
                raise ValueError("manifest starts with an invalid continuation line")
            logical_lines[-1] += line[1:]
        elif line:
            logical_lines.append(line)
    result: dict[str, str] = {}
    for line in logical_lines:
        key, separator, value = line.partition(": ")
        if not separator:
            raise ValueError(f"invalid manifest line: {line!r}")
        result[key] = value
    return result


jar_path = Path(sys.argv[1])
properties = read_gradle_properties(Path("gradle.properties"))
expected_version = properties["mod_version"]
expected_minecraft = properties["minecraft_version"]
expected_loader = properties["loader_version"]
expected_fabric_api = properties["fabric_api_version"]
expected_polymer = properties["polymer_version"]
expected_commit = os.environ["POLYMC_EXPECTED_GIT_COMMIT"]
expected_dirty = os.environ["POLYMC_EXPECTED_GIT_DIRTY"]
failures: list[str] = []

try:
    archive = zipfile.ZipFile(jar_path)
except (OSError, zipfile.BadZipFile) as error:
    raise SystemExit(f"Cannot open distributable JAR {jar_path}: {error}") from error

with archive:
    entries = archive.namelist()
    entry_set = set(entries)
    if len(entries) != len(entry_set):
        fail("distributable JAR contains duplicate ZIP entry names")

    for name in entries:
        path = PurePosixPath(name)
        if (not name or "\\" in name or name.startswith("/")
                or re.match(r"^[A-Za-z]:", name)
                or ".." in path.parts):
            fail(f"unsafe or non-normalized JAR entry path: {name!r}")

    required_entries = {
        "META-INF/MANIFEST.MF",
        "fabric.mod.json",
        "NOTICE.md",
        "THIRD_PARTY_NOTICES.md",
    }
    for required in sorted(required_entries - entry_set):
        fail(f"distributable JAR is missing {required}")
    if not any(re.fullmatch(r"LICENSE(?:_polymc-reborn)?", name) for name in entries):
        fail("distributable JAR is missing its LGPL license file")

    forbidden_prefixes = (
        "build/",
        "run/",
        "playtest/",
        "reports/",
        "screenshots/",
        "io/github/polymcreborn/gametest/",
        "io/github/polymcreborn/playtest/",
        "io/github/polymcreborn/fixture/",
    )
    forbidden_names = re.compile(
        r"(^|/)(polymc-reborn-(?:client-driver|gametest)|playtest-fixtures|"
        r"server-observations|loaded-client-mods|client-playtest)(?:[./-]|$)",
        re.IGNORECASE,
    )
    forbidden_entries = sorted(
        name for name in entries
        if name.startswith(forbidden_prefixes) or forbidden_names.search(name)
    )
    if forbidden_entries:
        fail("test fixtures, client drivers, or generated evidence leaked into the release JAR: "
             + ", ".join(forbidden_entries))

    nested_jars = sorted(
        name for name in entries
        if name.lower().endswith(".jar") or name.startswith("META-INF/jars/")
    )
    if nested_jars:
        fail("release JAR contains nested or shaded JARs: " + ", ".join(nested_jars))

    class_entries = sorted(name for name in entries if name.endswith(".class"))
    if not class_entries:
        fail("release JAR contains no compiled classes")
    wrong_class_versions: list[str] = []
    for name in class_entries:
        header = archive.read(name)[:8]
        if len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe":
            fail(f"invalid Java class header: {name}")
            continue
        major = struct.unpack(">H", header[6:8])[0]
        if major != 69:
            wrong_class_versions.append(f"{name} (major {major})")
    if wrong_class_versions:
        fail("classes are not compiled for Java 25 / class major 69: "
             + ", ".join(wrong_class_versions))

    try:
        manifest = read_manifest(archive.read("META-INF/MANIFEST.MF"))
    except (KeyError, UnicodeDecodeError, ValueError) as error:
        fail(f"cannot parse META-INF/MANIFEST.MF: {error}")
        manifest = {}

    expected_manifest = {
        "Implementation-Title": "PolyMc Reborn",
        "Implementation-Version": expected_version,
        "Minecraft-Version": expected_minecraft,
        "Git-Commit": expected_commit,
        "Git-Dirty": expected_dirty,
    }
    for key, expected in expected_manifest.items():
        actual = manifest.get(key)
        if actual != expected:
            fail(f"manifest {key} is {actual!r}; expected {expected!r}")

    try:
        metadata = json.loads(archive.read("fabric.mod.json"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"cannot parse fabric.mod.json: {error}")
        metadata = {}

    expected_metadata = {
        "schemaVersion": 1,
        "id": "polymc-reborn",
        "version": expected_version,
        "name": "PolyMc Reborn",
        "environment": "server",
        "license": "LGPL-3.0-or-later",
    }
    for key, expected in expected_metadata.items():
        actual = metadata.get(key)
        if actual != expected:
            fail(f"fabric.mod.json {key} is {actual!r}; expected {expected!r}")

    provides = metadata.get("provides")
    if not isinstance(provides, list) or "polymc" not in provides:
        fail("fabric.mod.json must provide the legacy polymc Mod ID")

    dependencies = metadata.get("depends")
    expected_dependencies = {
        "fabricloader": f">={expected_loader}",
        "minecraft": f"={expected_minecraft}",
        "java": ">=25",
        "fabric-api": f">={expected_fabric_api}",
        "polymer-core": f"={expected_polymer}",
        "polymer-blocks": f"={expected_polymer}",
        "polymer-resource-pack": f"={expected_polymer}",
        "polymer-virtual-entity": f"={expected_polymer}",
    }
    if not isinstance(dependencies, dict):
        fail("fabric.mod.json depends must be an object")
    else:
        for mod_id, expected in expected_dependencies.items():
            actual = dependencies.get(mod_id)
            if actual != expected:
                fail(f"fabric.mod.json dependency {mod_id} is {actual!r}; expected {expected!r}")

    breaks = metadata.get("breaks")
    if not isinstance(breaks, dict) or breaks.get("polymc-extra") != "*":
        fail("fabric.mod.json must declare polymc-extra as incompatible")
    if isinstance(breaks, dict) and "polymc" in breaks:
        fail("fabric.mod.json must not break the polymc alias it provides")

    main_entrypoints = metadata.get("entrypoints", {}).get("main", [])
    if "io.github.polymcreborn.core.PolyMcReborn" not in main_entrypoints:
        fail("fabric.mod.json is missing the PolyMc Reborn server entrypoint")
    if metadata.get("jars"):
        fail("fabric.mod.json declares nested JARs; dependencies must remain external")

if failures:
    for failure in failures:
        print(f"::error::{failure}", file=sys.stderr)
    raise SystemExit(f"Release JAR policy checks failed with {len(failures)} issue(s).")

print(
    f"Validated distributable JAR: {jar_path} "
    f"({len(class_entries)} Java 25 classes, commit {expected_commit}, dirty={expected_dirty})"
)
PY
