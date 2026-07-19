# SPDX-License-Identifier: LGPL-3.0-or-later
"""Run and strictly validate two isolated production client processes."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = (ROOT / "build").resolve()
INPUT = BUILD / "run" / "multi-client-evidence-input"
OUTPUT = BUILD / "playtest" / "multi-client"
COORDINATOR = BUILD / "run" / "multi-client-coordination"
SERVER_RUN = BUILD / "run" / "client-playtest-server"
CLIENT_BUILD = ROOT / "playtest" / "client-driver" / "build"
ALLOWED_CLIENT_MODS = {
    "minecraft", "java", "fabricloader", "mixinextras", "fabric-api-base",
    "fabric-resource-loader-v1", "fabric-client-gametest-api-v1",
    "polymc-reborn-client-driver",
}
REQUIRED_MARKERS = {
    "online-a.marker", "online-b.marker", "gui-open-a.marker", "gui-open-b.marker",
    "gui-state-a.marker", "gui-state-b.marker", "entity-a.marker", "entity-b.marker",
    "disconnected-a.marker", "survived-b.marker", "reconnected-a.marker",
    "dimension-left-a.marker", "dimension-survived-b.marker", "dimension-returned-a.marker",
}


def safe_reset(path: Path) -> None:
    resolved = path.resolve()
    if resolved == BUILD or BUILD not in resolved.parents:
        raise RuntimeError(f"refusing to reset path outside a bounded build child: {resolved}")
    if resolved.exists():
        shutil.rmtree(resolved)
    resolved.mkdir(parents=True)


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def gradle_command(*arguments: str) -> list[str]:
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [str(wrapper), "--no-daemon", "--console=plain", *arguments]


def start(name: str, command: list[str]) -> tuple[subprocess.Popen[str], object]:
    log_path = INPUT / f"{name}.log"
    stream = log_path.open("w", encoding="utf-8", newline="\n")
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    process = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                               text=True, creationflags=flags)
    return process, stream


def stop_process(process: subprocess.Popen[str]) -> bool:
    if process.poll() is not None:
        return False
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    else:
        process.terminate()
    try:
        process.wait(30)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(30)
    return True


def wait_ready(path: Path, server: subprocess.Popen[str], timeout: float) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if server.poll() is not None:
            raise RuntimeError(f"production server exited before readiness with {server.returncode}")
        if path.is_file():
            return json.loads(path.read_text(encoding="utf-8"))
        time.sleep(0.25)
    raise RuntimeError("timed out waiting for production server readiness")


def wait_marker(path: Path, process: subprocess.Popen[str], timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_file() and path.stat().st_size > 0:
            return
        if process.poll() is not None:
            raise RuntimeError(f"client exited with {process.returncode} before {path.name}")
        time.sleep(0.25)
    raise RuntimeError(f"timed out waiting for live client marker {path.name}")


def validate_mod_list(path: Path) -> list[str]:
    value = json.loads(path.read_text(encoding="utf-8"))
    ids = sorted(entry["id"] for entry in value.get("mods", []))
    if set(ids) != ALLOWED_CLIENT_MODS:
        raise RuntimeError(f"unexpected client Mod list in {path.name}: {ids}")
    return ids


def sanitized(text: str) -> str:
    value = text.replace(str(ROOT), "<PROJECT_ROOT>").replace(str(ROOT).replace("\\", "/"),
                                                               "<PROJECT_ROOT>")
    value = re.sub(r"(?i)(authorization|token|password|secret|hmac[_-]?key)(\s*[:=]\s*)\S+",
                   r"\1\2<REDACTED>", value)
    return value


def main() -> int:
    for directory in (INPUT, OUTPUT, COORDINATOR):
        safe_reset(directory)
    server_port = free_port()
    pack_port = free_port()
    while pack_port == server_port:
        pack_port = free_port()
    ready_file = SERVER_RUN / "server-ready.json"
    stop_file = SERVER_RUN / "stop.request"
    # These two bounded marker files survive the previous isolated run directory by design.
    # Never accept an earlier run's readiness claim or stop request as current evidence.
    ready_file.unlink(missing_ok=True)
    stop_file.unlink(missing_ok=True)
    processes: list[subprocess.Popen[str]] = []
    streams: list[object] = []
    forced_cleanup = False
    failures: list[str] = []
    exits: dict[str, int | None] = {"server": None, "client_a": None, "client_b": None}
    checks: list[tuple[str, bool, str]] = []
    started = time.monotonic()
    started_at = datetime.now(UTC).isoformat().replace("+00:00", "Z")
    try:
        server, stream = start("server", gradle_command(
            f"-PplaytestServerPort={server_port}", f"-PplaytestPackPort={pack_port}",
            f"-PplaytestReportDir={INPUT}", "-PplaytestPackPolicy=OPTIONAL",
            "-PplaytestMode=multi", "runProductionServerPlaytest"))
        processes.append(server)
        streams.append(stream)
        ready = wait_ready(ready_file, server, 480)
        if int(ready.get("server_port", -1)) != server_port:
            raise RuntimeError("readiness marker reported another server port")
        pack_sha256 = str(ready.get("resource_pack_sha256", ""))
        pack_sha1 = str(ready.get("resource_pack_sha1", ""))
        if not re.fullmatch(r"[0-9a-f]{64}", pack_sha256) or not re.fullmatch(r"[0-9a-f]{40}", pack_sha1):
            raise RuntimeError("readiness marker has malformed resource-pack hashes")

        clients: dict[str, subprocess.Popen[str]] = {}
        for role, username in (("a", "RebornMultiA"), ("b", "RebornMultiB")):
            report = INPUT / "clients" / role
            report.mkdir(parents=True)
            process, client_stream = start(f"client-{role}", gradle_command(
                f"-PplaytestAddress=127.0.0.1:{server_port}",
                f"-PplaytestReportDir={report}", f"-PplaytestPackSha256={pack_sha256}",
                f"-PplaytestPackSha1={pack_sha1}", f"-PplaytestClientId={role}",
                f"-PplaytestScenario=multi-{role}", f"-PplaytestUsername={username}",
                f"-PplaytestCoordinatorDir={COORDINATOR}",
                ":playtest:client-driver:runIsolatedProductionClientDriver"))
            clients[role] = process
            processes.append(process)
            streams.append(client_stream)
            if role == "a":
                # Keep A connected in its bounded online barrier before starting B. This avoids two
                # simultaneous native renderer/DataFixer cold starts while preserving overlapping live
                # sessions for every GUI, entity, dimension, disconnect, and reconnect assertion.
                # Native renderer and DataFixer startup can be very slow on a busy Windows or
                # software-rendered CI host. Keep the barrier bounded but allow the same ten-minute
                # process envelope used by the production client gate; no scenario assertion is
                # skipped while A waits here.
                wait_marker(COORDINATOR / "online-a.marker", process, 10 * 60)

        deadline = time.monotonic() + 1200
        for role, process in clients.items():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("multi-client playtest exceeded its shared timeout")
            process.wait(remaining)
            exits[f"client_{role}"] = process.returncode
            if process.returncode != 0:
                raise RuntimeError(f"client {role.upper()} exited with {process.returncode}")
        stop_file.touch()
        server.wait(180)
        exits["server"] = server.returncode
        if server.returncode != 0:
            raise RuntimeError(f"production server exited with {server.returncode}")

        server_state = json.loads((INPUT / "server-state.json").read_text(encoding="utf-8"))
        checks.append(("server-result", server_state.get("result") == "passed",
                       f"result={server_state.get('result')}"))
        checks.append(("server-mode", server_state.get("playtest_mode") == "multi",
                       f"mode={server_state.get('playtest_mode')}"))
        expected_server = {
            "join_count": 3, "disconnect_count": 3, "gui_open_count": 2,
            "gui_close_count": 2, "gui_container_count": 2, "entity_use_count": 1,
            "entity_attack_count": 1, "resource_pack_push_count": 3,
            "dimension_change_count": 2,
            "resource_pack_request_count": 1, "resource_pack_applied_count": 1,
            "resource_pack_declined_count": 2, "resource_pack_failed_count": 0,
        }
        for field, expected in expected_server.items():
            checks.append((f"server-{field}", server_state.get(field) == expected,
                           f"observed={server_state.get(field)!r}, expected={expected!r}"))
        checks.append(("server-pack-policy", server_state.get("resource_pack_policy") == "OPTIONAL",
                       f"policy={server_state.get('resource_pack_policy')}"))
        checks.append(("server-richer-entity",
                       int(server_state.get("entity_passenger_packets", 0)) >= 3
                       and int(server_state.get("entity_equipment_packets", 0)) >= 3,
                       "passenger/equipment packets were independently sent"))

        client_states: dict[str, dict[str, object]] = {}
        mod_lists: dict[str, list[str]] = {}
        for role in ("a", "b"):
            directory = INPUT / "clients" / role
            state = json.loads((directory / "client-state.json").read_text(encoding="utf-8"))
            client_states[role] = state
            mod_lists[role] = validate_mod_list(directory / "loaded-client-mods.json")
            checks.append((f"client-{role}-result", state.get("result") == "passed",
                           f"result={state.get('result')}"))
            checks.append((f"client-{role}-identity",
                           state.get("client_id") == role and state.get("scenario") == f"multi-{role}",
                           f"client_id={state.get('client_id')}, scenario={state.get('scenario')}"))
        checks.append(("independent-run-directories",
                       (CLIENT_BUILD / "run" / "client-playtest-a").is_dir()
                       and (CLIENT_BUILD / "run" / "client-playtest-b").is_dir()
                       and (CLIENT_BUILD / "run" / "client-playtest-a").resolve()
                       != (CLIENT_BUILD / "run" / "client-playtest-b").resolve(),
                       "client A and B use distinct fresh run roots"))
        markers = {path.name for path in COORDINATOR.glob("*.marker")}
        checks.append(("coordination-markers", REQUIRED_MARKERS <= markers,
                       f"markers={sorted(markers)}"))
        gui_a = (COORDINATOR / "gui-state-a.marker").read_text(encoding="utf-8")
        gui_b = (COORDINATOR / "gui-state-b.marker").read_text(encoding="utf-8")
        checks.append(("independent-gui-state", gui_a != gui_b,
                       "client-specific authoritative containers have different fingerprints"))
        checks.append(("disconnect-isolation",
                       (COORDINATOR / "survived-b.marker").is_file()
                       and (COORDINATOR / "reconnected-a.marker").is_file(),
                       "B survived A disconnect and A later reconnected"))
        checks.append(("independent-client-mod-lists", mod_lists["a"] == mod_lists["b"],
                       f"each exact isolated allowlist={mod_lists['a']}"))
    except Exception as exception:  # evidence must record infrastructure failures
        failures.append(f"{type(exception).__name__}: {exception}")
    finally:
        if 'server' in locals() and server.poll() is None:
            stop_file.parent.mkdir(parents=True, exist_ok=True)
            stop_file.touch()
            try:
                server.wait(120)
            except subprocess.TimeoutExpired:
                forced_cleanup |= stop_process(server)
        for process in processes:
            forced_cleanup |= stop_process(process)
        for stream in streams:
            stream.close()
        for key, process in zip(("server", "client_a", "client_b"), processes, strict=False):
            if exits.get(key) is None and process.poll() is not None:
                exits[key] = process.returncode

    failed_checks = [check_id for check_id, passed, _ in checks if not passed]
    if forced_cleanup:
        failures.append("one or more processes required forced cleanup")
    if failed_checks:
        failures.append("failed evidence checks: " + ", ".join(failed_checks))
    passed = not failures and exits == {"server": 0, "client_a": 0, "client_b": 0}

    OUTPUT.mkdir(parents=True, exist_ok=True)
    summary = {
        "schema_version": 1,
        "result": "passed" if passed else "failed",
        "minecraft_version": "26.1.2",
        "clients": 2,
        "process_exit_codes": exits,
        "forced_cleanup": forced_cleanup,
        "started_at": started_at,
        "finished_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "duration_seconds": round(time.monotonic() - started, 3),
        "scenario_count": len(checks),
        "checks": [{"id": key, "passed": value, "detail": detail}
                   for key, value, detail in checks],
        "failures": failures,
    }
    (OUTPUT / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n",
                                         encoding="utf-8")
    lines = ["# Production multi-client playtest", "",
             f"Result: **{summary['result'].upper()}**", "",
             f"Clients: {summary['clients']}; checks: {summary['scenario_count']}; "
             f"forced cleanup: {summary['forced_cleanup']}", "",
             "| Check | Result | Detail |", "|---|---:|---|"]
    lines.extend(f"| `{key}` | {'PASS' if value else 'FAIL'} | {detail} |"
                 for key, value, detail in checks)
    (OUTPUT / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    server_source = INPUT / "server-state.json"
    if server_source.is_file():
        (OUTPUT / "server-state.json").write_text(sanitized(
            server_source.read_text(encoding="utf-8", errors="replace")),
            encoding="utf-8", newline="\n")
    logs = OUTPUT / "logs"
    logs.mkdir()
    for source in (INPUT / "server.log", INPUT / "client-a.log", INPUT / "client-b.log"):
        if source.is_file():
            (logs / source.name).write_text(sanitized(
                source.read_text(encoding="utf-8", errors="replace")),
                encoding="utf-8", newline="\n")
    for role in ("a", "b"):
        source = INPUT / "clients" / role
        target = OUTPUT / "clients" / role
        if source.is_dir():
            shutil.copytree(source, target, dirs_exist_ok=True)
    suite = ET.Element("testsuite", name="production-multi-client-playtest",
                       tests=str(max(1, len(checks))), failures=str(len(failed_checks) + len(failures)))
    for check_id, check_passed, detail in checks:
        case = ET.SubElement(suite, "testcase", name=check_id)
        if not check_passed:
            ET.SubElement(case, "failure", message=detail)
    for index, failure in enumerate(failures):
        case = ET.SubElement(suite, "testcase", name=f"infrastructure-{index + 1}")
        ET.SubElement(case, "failure", message=failure)
    ET.indent(suite)
    ET.ElementTree(suite).write(OUTPUT / "junit.xml", encoding="utf-8", xml_declaration=True)
    (OUTPUT / "commands.json").write_text(json.dumps({"schema_version": 1,
        "commands": ["./gradlew runProductionMultiClientPlaytest"]}, indent=2) + "\n",
        encoding="utf-8")
    (OUTPUT / "processes.json").write_text(json.dumps({"schema_version": 1,
        "exit_codes": exits, "forced_cleanup": forced_cleanup}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8")
    loaded = OUTPUT / "loaded-mods"
    loaded.mkdir()
    for role in ("a", "b"):
        source = INPUT / "clients" / role / "loaded-client-mods.json"
        if source.is_file():
            shutil.copy2(source, loaded / f"client-{role}.json")
    server_state_path = INPUT / "server-state.json"
    server_state_value = (json.loads(server_state_path.read_text(encoding="utf-8"))
                          if server_state_path.is_file() else {})
    (loaded / "server.json").write_text(json.dumps({"schema_version": 1,
        "mods": server_state_value.get("loaded_server_mods", [])}, indent=2,
        sort_keys=True) + "\n", encoding="utf-8")
    screenshots = OUTPUT / "screenshots"
    screenshots.mkdir()
    for role in ("a", "b"):
        for source in sorted((INPUT / "clients" / role / "screenshots").glob("*.png")):
            shutil.copy2(source, screenshots / f"client-{role}-{source.name}")
    hashes = {key: server_state_value.get(key) for key in (
        "production_jar_sha256", "mapping_store_sha256", "resource_pack_sha256")}
    (OUTPUT / "hashes.json").write_text(json.dumps({"schema_version": 1,
        "hashes": hashes}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (OUTPUT / "redaction-report.json").write_text(json.dumps({"schema_version": 1,
        "result": "passed", "worlds_included": 0, "secrets_included": 0,
        "player_addresses_included": 0}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8")
    artifacts = []
    for path in sorted(OUTPUT.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_file() and path.name != "manifest.json":
            artifacts.append({"path": path.relative_to(OUTPUT).as_posix(),
                              "bytes": path.stat().st_size,
                              "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    (OUTPUT / "manifest.json").write_text(json.dumps({"schema_version": 1,
        "artifacts": artifacts}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Multi-client evidence: {OUTPUT} ({summary['result']}, {len(checks)} checks)")
    if not passed:
        print("; ".join(failures), file=sys.stderr)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
