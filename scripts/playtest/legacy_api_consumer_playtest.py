# SPDX-License-Identifier: LGPL-3.0-or-later
"""Build a locked 0.3 API consumer, then run that unchanged JAR on the RC."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROJECT_BUILD = (ROOT / "build").resolve()
BUILD = PROJECT_BUILD / "legacy-api-consumer"
REPOSITORY = BUILD / "maven"
DOWNLOAD = BUILD / "download"
PROJECT = ROOT / "playtest" / "legacy-api-consumer"
SOURCE = ROOT / "playtest" / "api-consumer" / "src"
VERSION = "0.3.0-beta.1+26.1.2"
RC_VERSION = "0.4.0-rc.2+26.1.2"
API_NAME = f"polymc-reborn-api-{VERSION}.jar"
API_SHA256 = "9649606f3381705e5b7548886c332002fc93c338ae1ac70cfd9aa523f0498fe3"
TAG = "v0.3.0-beta.1+26.1.2"
REPOSITORY_ID = "fadeway37/PolyMc-Reborn-Archive"
BASE_COMMIT = "bfe99049ffeb9da60a700a32282102278e6c3bba"
ARTIFACT_ID = 8446985229
ARTIFACT_RUN_ID = 29702813044
ARTIFACT_NAME = f"polymc-reborn-{BASE_COMMIT}"
ARTIFACT_DIGEST = "sha256:83939e933d2e52e39ab49ff6d52efe5961bc120404208b258f8551d7b9063699"


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


def run(
        command: list[str], *, environment: dict[str, str] | None = None,
        cwd: Path = ROOT,
) -> None:
    result = subprocess.run(command, cwd=cwd, env=environment, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"command exited with {result.returncode}: {' '.join(command)}")


def audited_build_environment() -> dict[str, str]:
    """Return an environment whose embedded Git identity matches the audited input."""
    environment = os.environ.copy()
    environment["GITHUB_SHA"] = BASE_COMMIT
    return environment


def reset_generated_directory(path: Path) -> None:
    resolved = path.resolve()
    if resolved == PROJECT_BUILD or PROJECT_BUILD not in resolved.parents:
        raise RuntimeError(f"refusing to reset unsafe generated directory: {resolved}")
    if resolved.exists():
        shutil.rmtree(resolved)
    resolved.mkdir(parents=True)


def validate_actions_artifact(metadata: object) -> dict[str, object]:
    if not isinstance(metadata, dict):
        raise RuntimeError("0.3 Actions artifact metadata is not an object")
    workflow = metadata.get("workflow_run")
    expected = {
        "id": ARTIFACT_ID,
        "name": ARTIFACT_NAME,
        "digest": ARTIFACT_DIGEST,
        "expired": False,
    }
    mismatches = [
        f"{key}={metadata.get(key)!r} (expected {value!r})"
        for key, value in expected.items() if metadata.get(key) != value
    ]
    if not isinstance(workflow, dict):
        mismatches.append("workflow_run is missing")
    else:
        if workflow.get("id") != ARTIFACT_RUN_ID:
            mismatches.append(
                f"workflow_run.id={workflow.get('id')!r} (expected {ARTIFACT_RUN_ID!r})")
        if workflow.get("head_sha") != BASE_COMMIT:
            mismatches.append(
                f"workflow_run.head_sha={workflow.get('head_sha')!r} "
                f"(expected {BASE_COMMIT!r})")
    if mismatches:
        raise RuntimeError("0.3 Actions artifact identity mismatch: " + "; ".join(mismatches))
    return metadata


def acquire_from_actions(destination: Path) -> dict[str, object]:
    metadata_result = subprocess.run(
        ["gh", "api", f"repos/{REPOSITORY_ID}/actions/artifacts/{ARTIFACT_ID}"],
        cwd=ROOT, check=False, capture_output=True, text=True,
    )
    if metadata_result.returncode != 0:
        detail = metadata_result.stderr.strip() or metadata_result.stdout.strip()
        raise RuntimeError(f"cannot query audited 0.3 Actions artifact: {detail}")
    metadata = validate_actions_artifact(json.loads(metadata_result.stdout))
    stage = DOWNLOAD / f"actions-{ARTIFACT_ID}"
    reset_generated_directory(stage)
    run([
        "gh", "run", "download", str(ARTIFACT_RUN_ID),
        "--repo", REPOSITORY_ID,
        "--name", ARTIFACT_NAME,
        "--dir", str(stage),
    ])
    source = stage / "api" / "build" / "libs" / API_NAME
    if not source.is_file():
        raise RuntimeError(f"audited Actions artifact does not contain {API_NAME}")
    observed = sha256(source)
    if observed != API_SHA256:
        raise RuntimeError(
            f"0.3 Actions API SHA-256 mismatch: expected {API_SHA256}, got {observed}")
    shutil.copyfile(source, destination)
    return {
        "method": "github-actions-artifact",
        "repository": REPOSITORY_ID,
        "artifact_id": ARTIFACT_ID,
        "artifact_name": ARTIFACT_NAME,
        "artifact_digest": ARTIFACT_DIGEST,
        "workflow_run_id": ARTIFACT_RUN_ID,
        "head_sha": BASE_COMMIT,
        "expires_at": metadata.get("expires_at"),
    }


def reproduce_from_audited_source(destination: Path) -> dict[str, object]:
    exact = subprocess.check_output(
        ["git", "rev-parse", BASE_COMMIT], cwd=ROOT, text=True).strip()
    if exact != BASE_COMMIT:
        raise RuntimeError(f"audited 0.3 commit mismatch: expected {BASE_COMMIT}, got {exact}")
    acquisition: dict[str, object] | None = None
    failure: Exception | None = None
    cleanup_failure: str | None = None
    with tempfile.TemporaryDirectory(prefix="polymc-reborn-0.3-api-") as temporary:
        stage = Path(temporary).resolve() / "source"
        added = False
        try:
            run(["git", "worktree", "add", "--detach", str(stage), BASE_COMMIT])
            added = True
            wrapper = stage / ("gradlew.bat" if os.name == "nt" else "gradlew")
            run(
                [str(wrapper), "--no-daemon", "--console=plain", ":api:jar"],
                environment=audited_build_environment(),
                cwd=stage,
            )
            source = stage / "api" / "build" / "libs" / API_NAME
            if not source.is_file():
                raise RuntimeError(f"audited source build did not produce {API_NAME}")
            observed = sha256(source)
            if observed != API_SHA256:
                raise RuntimeError(
                    f"reproduced 0.3 API SHA-256 mismatch: expected {API_SHA256}, "
                    f"got {observed}")
            shutil.copyfile(source, destination)
            acquisition = {
                "method": "reproduced-audited-git-worktree",
                "repository": REPOSITORY_ID,
                "head_sha": BASE_COMMIT,
                "expected_published_sha256": API_SHA256,
            }
        except Exception as exception:  # Preserve the original build/identity failure.
            failure = exception
        finally:
            if added:
                cleanup = subprocess.run(
                    ["git", "worktree", "remove", "--force", str(stage)],
                    cwd=ROOT, check=False, capture_output=True, text=True,
                )
                if cleanup.returncode != 0:
                    cleanup_failure = cleanup.stderr.strip() or cleanup.stdout.strip()
        if failure is not None:
            raise failure
        if cleanup_failure is not None:
            raise RuntimeError(f"cannot remove audited 0.3 temporary worktree: {cleanup_failure}")
    if acquisition is None:
        raise RuntimeError("audited 0.3 worktree build produced no acquisition record")
    return acquisition


def prepare_locked_repository() -> tuple[Path, dict[str, object]]:
    DOWNLOAD.mkdir(parents=True, exist_ok=True)
    downloaded = DOWNLOAD / API_NAME
    acquisition: dict[str, object] = {"method": "verified-cache"}
    if not downloaded.is_file() or sha256(downloaded) != API_SHA256:
        if downloaded.exists():
            downloaded.unlink()
        try:
            acquisition = acquire_from_actions(downloaded)
        except (OSError, ValueError, json.JSONDecodeError, RuntimeError) as exception:
            print(
                f"Audited Actions artifact unavailable; reproducing exact 0.3 API: {exception}",
                file=sys.stderr,
            )
            acquisition = reproduce_from_audited_source(downloaded)
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
    acquisition["api_jar_sha256"] = observed
    acquisition["published_tag"] = TAG
    return artifact, acquisition


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
        _, acquisition = prepare_locked_repository()
        run([
            str(wrapper), "-p", str(PROJECT), "clean", "build", "--no-daemon",
            f"-PapiRepository={REPOSITORY}",
        ])
        jar = PROJECT / "build" / "libs" / f"polymc-reborn-legacy-api-consumer-{VERSION}.jar"
        record = verify_consumer(jar)
        record["api_acquisition"] = acquisition
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
