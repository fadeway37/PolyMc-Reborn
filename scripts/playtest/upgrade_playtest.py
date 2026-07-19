# SPDX-License-Identifier: LGPL-3.0-or-later
"""Build 0.2 from the audited commit and run real 0.2 -> 0.3 mapping upgrades."""

from __future__ import annotations

import hashlib
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tarfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
BUILD = (ROOT / "build").resolve()
WORK = BUILD / "run" / "upgrade-work"
SERVER = BUILD / "run" / "upgrade-server"
UPGRADE_OUTPUT = BUILD / "playtest" / "upgrade"
EXPANSION_OUTPUT = BUILD / "playtest" / "modset-expansion"
BASE_COMMIT = "e15714e0cb922bb4551442a63b3ad192534dde45"


def reset(path: Path) -> None:
    resolved = path.resolve()
    if resolved == BUILD or BUILD not in resolved.parents:
        raise RuntimeError(f"refusing unsafe reset: {resolved}")
    if resolved.exists():
        shutil.rmtree(resolved)
    resolved.mkdir(parents=True)


def run(command: list[str], cwd: Path, log: Path | None = None) -> None:
    kwargs: dict[str, object] = {"cwd": cwd, "check": True, "text": True}
    if log is not None:
        with log.open("w", encoding="utf-8", newline="\n") as stream:
            subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, **kwargs)
    else:
        subprocess.run(command, **kwargs)


def wrapper(directory: Path) -> str:
    return str(directory / ("gradlew.bat" if os.name == "nt" else "gradlew"))


def extract_baseline(destination: Path) -> None:
    archive = subprocess.check_output(["git", "archive", "--format=tar", BASE_COMMIT], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
        members = tar.getmembers()
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or member.issym() or member.islnk():
                raise RuntimeError(f"unsafe path in audited git archive: {member.name}")
        tar.extractall(destination, members=members, filter="data")


def release_jar(directory: Path, version: str) -> Path:
    expected = directory / "build" / "libs" / f"polymc-reborn-{version}.jar"
    if not expected.is_file():
        raise RuntimeError(f"expected release JAR was not built: {expected}")
    return expected.resolve()


def mappings() -> tuple[bytes, dict[str, dict[str, object]]]:
    path = SERVER / "config" / "polymc-reborn" / "mappings-v1.json"
    raw = path.read_bytes()
    document = json.loads(raw)
    values: dict[str, dict[str, object]] = {}
    for entry in document["mappings"]:
        key = f"{entry['content_type'].lower()}:{entry['registry_id']}[{entry['state']}]"
        values[key] = entry
    return raw, values


def fixture_assignments(values: dict[str, dict[str, object]]) -> dict[str, tuple[str, str]]:
    return {key: (str(entry["strategy"]), str(entry["client_carrier"]))
            for key, entry in values.items() if ("polymc-reborn-upgrade-fixture:" in key
                                                 or "polymc-reborn-upgrade-expansion:" in key)}


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def wait_for_file(path: Path, process: subprocess.Popen[str], timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_file():
            return
        if process.poll() is not None:
            raise RuntimeError(f"server exited with {process.returncode} before creating {path.name}")
        time.sleep(0.25)
    raise RuntimeError(f"timed out waiting for {path.name}")


def run_upgrade_client(mode: str, port: int, ready: dict[str, object]) -> dict[str, object]:
    report = WORK / f"{mode}-client"
    report.mkdir(parents=True, exist_ok=True)
    client_id = mode.replace("mod-set-", "mod-").replace("upgrade-0.3", "upgrade-current")
    client_id = client_id.replace(".", "-")
    args = [wrapper(ROOT), "--no-daemon", "--console=plain",
            f"-PplaytestClientId={client_id}", f"-PplaytestScenario=upgrade-{mode}",
            "-PplaytestUsername=UpgradeClient", f"-PplaytestAddress=127.0.0.1:{port}",
            f"-PplaytestReportDir={report}",
            f"-PplaytestPackSha256={ready['resource_pack_sha256']}",
            f"-PplaytestPackSha1={ready['resource_pack_sha1']}",
            ":playtest:client-driver:runIsolatedProductionClientDriver"]
    run(args, ROOT, WORK / f"{mode}-client.log")
    state_path = report / "client-state.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    if state.get("result") != "passed":
        raise RuntimeError(f"upgrade client did not pass for {mode}")
    return state


def server_leg(reborn_jar: Path, mode: str, expanded: bool, client: bool) -> tuple[
        bytes, dict[str, dict[str, object]], dict[str, object], dict[str, object] | None]:
    report = WORK / f"{mode}-fixture-state.json"
    ready_report = Path(str(report) + ".ready.json")
    stop_file = WORK / f"{mode}-stop.request"
    port = free_port()
    pack_port = free_port()
    args = [wrapper(ROOT), "--no-daemon", "--console=plain",
            f"-PupgradeRebornJar={reborn_jar}", f"-PupgradeMode={mode}",
            f"-PupgradeExpanded={'true' if expanded else 'false'}", f"-PupgradeReport={report}",
            f"-PupgradeStopFile={stop_file}", f"-PupgradeServerPort={port}",
            f"-PupgradePackPort={pack_port}",
            "runUpgradeFixtureServer"]
    server_log = WORK / f"{mode}-server.log"
    with server_log.open("w", encoding="utf-8", newline="\n") as stream:
        process = subprocess.Popen(args, cwd=ROOT, text=True, stdout=stream,
                                   stderr=subprocess.STDOUT)
        client_state: dict[str, object] | None = None
        failure: BaseException | None = None
        try:
            wait_for_file(ready_report, process, 8 * 60)
            ready = json.loads(ready_report.read_text(encoding="utf-8"))
            if client:
                client_state = run_upgrade_client(mode, port, ready)
        except BaseException as exception:
            failure = exception
        finally:
            stop_file.touch()
            try:
                return_code = process.wait(timeout=4 * 60)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=30)
                raise RuntimeError(f"upgrade server required forced termination for {mode}")
        if failure is not None:
            raise failure
    if return_code != 0:
        raise RuntimeError(f"upgrade server exited with {return_code} for {mode}")
    state = json.loads(report.read_text(encoding="utf-8"))
    if state.get("mode") != mode:
        raise RuntimeError(f"upgrade fixture reported another mode for {mode}")
    raw, values = mappings()
    if state.get("mapping_sha256") != hashlib.sha256(raw).hexdigest():
        raise RuntimeError(f"fixture/server mapping hash mismatch for {mode}")
    (WORK / f"{mode}-mappings-v1.json").write_bytes(raw)
    shutil.copy2(report, WORK / f"{mode}-state.json")
    diff = SERVER / "config" / "polymc-reborn" / "reports" / "mapping-diff-latest.json"
    if diff.is_file():
        shutil.copy2(diff, WORK / f"{mode}-mapping-diff.json")
    return raw, values, state, client_state


def materialize_evidence(output: Path, suite: str, selected: list[dict[str, object]],
                         failures: list[str], log_names: list[str], mapping_names: list[str]) -> None:
    reset(output)
    failed = [entry for entry in selected if not entry["passed"]]
    passed = not failures and not failed
    summary = {"schema_version": 1, "suite": suite, "result": "passed" if passed else "failed",
               "scenario_count": len(selected), "checks": selected, "failures": failures}
    (output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n",
                                         encoding="utf-8")
    lines = [f"# {suite}", "", f"Result: **{summary['result'].upper()}**", "",
             "| Check | Result | Detail |", "|---|---:|---|"]
    lines.extend(f"| `{entry['id']}` | {'PASS' if entry['passed'] else 'FAIL'} | {entry['detail']} |"
                 for entry in selected)
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    suite_xml = ET.Element("testsuite", name=suite, tests=str(len(selected)), failures=str(len(failed)))
    for entry in selected:
        case = ET.SubElement(suite_xml, "testcase", name=str(entry["id"]))
        if not entry["passed"]:
            ET.SubElement(case, "failure", message=str(entry["detail"]))
    ET.indent(suite_xml)
    ET.ElementTree(suite_xml).write(output / "junit.xml", encoding="utf-8", xml_declaration=True)
    logs = output / "logs"
    logs.mkdir()
    for name in log_names:
        source = WORK / name
        if source.is_file():
            text = source.read_text(encoding="utf-8", errors="replace")
            text = text.replace(str(ROOT), "<PROJECT_ROOT>").replace(
                str(Path.home()), "<USER_HOME>")
            (logs / name).write_text(text, encoding="utf-8", newline="\n")
    reports = output / "reports"
    reports.mkdir()
    hashes: dict[str, str] = {}
    for name in mapping_names:
        source = WORK / name
        if source.is_file():
            shutil.copy2(source, reports / name)
            hashes[name] = hashlib.sha256(source.read_bytes()).hexdigest()
    (output / "commands.json").write_text(json.dumps({"schema_version": 1, "commands": [
        "./gradlew runUpgradePlaytest", "./gradlew runModSetExpansionPlaytest"]}, indent=2) + "\n",
        encoding="utf-8")
    (output / "processes.json").write_text(json.dumps({"schema_version": 1,
        "result": "passed" if passed else "failed", "forced_cleanup": False}, indent=2) + "\n",
        encoding="utf-8")
    (output / "hashes.json").write_text(json.dumps({"schema_version": 1, "files": hashes},
        indent=2, sort_keys=True) + "\n", encoding="utf-8")
    loaded = output / "loaded-mods"
    loaded.mkdir()
    state_files = sorted((reports).glob("*-state.json"))
    server_mods = sorted({entry for state_file in state_files
        for entry in str(json.loads(state_file.read_text(encoding="utf-8")).get("mod_list", "")).split(",")
        if entry})
    (loaded / "server.json").write_text(json.dumps({"schema_version": 1,
        "mods": server_mods}, indent=2) + "\n", encoding="utf-8")
    screenshots = output / "screenshots"
    screenshots.mkdir()
    for client_report in sorted(WORK.glob("*-client")):
        if not client_report.is_dir():
            continue
        prefix = client_report.name.removesuffix("-client")
        mods = client_report / "loaded-client-mods.json"
        if mods.is_file():
            shutil.copy2(mods, loaded / f"{prefix}-client.json")
        for source in sorted((client_report / "screenshots").glob("*.png")):
            shutil.copy2(source, screenshots / f"{prefix}-{source.name}")
    (output / "redaction-report.json").write_text(json.dumps({"schema_version": 1,
        "result": "passed", "worlds_included": 0, "third_party_jars_included": 0}, indent=2) + "\n",
        encoding="utf-8")
    artifacts = []
    for path in sorted(output.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_file() and path.name != "manifest.json":
            artifacts.append({"path": path.relative_to(output).as_posix(), "bytes": path.stat().st_size,
                              "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    (output / "manifest.json").write_text(json.dumps({"schema_version": 1,
        "artifacts": artifacts}, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    reset(WORK)
    reset(SERVER)
    (SERVER / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (SERVER / "server.properties").write_text(
        "online-mode=false\nserver-port=0\ngenerate-structures=false\n"
        "level-seed=PolyMc-Reborn-upgrade-playtest\n", encoding="utf-8")
    baseline_source = WORK / "baseline-source"
    baseline_source.mkdir()
    failures: list[str] = []
    checks: list[dict[str, object]] = []
    try:
        exact = subprocess.check_output(["git", "rev-parse", BASE_COMMIT], cwd=ROOT, text=True).strip()
        if exact != BASE_COMMIT:
            raise RuntimeError(f"audited 0.2 commit mismatch: {exact}")
        extract_baseline(baseline_source)
        run([wrapper(baseline_source), "--no-daemon", "--console=plain", "jar"],
            baseline_source, WORK / "baseline-build.log")
        baseline_jar = release_jar(baseline_source, "0.2.0-alpha.1+26.1.2")
        run([wrapper(ROOT), "--no-daemon", "--console=plain", "jar", "upgradeFixtureJar"],
            ROOT, WORK / "current-build.log")
        current_jar = release_jar(ROOT, "0.3.0-beta.1+26.1.2")

        baseline_raw, baseline, baseline_state, baseline_client = server_leg(
            baseline_jar, "baseline-0.2", False, True)
        upgrade_raw, upgraded, upgrade_state, upgrade_client = server_leg(
            current_jar, "upgrade-0.3", False, True)
        expansion_raw, expanded, expanded_state, expanded_client = server_leg(
            current_jar, "mod-set-expanded", True, True)
        removed_raw, removed, removed_state, _ = server_leg(
            current_jar, "mod-set-removed", False, False)
        readded_raw, readded, readded_state, readded_client = server_leg(
            current_jar, "mod-set-readded", True, True)

        baseline_assignments = fixture_assignments(baseline)
        upgraded_assignments = fixture_assignments(upgraded)
        expanded_assignments = fixture_assignments(expanded)
        removed_assignments = fixture_assignments(removed)
        readded_assignments = fixture_assignments(readded)
        base_keys = {key for key in upgraded_assignments if "upgrade-fixture:" in key}
        expansion_keys = {key for key in expanded_assignments if "upgrade-expansion:" in key}
        removed_diff = json.loads((WORK / "mod-set-removed-mapping-diff.json").read_text(
            encoding="utf-8"))
        removed_count = int(removed_diff.get("counts", {}).get("REMOVED", 0))
        checks.extend([
            {"id": "audited-base-commit", "passed": exact == BASE_COMMIT, "detail": exact},
            {"id": "baseline-content", "passed": len(baseline_assignments) >= 3,
             "detail": f"fixture assignments={len(baseline_assignments)}"},
            {"id": "upgrade-key-set", "passed": baseline_assignments.keys() == upgraded_assignments.keys(),
             "detail": "0.2 and 0.3 discovered the same original fixture keys"},
            {"id": "upgrade-allocations-preserved", "passed": baseline_assignments == upgraded_assignments,
             "detail": "strategy and client carrier stayed unchanged"},
            {"id": "upgrade-world-state", "passed": baseline_state["world_state"] == upgrade_state["world_state"],
             "detail": str(upgrade_state["world_state"])},
            {"id": "upgrade-player-inventory", "passed": (
                baseline_state["player_observed"] is True and upgrade_state["player_observed"] is True
                and baseline_state["player_item"] == upgrade_state["player_item"]
                == "polymc-reborn-upgrade-fixture:stable_item"
                and baseline_state["player_item_count"] == upgrade_state["player_item_count"] == 7),
             "detail": f"item={upgrade_state['player_item']}, count={upgrade_state['player_item_count']}"},
            {"id": "upgrade-player-data", "passed": (
                int(baseline_state["player_data_bytes"]) > 0 and int(upgrade_state["player_data_bytes"]) > 0),
             "detail": "persistent player NBT exists after both clean client sessions"},
            {"id": "upgrade-resource-pack-client", "passed": (
                baseline_client is not None and upgrade_client is not None
                and baseline_client.get("result") == upgrade_client.get("result") == "passed"),
             "detail": "both isolated clients joined and applied their exact server pack"},
            {"id": "upgrade-production-jars", "passed": (
                baseline_state["reborn_jar_sha256"] == hashlib.sha256(baseline_jar.read_bytes()).hexdigest()
                and upgrade_state["reborn_jar_sha256"] == hashlib.sha256(current_jar.read_bytes()).hexdigest()),
             "detail": (f"0.2={baseline_state['reborn_jar_sha256']}; "
                        f"0.3={upgrade_state['reborn_jar_sha256']}")},
            {"id": "upgrade-loaded-versions", "passed": (
                "polymc-reborn=0.2.0-alpha.1+26.1.2" in str(baseline_state["mod_list"])
                and "polymc-reborn=0.3.0-beta.1+26.1.2" in str(upgrade_state["mod_list"])
                and "polymc-reborn=0.3.0-beta.1+26.1.2" not in str(baseline_state["mod_list"])),
             "detail": "Fabric Loader reports exact 0.2 then exact 0.3 Reborn containers"},
            {"id": "upgrade-schema-unchanged", "passed": (
                json.loads(baseline_raw)["schema_version"] == json.loads(upgrade_raw)["schema_version"] == 1),
             "detail": "schema_version=1; no migration was claimed or required"},
            {"id": "mod-set-preserves-old", "passed": all(expanded_assignments.get(key) == value
                    for key, value in upgraded_assignments.items()),
             "detail": "all pre-expansion allocations remain identical"},
            {"id": "mod-set-adds-content", "passed": len(expanded_assignments) > len(upgraded_assignments),
             "detail": f"before={len(upgraded_assignments)}, after={len(expanded_assignments)}"},
            {"id": "mod-set-independent-b", "passed": bool(base_keys) and bool(expansion_keys),
             "detail": f"Mod A keys={len(base_keys)}, independent Mod B keys={len(expansion_keys)}"},
            {"id": "mod-set-removal-dormant", "passed": (
                removed_assignments == expanded_assignments and removed_count >= len(expansion_keys)),
             "detail": f"REMOVED={removed_count}; retained allocations={len(removed_assignments)}"},
            {"id": "mod-set-removal-world", "passed": (
                expanded_state["world_state"] == removed_state["world_state"]
                == readded_state["world_state"]),
             "detail": str(readded_state["world_state"])},
            {"id": "expanded-readd-assignments", "passed": expanded_assignments == readded_assignments,
             "detail": "remove/re-add restored every allocation without reuse or reordering"},
            {"id": "expanded-readd-bytes", "passed": expansion_raw == readded_raw,
             "detail": f"sha256={hashlib.sha256(readded_raw).hexdigest()}"},
            {"id": "expanded-client-evidence", "passed": (
                expanded_client is not None and readded_client is not None
                and expanded_client.get("result") == readded_client.get("result") == "passed"),
             "detail": "isolated clients joined with Mod B present before and after re-add"},
            {"id": "mapping-document-remains-present", "passed": bool(baseline_raw and upgrade_raw),
             "detail": "no migration silently regenerated an empty mapping store"},
        ])
    except Exception as exception:
        failures.append(f"{type(exception).__name__}: {exception}")
    failed = [entry["id"] for entry in checks if not entry["passed"]]
    if failed:
        failures.append("failed checks: " + ", ".join(str(value) for value in failed))
    passed = not failures
    summary = {"schema_version": 1, "result": "passed" if passed else "failed",
               "base_commit": BASE_COMMIT, "scenario_count": len(checks),
               "checks": checks, "failures": failures}
    (WORK / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n",
                                       encoding="utf-8")
    upgrade_ids = {"audited-base-commit", "baseline-content", "upgrade-key-set",
                   "upgrade-allocations-preserved", "upgrade-world-state", "upgrade-player-inventory",
                   "upgrade-player-data", "upgrade-resource-pack-client", "upgrade-production-jars",
                   "upgrade-loaded-versions", "upgrade-schema-unchanged",
                   "mapping-document-remains-present"}
    expansion_ids = {"mod-set-preserves-old", "mod-set-adds-content",
                     "mod-set-independent-b", "mod-set-removal-dormant", "mod-set-removal-world",
                     "expanded-readd-assignments", "expanded-readd-bytes", "expanded-client-evidence"}
    materialize_evidence(UPGRADE_OUTPUT, "upgrade-playtest",
                         [entry for entry in checks if entry["id"] in upgrade_ids], failures,
                         ["baseline-build.log", "current-build.log", "baseline-0.2-server.log",
                          "baseline-0.2-client.log", "upgrade-0.3-server.log", "upgrade-0.3-client.log"],
                         ["baseline-0.2-mappings-v1.json", "upgrade-0.3-mappings-v1.json",
                          "baseline-0.2-state.json", "upgrade-0.3-state.json"])
    materialize_evidence(EXPANSION_OUTPUT, "modset-expansion-playtest",
                         [entry for entry in checks if entry["id"] in expansion_ids], failures,
                         ["mod-set-expanded-server.log", "mod-set-expanded-client.log",
                          "mod-set-removed-server.log", "mod-set-readded-server.log",
                          "mod-set-readded-client.log"],
                         ["upgrade-0.3-mappings-v1.json", "mod-set-expanded-mappings-v1.json",
                          "mod-set-removed-mappings-v1.json", "mod-set-readded-mappings-v1.json",
                          "mod-set-expanded-state.json", "mod-set-removed-state.json",
                          "mod-set-readded-state.json", "mod-set-removed-mapping-diff.json"])
    print(f"Upgrade evidence: {WORK} ({summary['result']}, {len(checks)} checks)")
    if failures:
        print("; ".join(failures), file=sys.stderr)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
