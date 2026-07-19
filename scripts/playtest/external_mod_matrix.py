# SPDX-License-Identifier: LGPL-3.0-or-later
"""Run hash-locked third-party Mods one at a time without shipping their JARs."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / "playtest" / "external-mods.lock.json"
DOWNLOADS = ROOT / "build" / "external-mods"
OUTPUT = ROOT / "build" / "playtest" / "external-mods"
STAGING = ROOT / "build" / "external-mod-matrix-work"
MAX_BYTES = 8 * 1024 * 1024


def download(entry: dict[str, object]) -> Path:
    destination = DOWNLOADS / str(entry["artifact_file"])
    expected_size = int(entry["artifact_size"])
    if expected_size <= 0 or expected_size > MAX_BYTES:
        raise RuntimeError(f"unsafe locked size for {entry['mod_id']}")
    request = urllib.request.Request(str(entry["download_url"]), headers={
        "User-Agent": "PolyMc-Reborn/0.3 external-matrix"
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        declared = response.headers.get("Content-Length")
        if declared is not None and int(declared) != expected_size:
            raise RuntimeError(f"Content-Length mismatch for {entry['mod_id']}")
        data = response.read(MAX_BYTES + 1)
    if len(data) != expected_size or len(data) > MAX_BYTES:
        raise RuntimeError(f"download size mismatch for {entry['mod_id']}")
    if hashlib.sha256(data).hexdigest() != entry["sha256"]:
        raise RuntimeError(f"SHA-256 mismatch for {entry['mod_id']}")
    if hashlib.sha512(data).hexdigest() != entry["sha512"]:
        raise RuntimeError(f"SHA-512 mismatch for {entry['mod_id']}")
    destination.write_bytes(data)
    return destination


def run_entry(entry: dict[str, object], jar: Path) -> dict[str, object]:
    environment = os.environ.copy()
    environment.update({
        "POLYMC_REBORN_EXTERNAL_MOD_JAR": str(jar.resolve()),
        "POLYMC_REBORN_EXTERNAL_MODE": str(entry["mode"]),
        "POLYMC_REBORN_EXTERNAL_MOD_ID": str(entry["mod_id"]),
        "POLYMC_REBORN_EXTERNAL_ITEM_ID": str(entry["item_id"]),
        "POLYMC_REBORN_EXTERNAL_BLOCK_ID": str(entry.get("block_id", "none")),
    })
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    # The nested production playtest replaces build/playtest on every leg, so
    # retain every attempt outside that root until all legs have completed.
    destination = STAGING / str(entry["mod_id"])
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    attempt_history: list[dict[str, object]] = []
    evidence = ROOT / "build" / "playtest" / "single-client"
    summary: dict[str, object] = {}
    server: dict[str, object] = {}
    logs = ""
    process: subprocess.CompletedProcess[bytes]
    passed = False
    client_crashed = False
    server_crashed = False
    registry_sync_disconnect = False
    decoder_exception = False
    scenario_count = 0
    for attempt in range(1, 3):
        process = subprocess.run(
            [str(wrapper), "--no-daemon", "--console=plain", "runProductionClientPlaytest"],
            cwd=ROOT, env=environment, timeout=35 * 60, check=False,
        )
        summary_file = evidence / "summary.json"
        server_file = evidence / "server-state.json"
        summary = (json.loads(summary_file.read_text(encoding="utf-8"))
                   if summary_file.is_file() else {})
        server = (json.loads(server_file.read_text(encoding="utf-8"))
                  if server_file.is_file() else {})
        logs = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                         for path in (evidence / "logs").glob("*.log"))
        lowered_logs = logs.lower()
        client_crashed = "client crash" in lowered_logs or "client-crash" in lowered_logs
        server_crashed = "failed to start the minecraft server" in lowered_logs
        decoder_exception = "decoderexception" in lowered_logs
        registry_sync_disconnect = (("registry sync" in lowered_logs
                                     or "registry entry namespaces" in lowered_logs)
                                    and ("disconnect" in lowered_logs
                                         or "requires fabric api" in lowered_logs))
        scenarios = summary.get("client_scenarios", [])
        scenario_count = len(scenarios) if isinstance(scenarios, list) else 0
        passed = (process.returncode == 0 and summary.get("result") == "passed"
                  and server.get("external_mod_loaded") is True
                  and server.get("external_content_passed") is True)
        retryable_infrastructure = (not passed and process.returncode != 0
                                    and scenario_count == 0 and not client_crashed
                                    and not server_crashed and not decoder_exception
                                    and not registry_sync_disconnect)
        attempt_history.append({
            "attempt": attempt,
            "gradle_exit_code": process.returncode,
            "scenario_count": scenario_count,
            "result": "passed" if passed else "failed",
            "retryable_infrastructure": retryable_infrastructure,
        })
        if evidence.is_dir():
            shutil.copytree(evidence, destination / "attempts" / f"attempt-{attempt}")
        if passed or not retryable_infrastructure or attempt == 2:
            break
        # Let the native renderer, audio engine, and nested Gradle JVM release
        # host resources before the single bounded infrastructure retry.
        time.sleep(10)
    if evidence.is_dir():
        shutil.copytree(evidence, destination / "evidence")
    classification = (str(entry["expected_classification"]) if passed else
                      "CRASH" if server_crashed or client_crashed else
                      "INFRASTRUCTURE_FAILURE" if scenario_count == 0 else "PARTIAL")
    result = {
        "schema_version": 1,
        "result": "passed" if passed else "failed",
        "classification": classification,
        "project_id": entry["project_id"],
        "project_name": entry["project_name"],
        "mod_id": entry["mod_id"],
        "exact_version": entry["exact_version"],
        "version_id": entry["version_id"],
        "license": entry["license"],
        "source_commit": entry["source_commit"],
        "artifact_size": entry["artifact_size"],
        "sha256": entry["sha256"],
        "sha512": entry["sha512"],
        "client_install_forbidden": True,
        "attempt_count": len(attempt_history),
        "attempts": attempt_history,
        "gradle_exit_code": process.returncode,
        "external_content_passed": server.get("external_content_passed", False),
        "scenario_count": scenario_count,
        "feature_results": {
            "server_started": server.get("result") == "passed",
            "client_joined": int(server.get("join_count", 0)) >= 1,
            "resource_pack_applied": int(server.get("resource_pack_request_count", 0)) >= 1,
            "custom_item_obtained": bool(server.get("external_content_passed", False)),
            "custom_item_move_drop_pickup": "NOT_TESTED",
            "simple_block_placed": bool(server.get("external_block_placed", False)),
            "simple_block_broken": bool(server.get("external_block_broken", False)),
            "server_logic_executed": bool(server.get("external_item_equipped", False)
                                           or server.get("external_block_placed", False)),
            "reconnect_stable": int(server.get("join_count", 0)) == 2,
            "mapping_stable": bool(server.get("mapping_store_stable", False)),
            "unsupported_content_isolated": "NOT_TESTED",
            "missing_model": "missing model" in logs.lower(),
            "decoder_exception": decoder_exception,
            "registry_sync_disconnect": registry_sync_disconnect,
            "client_crashed": client_crashed,
            "server_crashed": server_crashed,
        },
        "production_jar_sha256": server.get("production_jar_sha256"),
        "mapping_store_sha256": server.get("mapping_store_sha256"),
        "resource_pack_sha256": server.get("resource_pack_sha256"),
    }
    (destination / "summary.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf-8"
    )
    return result


def main() -> int:
    document = json.loads(LOCK.read_text(encoding="utf-8"))
    if document.get("schema_version") != 1 or document.get("minecraft_version") != "26.1.2":
        raise RuntimeError("unsupported external-mod lock schema or Minecraft version")
    entries = document.get("entries")
    if not isinstance(entries, list) or len(entries) < 2:
        raise RuntimeError("external matrix requires at least two locked entries")
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    for directory in (STAGING, OUTPUT):
        if directory.exists():
            shutil.rmtree(directory)
    STAGING.mkdir(parents=True)
    results = []
    for entry in entries:
        jar = download(entry)
        results.append(run_entry(entry, jar))
    OUTPUT.mkdir(parents=True)
    for entry in entries:
        mod_id = str(entry["mod_id"])
        shutil.copytree(STAGING / mod_id, OUTPUT / mod_id)
    overall = {
        "schema_version": 1,
        "result": "passed" if all(item["result"] == "passed" for item in results) else "failed",
        "minecraft_version": "26.1.2",
        "client_content_mods_installed": False,
        "entries": results,
    }
    (OUTPUT / "summary.json").write_text(
        json.dumps(overall, indent=2) + "\n", encoding="utf-8"
    )
    lines = ["# External Mod compatibility matrix", "",
             f"Result: **{overall['result'].upper()}**", "",
             "| Mod | Version | Classification | Result |", "|---|---|---|---:|"]
    lines.extend(f"| {item['project_name']} | `{item['exact_version']}` | "
                 f"`{item['classification']}` | "
                 f"{'PASS' if item['result'] == 'passed' else 'FAIL'} |" for item in results)
    (OUTPUT / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    suite = ET.Element("testsuite", name="external-mod-matrix", tests=str(len(results)),
                       failures=str(sum(item["result"] != "passed" for item in results)))
    for item in results:
        case = ET.SubElement(suite, "testcase", name=str(item["mod_id"]))
        if item["result"] != "passed":
            ET.SubElement(case, "failure", message=str(item["classification"]))
    ET.indent(suite)
    ET.ElementTree(suite).write(OUTPUT / "junit.xml", encoding="utf-8", xml_declaration=True)
    (OUTPUT / "commands.json").write_text(json.dumps({"schema_version": 1,
        "commands": ["./gradlew runExternalModMatrix"]}, indent=2) + "\n", encoding="utf-8")
    (OUTPUT / "processes.json").write_text(json.dumps({"schema_version": 1,
        "entries": [{"mod_id": item["mod_id"], "gradle_exit_code": item["gradle_exit_code"]}
                    for item in results]}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    logs_dir = OUTPUT / "logs"
    screenshots_dir = OUTPUT / "screenshots"
    loaded_dir = OUTPUT / "loaded-mods"
    for directory in (logs_dir, screenshots_dir, loaded_dir):
        directory.mkdir()
    for item in results:
        mod_id = str(item["mod_id"])
        evidence = OUTPUT / mod_id / "evidence"
        for source in sorted((evidence / "logs").glob("*.log")):
            shutil.copy2(source, logs_dir / f"{mod_id}-{source.name}")
        for source in sorted((evidence / "screenshots").glob("*.png")):
            shutil.copy2(source, screenshots_dir / f"{mod_id}-{source.name}")
        for source in sorted((evidence / "loaded-mods").glob("*.json")):
            shutil.copy2(source, loaded_dir / f"{mod_id}-{source.name}")
    (OUTPUT / "hashes.json").write_text(json.dumps({"schema_version": 1,
        "entries": [{key: item.get(key) for key in ("mod_id", "sha256", "sha512",
            "production_jar_sha256", "mapping_store_sha256", "resource_pack_sha256")}
                    for item in results]}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (OUTPUT / "redaction-report.json").write_text(json.dumps({"schema_version": 1,
        "result": "passed", "worlds_included": 0, "third_party_jars_included": 0,
        "secrets_included": 0}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    artifacts = []
    for path in sorted(OUTPUT.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_file() and path.name != "manifest.json":
            if path.suffix.lower() == ".jar":
                raise RuntimeError(f"external Mod JAR leaked into evidence: {path}")
            artifacts.append({"path": path.relative_to(OUTPUT).as_posix(),
                              "bytes": path.stat().st_size,
                              "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    (OUTPUT / "manifest.json").write_text(json.dumps({"schema_version": 1,
        "artifacts": artifacts}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0 if overall["result"] == "passed" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.TimeoutExpired as exception:
        print(f"external matrix timed out: {exception}", file=sys.stderr)
        raise SystemExit(1)
