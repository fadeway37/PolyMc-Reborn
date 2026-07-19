# SPDX-License-Identifier: LGPL-3.0-or-later
"""Exercise REQUIRED-decline and DISABLED policy paths with real clients."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = (ROOT / "build").resolve()
OUTPUT = BUILD / "playtest" / "pack-policy"
SERVER_RUN = BUILD / "run" / "client-playtest-server"


def reset(path: Path) -> None:
    resolved = path.resolve()
    if resolved == BUILD or BUILD not in resolved.parents:
        raise RuntimeError(f"refusing unsafe reset: {resolved}")
    if resolved.exists():
        shutil.rmtree(resolved)
    resolved.mkdir(parents=True)


def port() -> int:
    with socket.socket() as value:
        value.bind(("127.0.0.1", 0))
        return int(value.getsockname()[1])


def command(*args: str) -> list[str]:
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [str(wrapper), "--no-daemon", "--console=plain", *args]


def start(args: list[str], log: Path) -> tuple[subprocess.Popen[str], object]:
    stream = log.open("w", encoding="utf-8", newline="\n")
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    process = subprocess.Popen(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                               text=True, creationflags=flags)
    return process, stream


def terminate(process: subprocess.Popen[str]) -> bool:
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


def wait_ready(path: Path, process: subprocess.Popen[str]) -> dict[str, object]:
    deadline = time.monotonic() + 480
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited before readiness with {process.returncode}")
        if path.is_file():
            return json.loads(path.read_text(encoding="utf-8"))
        time.sleep(0.25)
    raise RuntimeError("server readiness timed out")


def run_case(case: str, policy: str) -> dict[str, object]:
    input_dir = BUILD / "run" / f"{case}-evidence-input"
    reset(input_dir)
    server_port, pack_port = port(), port()
    while pack_port == server_port:
        pack_port = port()
    ready_file = SERVER_RUN / "server-ready.json"
    stop_file = SERVER_RUN / "stop.request"
    ready_file.unlink(missing_ok=True)
    stop_file.unlink(missing_ok=True)
    server = client = None
    streams: list[object] = []
    failures: list[str] = []
    forced = False
    exits = {"server": None, "client": None}
    try:
        server, server_stream = start(command(
            f"-PplaytestServerPort={server_port}", f"-PplaytestPackPort={pack_port}",
            f"-PplaytestReportDir={input_dir}", f"-PplaytestPackPolicy={policy}",
            f"-PplaytestMode={case}", "runProductionServerPlaytest"), input_dir / "server.log")
        streams.append(server_stream)
        ready = wait_ready(ready_file, server)
        if int(ready.get("server_port", -1)) != server_port:
            raise RuntimeError("readiness marker reported another server port")
        report = input_dir / "client"
        report.mkdir()
        client, client_stream = start(command(
            f"-PplaytestAddress=127.0.0.1:{server_port}", f"-PplaytestReportDir={report}",
            f"-PplaytestPackSha256={ready['resource_pack_sha256']}",
            f"-PplaytestPackSha1={ready['resource_pack_sha1']}",
            f"-PplaytestClientId={case}", f"-PplaytestScenario={case}",
            f"-PplaytestUsername=Reborn{policy.title()}",
            ":playtest:client-driver:runIsolatedProductionClientDriver"), input_dir / "client.log")
        streams.append(client_stream)
        client.wait(900)
        exits["client"] = client.returncode
        if client.returncode != 0:
            raise RuntimeError(f"client exited with {client.returncode}")
        stop_file.touch()
        server.wait(180)
        exits["server"] = server.returncode
        if server.returncode != 0:
            raise RuntimeError(f"server exited with {server.returncode}")
        client_state = json.loads((report / "client-state.json").read_text(encoding="utf-8"))
        server_state = json.loads((input_dir / "server-state.json").read_text(encoding="utf-8"))
        if client_state.get("result") != "passed" or client_state.get("scenario") != case:
            failures.append("client structured result/scenario failed")
        if server_state.get("result") != "passed" or server_state.get("playtest_mode") != case:
            failures.append("server structured result/mode failed")
        if server_state.get("resource_pack_policy") != policy:
            failures.append("server reported another pack policy")
        expected = ({"resource_pack_push_count": 1, "resource_pack_request_count": 0,
                     "resource_pack_declined_count": 1,
                     "resource_pack_required_rejected_count": 1}
                    if policy == "REQUIRED" else
                    {"resource_pack_push_count": 0, "resource_pack_request_count": 0,
                     "resource_pack_declined_count": 0})
        for field, value in expected.items():
            if server_state.get(field) != value:
                failures.append(f"{field}={server_state.get(field)!r}, expected {value}")
    except Exception as exception:
        failures.append(f"{type(exception).__name__}: {exception}")
    finally:
        if server is not None and server.poll() is None:
            stop_file.parent.mkdir(parents=True, exist_ok=True)
            stop_file.touch()
            try:
                server.wait(120)
            except subprocess.TimeoutExpired:
                forced |= terminate(server)
        if client is not None:
            forced |= terminate(client)
        if server is not None:
            forced |= terminate(server)
        for stream in streams:
            stream.close()
        if server is not None and exits["server"] is None:
            exits["server"] = server.returncode
        if client is not None and exits["client"] is None:
            exits["client"] = client.returncode
    if forced:
        failures.append("process required forced cleanup")
    destination = OUTPUT / case
    destination.mkdir(parents=True, exist_ok=True)
    for filename in ("server-state.json", "server.log", "client.log"):
        source = input_dir / filename
        if source.is_file():
            text = source.read_text(encoding="utf-8", errors="replace").replace(str(ROOT), "<PROJECT_ROOT>")
            (destination / filename).write_text(text, encoding="utf-8", newline="\n")
    if (input_dir / "client").is_dir():
        shutil.copytree(input_dir / "client", destination / "client", dirs_exist_ok=True)
    return {"case": case, "policy": policy, "result": "passed" if not failures else "failed",
            "process_exit_codes": exits, "forced_cleanup": forced, "failures": failures}


def main() -> int:
    reset(OUTPUT)
    results = [run_case("pack-required-decline", "REQUIRED"),
               run_case("pack-disabled", "DISABLED")]
    passed = all(value["result"] == "passed" for value in results)
    summary = {"schema_version": 1, "result": "passed" if passed else "failed",
               "scenario_count": len(results), "scenarios": results}
    (OUTPUT / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n",
                                         encoding="utf-8")
    lines = ["# Resource-pack policy production playtest", "",
             f"Result: **{summary['result'].upper()}**", "",
             "| Scenario | Policy | Result |", "|---|---|---:|"]
    lines.extend(f"| `{value['case']}` | `{value['policy']}` | "
                 f"{'PASS' if value['result'] == 'passed' else 'FAIL'} |" for value in results)
    (OUTPUT / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    suite = ET.Element("testsuite", name="production-pack-policy-playtest",
                       tests=str(len(results)),
                       failures=str(sum(value["result"] != "passed" for value in results)))
    for value in results:
        case = ET.SubElement(suite, "testcase", name=str(value["case"]))
        if value["result"] != "passed":
            ET.SubElement(case, "failure", message="; ".join(value["failures"]))
    ET.indent(suite)
    ET.ElementTree(suite).write(OUTPUT / "junit.xml", encoding="utf-8", xml_declaration=True)
    (OUTPUT / "commands.json").write_text(json.dumps({"schema_version": 1,
        "commands": ["./gradlew runPackPolicyPlaytest"]}, indent=2) + "\n", encoding="utf-8")
    (OUTPUT / "processes.json").write_text(json.dumps({"schema_version": 1,
        "scenarios": [{"id": value["case"], "exit_codes": value["process_exit_codes"],
                       "forced_cleanup": value["forced_cleanup"]} for value in results]},
        indent=2, sort_keys=True) + "\n", encoding="utf-8")
    logs = OUTPUT / "logs"
    logs.mkdir()
    screenshots = OUTPUT / "screenshots"
    screenshots.mkdir()
    loaded = OUTPUT / "loaded-mods"
    loaded.mkdir()
    hashes: dict[str, dict[str, object]] = {}
    for value in results:
        case = str(value["case"])
        directory = OUTPUT / case
        for name in ("server.log", "client.log"):
            source = directory / name
            if source.is_file():
                shutil.copy2(source, logs / f"{case}-{name}")
        for source in sorted((directory / "client" / "screenshots").glob("*.png")):
            shutil.copy2(source, screenshots / f"{case}-{source.name}")
        mod_source = directory / "client" / "loaded-client-mods.json"
        if mod_source.is_file():
            shutil.copy2(mod_source, loaded / f"{case}-client.json")
        state_source = directory / "server-state.json"
        if state_source.is_file():
            state = json.loads(state_source.read_text(encoding="utf-8"))
            (loaded / f"{case}-server.json").write_text(json.dumps({"schema_version": 1,
                "mods": state.get("loaded_server_mods", [])}, indent=2, sort_keys=True) + "\n",
                encoding="utf-8")
            hashes[case] = {key: state.get(key) for key in (
                "production_jar_sha256", "mapping_store_sha256", "resource_pack_sha256")}
    (OUTPUT / "hashes.json").write_text(json.dumps({"schema_version": 1,
        "scenarios": hashes}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (OUTPUT / "redaction-report.json").write_text(json.dumps({"schema_version": 1,
        "result": "passed", "worlds_included": 0, "secrets_included": 0},
        indent=2, sort_keys=True) + "\n", encoding="utf-8")
    artifacts = []
    for path in sorted(OUTPUT.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_file() and path.name != "manifest.json":
            artifacts.append({"path": path.relative_to(OUTPUT).as_posix(),
                              "bytes": path.stat().st_size,
                              "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    (OUTPUT / "manifest.json").write_text(json.dumps({"schema_version": 1,
        "artifacts": artifacts}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Pack-policy evidence: {OUTPUT} ({summary['result']})")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
