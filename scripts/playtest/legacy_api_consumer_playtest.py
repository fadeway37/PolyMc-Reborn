# SPDX-License-Identifier: LGPL-3.0-or-later
"""Build a locked 0.3 API consumer, then run that unchanged JAR on the RC."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build" / "legacy-api-consumer"
REPOSITORY = BUILD / "maven"
DOWNLOAD = BUILD / "download"
PROJECT = ROOT / "playtest" / "legacy-api-consumer"
SOURCE = ROOT / "playtest" / "api-consumer" / "src"
VERSION = "0.3.0-beta.1+26.1.2"
RC_VERSION = "0.4.0-rc.1+26.1.2"
API_NAME = f"polymc-reborn-api-{VERSION}.jar"
API_SHA256 = "9649606f3381705e5b7548886c332002fc93c338ae1ac70cfd9aa523f0498fe3"
TAG = "v0.3.0-beta.1+26.1.2"
REPOSITORY_ID = "fadeway37/PolyMc-Reborn"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def source_sha256() -> str:
    digest = hashlib.sha256()
    for path in sorted(candidate for candidate in SOURCE.rglob("*") if candidate.is_file()):
        digest.update(path.relative_to(SOURCE).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def run(command: list[str], *, environment: dict[str, str] | None = None) -> None:
    result = subprocess.run(command, cwd=ROOT, env=environment, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"command exited with {result.returncode}: {' '.join(command)}")


def prepare_locked_repository() -> Path:
    DOWNLOAD.mkdir(parents=True, exist_ok=True)
    downloaded = DOWNLOAD / API_NAME
    if not downloaded.is_file() or sha256(downloaded) != API_SHA256:
        if downloaded.exists():
            downloaded.unlink()
        run([
            "gh", "release", "download", TAG,
            "--repo", REPOSITORY_ID,
            "--pattern", API_NAME,
            "--dir", str(DOWNLOAD),
            "--clobber",
        ])
    observed = sha256(downloaded)
    if observed != API_SHA256:
        raise RuntimeError(f"0.3 API SHA-256 mismatch: expected {API_SHA256}, got {observed}")

    coordinate = REPOSITORY / "io" / "github" / "polymcreborn" / "polymc-reborn-api" / VERSION
    coordinate.mkdir(parents=True, exist_ok=True)
    artifact = coordinate / API_NAME
    shutil.copyfile(downloaded, artifact)
    pom = coordinate / f"polymc-reborn-api-{VERSION}.pom"
    pom.write_text(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" "
        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
        "xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 "
        "https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
        "  <modelVersion>4.0.0</modelVersion>\n"
        "  <groupId>io.github.polymcreborn</groupId>\n"
        "  <artifactId>polymc-reborn-api</artifactId>\n"
        f"  <version>{VERSION}</version>\n"
        "</project>\n",
        encoding="utf-8",
        newline="\n",
    )
    return artifact


def verify_consumer(jar: Path) -> dict[str, object]:
    if not jar.is_file():
        raise RuntimeError(f"legacy consumer JAR is missing: {jar}")
    with zipfile.ZipFile(jar) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
        class_names = sorted(name for name in archive.namelist() if name.endswith(".class"))
        if not class_names:
            raise RuntimeError("legacy consumer contains no classes")
        for name in class_names:
            header = archive.read(name)[:8]
            if len(header) != 8 or int.from_bytes(header[6:8], "big") != 69:
                raise RuntimeError(f"legacy consumer class is not Java 25: {name}")
    if metadata.get("id") != "polymc-reborn-api-consumer" or metadata.get("version") != VERSION:
        raise RuntimeError("legacy consumer metadata is not pinned to 0.3 Beta")
    dependency = metadata.get("depends", {}).get("polymc-reborn", "")
    if VERSION not in dependency or RC_VERSION not in dependency:
        raise RuntimeError("legacy consumer metadata does not explicitly permit both Beta and RC")
    return {
        "schema_version": 1,
        "compiled_against": VERSION,
        "runtime_version": RC_VERSION,
        "api_jar_sha256": API_SHA256,
        "consumer_source_sha256": source_sha256(),
        "consumer_jar": jar.name,
        "consumer_jar_sha256": sha256(jar),
        "class_count": len(class_names),
        "recompiled_against_rc": False,
    }


def main() -> int:
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    try:
        prepare_locked_repository()
        run([
            str(wrapper), "-p", str(PROJECT), "clean", "build", "--no-daemon",
            f"-PapiRepository={REPOSITORY}",
        ])
        jar = PROJECT / "build" / "libs" / f"polymc-reborn-legacy-api-consumer-{VERSION}.jar"
        record = verify_consumer(jar)
        environment = os.environ.copy()
        environment["POLYMC_REBORN_API_CONSUMER_JAR"] = str(jar.resolve())
        run([str(wrapper), "--no-daemon", "--console=plain", "runProductionClientPlaytest"],
            environment=environment)

        source = ROOT / "build" / "playtest" / "single-client"
        target = ROOT / "build" / "playtest" / "legacy-api-consumer"
        if not source.is_dir():
            raise RuntimeError("production client evidence is missing after legacy consumer run")
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(source, target)
        server = json.loads((target / "server-state.json").read_text(encoding="utf-8"))
        loaded = server.get("loaded_server_mods", [])
        expected_mod = f"polymc-reborn-api-consumer@{VERSION}"
        if server.get("result") != "passed" or expected_mod not in loaded:
            raise RuntimeError("RC server did not run the exact 0.3 binary consumer successfully")
        record["result"] = "passed"
        record["runtime_loaded_mod"] = expected_mod
        record["provider_item_block_gui_entity_resource_contributor"] = True
        (target / "binary-compatibility.json").write_text(
            json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
        print(f"0.3 binary API consumer playtest passed: {jar.name}")
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError, RuntimeError,
            zipfile.BadZipFile) as exception:
        print(f"0.3 binary API consumer playtest failed: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
