# SPDX-License-Identifier: LGPL-3.0-or-later
"""Cross-platform RC soak orchestration with strict cleanup evidence."""

from __future__ import annotations

import argparse
import csv
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
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
BUILD = (ROOT / "build").resolve()
CONTROL_ROOT = BUILD / "soak-orchestrator"
PLAYTEST_ROOT = BUILD / "playtest"
RUNS_ROOT = PLAYTEST_ROOT / "soak-runs"
LATEST_ROOT = PLAYTEST_ROOT / "soak"
PORT_PATTERN = re.compile(
    r"Allocated loopback server port (?P<server>\d+) and resource-pack port (?P<pack>\d+)\."
)
TEMP_SUFFIXES = (".tmp", ".part", ".pending")


class SoakFailure(RuntimeError):
    """An evidence-backed soak failure."""


def _within(path: Path, parent: Path) -> bool:
    return path == parent or parent in path.parents


def _safe_child(path: Path, parent: Path, label: str) -> Path:
    resolved = path.resolve()
    root = parent.resolve()
    if resolved == root or root not in resolved.parents:
        raise SoakFailure(f"unsafe {label}: {resolved}")
    if path.is_symlink():
        raise SoakFailure(f"{label} must not be a symbolic link: {path}")
    return resolved


def _reset(path: Path, parent: Path, label: str) -> Path:
    resolved = _safe_child(path, parent, label)
    if resolved.exists():
        shutil.rmtree(resolved)
    resolved.mkdir(parents=True)
    return resolved


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def _write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(value, encoding="utf-8", newline="\n")
    os.replace(temporary, path)


def _read_json(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        raise SoakFailure(f"missing regular JSON evidence: {path.name}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise SoakFailure(f"JSON evidence is not an object: {path.name}")
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _java_processes() -> set[int]:
    if os.name == "nt":
        result = subprocess.run(
            ["tasklist", "/FO", "CSV", "/NH", "/FI", "IMAGENAME eq java.exe"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        processes: set[int] = set()
        for row in csv.reader(result.stdout.splitlines()):
            if len(row) >= 2 and row[0].lower() == "java.exe":
                try:
                    processes.add(int(row[1]))
                except ValueError:
                    continue
        return processes
    processes = set()
    proc = Path("/proc")
    if not proc.is_dir():
        return processes
    for candidate in proc.iterdir():
        if not candidate.name.isdigit():
            continue
        try:
            if (candidate / "comm").read_text(encoding="utf-8").strip() == "java":
                processes.add(int(candidate.name))
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
    return processes


def _wait_for_process_cleanup(baseline: set[int], timeout: float = 30.0) -> list[int]:
    deadline = time.monotonic() + timeout
    leaked: set[int] = set()
    while time.monotonic() < deadline:
        leaked = _java_processes() - baseline
        if not leaked:
            return []
        time.sleep(0.25)
    return sorted(leaked)


def _port_is_released(port: int) -> bool:
    if not 1 <= port <= 65_535:
        return False
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


def _parse_ports(log_path: Path) -> tuple[int, int]:
    text = log_path.read_text(encoding="utf-8", errors="replace")
    match = PORT_PATTERN.search(text)
    if match is None:
        raise SoakFailure("production playtest did not report its dynamic ports")
    return int(match.group("server")), int(match.group("pack"))


def _rename_probe(path: Path) -> bool:
    probe = path.with_name(path.name + ".handle-probe")
    try:
        os.replace(path, probe)
        os.replace(probe, path)
        return True
    except OSError:
        if probe.exists() and not path.exists():
            try:
                os.replace(probe, path)
            except OSError:
                pass
        return False


def _temporary_files(root: Path) -> list[str]:
    if not root.is_dir():
        return []
    return sorted(
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file() and (path.name.endswith(TEMP_SUFFIXES) or ".tmp." in path.name)
    )


def _operation_checks(server: dict[str, Any], orchestration: dict[str, Any]) -> dict[str, bool]:
    client_process = orchestration.get("client", {})
    server_process = orchestration.get("server", {})
    return {
        "production-server-started": server_process.get("started") is True,
        "real-client-started": client_process.get("started") is True,
        "connected": int(server.get("join_count", 0)) >= 2,
        "resource-pack-applied": int(server.get("resource_pack_applied_count", 0)) >= 2,
        "gui-open-close": int(server.get("gui_open_count", 0)) >= 3
        and int(server.get("gui_close_count", 0)) >= 3,
        "gui-transaction": server.get("gui_inventory_integrity") is True,
        "entity-projection-created-destroyed": int(server.get("entity_passenger_packets", 0)) >= 2
        and int(server.get("entity_projection_sessions", -1)) == 0,
        "entity-use": int(server.get("entity_use_count", 0)) == 1,
        "entity-attack": int(server.get("entity_attack_count", 0)) == 1,
        "mapped-block-place": server.get("placed_block_observed") is True,
        "mapped-block-break": server.get("broken_block_observed") is True,
        "mapped-item-use": server.get("semantic_use_observed") is True,
        "mapped-item-drop": server.get("item_drop_observed") is True,
        "mapped-item-pickup": server.get("item_pickup_observed") is True,
        "disconnect": int(server.get("disconnect_count", 0)) >= 2,
        "reconnect": int(server.get("join_count", 0)) >= 2,
        "mapping-dry-run": int(server.get("mapping_dry_run_count", 0)) >= 1,
        "support-bundle": server.get("support_bundle_valid") is True
        and int(server.get("support_bundle_generation_count", 0)) >= 1,
        "client-clean-exit": client_process.get("exit_code") == 0
        and client_process.get("forced_termination") is False,
        "server-clean-exit": server_process.get("exit_code") == 0
        and server_process.get("clean_shutdown") is True
        and server_process.get("forced_termination") is False,
    }


def _resource_trends(iterations: list[dict[str, Any]]) -> dict[str, Any]:
    """Detect only sustained post-warmup growth, not ordinary JVM noise."""
    fields = {
        "heap_used_bytes": 256 * 1024 * 1024,
        "rss_bytes": 256 * 1024 * 1024,
        "thread_count": 32,
        "open_file_count": 64,
        "cache_size": 0,
    }
    output: dict[str, Any] = {"schema_version": 1, "warmup_iterations": 2, "metrics": {}}
    for field, allowance in fields.items():
        values = [int(item["resources"].get(field, -1)) for item in iterations]
        supported = [value for value in values if value >= 0]
        post_warmup = supported[2:] if len(supported) > 2 else supported
        delta = post_warmup[-1] - post_warmup[0] if len(post_warmup) >= 2 else 0
        monotonic = len(post_warmup) >= 3 and all(
            right >= left for left, right in zip(post_warmup, post_warmup[1:])
        )
        leaking = monotonic and delta > allowance
        output["metrics"][field] = {
            "supported": len(supported) == len(values),
            "values": values,
            "post_warmup_delta": delta,
            "monotonic_non_decreasing": monotonic,
            "allowance": allowance,
            "leak_detected": leaking,
        }
    output["passed"] = not any(
        metric["leak_detected"] for metric in output["metrics"].values()
    )
    output["rationale"] = (
        "Only monotonic post-warmup growth beyond a JVM-noise allowance fails; "
        "unsupported platform metrics remain explicitly -1."
    )
    return output


def _iteration_markdown(value: dict[str, Any]) -> str:
    lines = [
        f"# RC soak iteration {value['iteration']}",
        "",
        f"- Result: **{str(value['result']).upper()}**",
        f"- Client scenarios: `{value['client_scenarios']}`",
        f"- Mapping SHA-256: `{value['mapping_sha256']}`",
        f"- Resource-pack SHA-256: `{value['pack_sha256']}`",
        f"- New Java processes after cleanup: `{len(value['cleanup']['new_java_processes'])}`",
        f"- Ports released: `{value['cleanup']['ports_released']}`",
        f"- File-handle probes passed: `{value['cleanup']['file_handles_released']}`",
        "",
        "## Operations",
        "",
    ]
    for name, passed in value["operations"].items():
        lines.append(f"- {'PASS' if passed else 'FAIL'} `{name}`")
    lines.append("")
    return "\n".join(lines)


def _aggregate_markdown(value: dict[str, Any]) -> str:
    return "\n".join([
        "# PolyMc Reborn RC soak",
        "",
        f"- Result: **{str(value['result']).upper()}**",
        f"- Mode: `{value['mode']}`",
        f"- Iterations: `{value['completed_iterations']}/{value['requested_iterations']}`",
        f"- Client scenarios: `{value['client_scenarios']}`",
        f"- Operation assertions: `{value['operation_assertions']}`",
        f"- Duration seconds: `{value['duration_seconds']}`",
        f"- Mapping SHA-256: `{value.get('mapping_sha256')}`",
        f"- Resource-pack SHA-256: `{value.get('pack_sha256')}`",
        f"- Final GUI sessions: `{value['final_resources']['gui_sessions']}`",
        f"- Final entity projections: `{value['final_resources']['entity_projections']}`",
        f"- Final interaction proxies: `{value['final_resources']['interaction_proxies']}`",
        f"- Final pack sessions: `{value['final_resources']['pack_sessions']}`",
        f"- Operation totals: `{json.dumps(value.get('operation_totals', {}), sort_keys=True)}`",
        "",
        "## Failures",
        "",
        *(f"- {failure}" for failure in value["failures"]),
        "" if value["failures"] else "- None",
        "",
    ])


def _aggregate_junit(iterations: list[dict[str, Any]], failures: list[str]) -> bytes:
    suite = ET.Element("testsuite", {
        "name": "PolyMc Reborn RC soak",
        "tests": str(len(iterations) + (1 if failures else 0)),
        "failures": str(1 if failures else 0),
        "errors": "0",
        "skipped": "0",
    })
    for iteration in iterations:
        ET.SubElement(suite, "testcase", {
            "classname": "playtest.soak",
            "name": f"iteration-{iteration['iteration']}",
        })
    if failures:
        case = ET.SubElement(suite, "testcase", {
            "classname": "playtest.soak", "name": "aggregate-cleanup",
        })
        failure = ET.SubElement(case, "failure", {
            "type": "soak-validation", "message": failures[0],
        })
        failure.text = "\n".join(failures)
    ET.indent(suite, space="  ")
    return ET.tostring(suite, encoding="utf-8", xml_declaration=True) + b"\n"


def _materialize_iteration(staging: Path, target: Path, value: dict[str, Any],
                           ports: dict[str, Any], cleanup: dict[str, Any],
                           resources: dict[str, Any]) -> None:
    shutil.copytree(staging, target)
    source_summary = target / "summary.json"
    if source_summary.is_file():
        os.replace(source_summary, target / "production-summary.json")
    source_markdown = target / "summary.md"
    if source_markdown.is_file():
        os.replace(source_markdown, target / "production-summary.md")
    _write_json(target / "summary.json", value)
    _write_text(target / "summary.md", _iteration_markdown(value))
    _write_json(target / "ports.json", ports)
    _write_json(target / "cleanup.json", cleanup)
    _write_json(target / "resource-counts.json", resources)


def _materialize_failed_iteration(staging: Path, target: Path) -> None:
    """Preserve the nested process evidence before publishing the failed aggregate."""
    if not staging.is_dir() or staging.is_symlink():
        raise SoakFailure("failed iteration staging is missing or unsafe")
    shutil.copytree(staging, target)


def run(iteration_count: int, mode: str) -> int:
    if not 1 <= iteration_count <= 100:
        raise SoakFailure("iteration count must be in [1, 100]")
    started = time.monotonic()
    started_at = datetime.now(UTC)
    run_id = started_at.strftime("%Y%m%dT%H%M%SZ") + f"-{os.getpid()}"
    control_run = _reset(CONTROL_ROOT / run_id, CONTROL_ROOT, "soak control run")
    staging_root = _reset(control_run / "iterations", control_run, "soak staging root")
    control_log = control_run / "orchestrator.log"
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    iterations: list[dict[str, Any]] = []
    failed_staging: dict[int, Path] = {}
    failures: list[str] = []
    baseline_mapping: str | None = None
    baseline_pack: str | None = None

    for index in range(1, iteration_count + 1):
        iteration_log = control_run / f"iteration-{index}.launcher.log"
        before_java = _java_processes()
        environment = os.environ.copy()
        environment["POLYMC_REBORN_SOAK_MODE"] = mode
        environment["POLYMC_REBORN_SOAK_ITERATION"] = str(index)
        command = [str(wrapper), "--no-daemon", "--console=plain", "runProductionClientPlaytest"]
        iteration_started = time.monotonic()
        with iteration_log.open("w", encoding="utf-8", newline="\n") as stream:
            result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                    text=True, env=environment, check=False)
        leaked_java = _wait_for_process_cleanup(before_java)
        log_handle_released = _rename_probe(iteration_log)
        with control_log.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(f"iteration={index} exit={result.returncode} "
                         f"leaked_java={leaked_java} log_handle={log_handle_released}\n")

        source = PLAYTEST_ROOT / "single-client"
        staging = staging_root / f"iteration-{index}"
        if source.is_dir() and not source.is_symlink():
            shutil.copytree(source, staging)
            (staging / "logs").mkdir(exist_ok=True)
            shutil.copy2(iteration_log, staging / "logs" / "soak-launcher.log")
        if result.returncode != 0 or not staging.is_dir():
            if staging.is_dir() and not staging.is_symlink():
                failed_staging[index] = staging
            failures.append(f"iteration {index} launcher/evidence failed with {result.returncode}")
            break

        try:
            production_summary = _read_json(staging / "summary.json")
            server = _read_json(staging / "server-state.json")
            orchestration = _read_json(staging / "processes.json")
            server_port, pack_port = _parse_ports(staging / "logs" / "orchestrator.log")
        except (OSError, ValueError, json.JSONDecodeError, SoakFailure) as exception:
            failed_staging[index] = staging
            failures.append(f"iteration {index} evidence parse failed: {exception}")
            break

        ports = {
            "schema_version": 1,
            "server_port": server_port,
            "resource_pack_port": pack_port,
            "server_port_released": _port_is_released(server_port),
            "resource_pack_port_released": _port_is_released(pack_port),
        }
        temporary_files = _temporary_files(staging)
        evidence_handle_released = _rename_probe(staging)
        mapping_hash = str(server.get("mapping_store_sha256", ""))
        pack_hash = str(server.get("resource_pack_sha256", ""))
        operations = _operation_checks(server, orchestration)
        scenarios = production_summary.get("client_scenarios", [])
        scenario_count = len(scenarios) if isinstance(scenarios, list) else 0
        cleanup = {
            "schema_version": 1,
            "new_java_processes": leaked_java,
            "ports_released": ports["server_port_released"] and ports["resource_pack_port_released"],
            "log_handle_released": log_handle_released,
            "evidence_handle_released": evidence_handle_released,
            "file_handles_released": log_handle_released and evidence_handle_released,
            "temporary_files": temporary_files,
            "forced_cleanup": bool(
                orchestration.get("client", {}).get("forced_termination")
                or orchestration.get("server", {}).get("forced_termination")
            ),
        }
        resources = {
            "schema_version": 1,
            "gui_sessions": int(server.get("gui_active_sessions", -1)),
            "entity_projections": int(server.get("entity_projection_sessions", -1)),
            "interaction_proxies": int(server.get("active_interaction_proxies", -1)),
            "pack_sessions": int(server.get("resource_pack_active_sessions", -1)),
            "support_bundle_entries": int(server.get("support_bundle_entries", 0)),
            "mapping_dry_run_observed": operations["mapping-dry-run"],
            "gui_cycles": int(server.get("soak_gui_cycles", 0)),
            "rejected_transactions": int(server.get("soak_rejected_transactions", 0)),
            "entity_spawns": int(server.get("soak_entity_spawns", 0)),
            "entity_despawns": int(server.get("soak_entity_despawns", 0)),
            "tracking_cycles": int(server.get("soak_tracking_cycles", 0)),
            "reconnect_cycles": max(0, int(server.get("join_count", 0)) - 1),
            "pack_sessions_completed": int(server.get("resource_pack_applied_count", 0)),
            "support_bundles": int(server.get("support_bundle_generation_count", 0)),
            "mapping_dry_runs": int(server.get("mapping_dry_run_count", 0)),
            "server_ticks": int(server.get("server_ticks", 0)),
            "heap_used_bytes": int(server.get("jvm_heap_used_bytes", -1)),
            "rss_bytes": int(server.get("jvm_rss_bytes", -1)),
            "thread_count": int(server.get("jvm_thread_count", -1)),
            "open_file_count": int(server.get("jvm_open_file_count", -1)),
            "cache_size": int(server.get("mapping_cache_size", -1)),
            "gc_count": int(server.get("jvm_gc_count", -1)),
            "transaction_average_millis": float(server.get("transaction_average_millis", 0.0)),
            "transaction_max_millis": float(server.get("transaction_max_millis", 0.0)),
        }
        long_stress_valid = mode != "long" or (
            resources["gui_cycles"] == 25
            and resources["rejected_transactions"] == 25
            and resources["entity_spawns"] == 50
            and resources["entity_despawns"] == 50
            and resources["tracking_cycles"] == 10
            and resources["reconnect_cycles"] == 2
            and resources["pack_sessions_completed"] == 3
            and resources["support_bundles"] == 3
            and resources["mapping_dry_runs"] == 3
        )
        stable = (
            production_summary.get("result") == "passed"
            and scenario_count >= 39
            and all(operations.values())
            and not leaked_java
            and cleanup["ports_released"]
            and cleanup["file_handles_released"]
            and not temporary_files
            and not cleanup["forced_cleanup"]
            and resources["gui_sessions"] == 0
            and resources["entity_projections"] == 0
            and resources["interaction_proxies"] == 0
            and resources["pack_sessions"] == 0
            and long_stress_valid
            and mapping_hash != ""
            and pack_hash != ""
            and baseline_mapping in (None, mapping_hash)
            and baseline_pack in (None, pack_hash)
        )
        baseline_mapping = mapping_hash if baseline_mapping is None else baseline_mapping
        baseline_pack = pack_hash if baseline_pack is None else baseline_pack
        value = {
            "schema_version": 2,
            "suite": "rc-soak-iteration",
            "mode": mode,
            "iteration": index,
            "result": "passed" if stable else "failed",
            "duration_seconds": round(time.monotonic() - iteration_started, 3),
            "client_scenarios": scenario_count,
            "operation_assertions": len(operations),
            "operations": operations,
            "mapping_sha256": mapping_hash,
            "pack_sha256": pack_hash,
            "resources": resources,
            "ports": ports,
            "cleanup": cleanup,
        }
        _write_json(staging / "soak-iteration.json", value)
        iterations.append(value)
        if not stable:
            failures.append(f"iteration {index} violated RC soak stability or cleanup assertions")
            break

    final_run = _reset(RUNS_ROOT / run_id, PLAYTEST_ROOT, "final soak run")
    for iteration in iterations:
        index = int(iteration["iteration"])
        staging = staging_root / f"iteration-{index}"
        target = final_run / f"iteration-{index}"
        _materialize_iteration(staging, target, iteration, iteration["ports"],
                               iteration["cleanup"], iteration["resources"])
    for index, staging in sorted(failed_staging.items()):
        _materialize_failed_iteration(staging, final_run / f"failed-iteration-{index}")
    (final_run / "logs").mkdir(exist_ok=True)
    if control_log.is_file():
        shutil.copy2(control_log, final_run / "logs" / "orchestrator.log")
    for candidate in sorted(control_run.glob("iteration-*.launcher.log")):
        shutil.copy2(candidate, final_run / "logs" / candidate.name)

    passed = len(iterations) == iteration_count and not failures
    final_resources = iterations[-1]["resources"] if iterations else {
        "gui_sessions": -1, "entity_projections": -1,
        "interaction_proxies": -1, "pack_sessions": -1,
    }
    totals = {
        field: sum(int(item["resources"].get(field, 0)) for item in iterations)
        for field in (
            "gui_cycles", "rejected_transactions", "entity_spawns", "entity_despawns",
            "tracking_cycles", "reconnect_cycles", "pack_sessions_completed",
            "support_bundles", "mapping_dry_runs", "server_ticks",
        )
    }
    trends = _resource_trends(iterations)
    long_thresholds = {
        "iterations": len(iterations) >= 10,
        "gui_cycles": totals["gui_cycles"] >= 250,
        "rejected_transactions": totals["rejected_transactions"] >= 250,
        "entity_spawns": totals["entity_spawns"] >= 500,
        "entity_despawns": totals["entity_despawns"] >= 500,
        "tracking_cycles": totals["tracking_cycles"] >= 100,
        "reconnect_cycles": totals["reconnect_cycles"] >= 20,
        "pack_sessions": totals["pack_sessions_completed"] >= 10,
        "support_bundles": totals["support_bundles"] >= 25,
        "mapping_dry_runs": totals["mapping_dry_runs"] >= 25,
        "server_ticks": totals["server_ticks"] >= 10_000,
        "resource_trends": trends["passed"],
    }
    if mode == "long" and not all(long_thresholds.values()):
        failures.append("long-soak operation or resource-trend threshold was not satisfied")
        passed = False
    aggregate = {
        "schema_version": 2,
        "suite": "rc-soak",
        "mode": mode,
        "result": "passed" if passed else "failed",
        "run_id": run_id,
        "requested_iterations": iteration_count,
        "completed_iterations": len(iterations),
        "connection_lifecycles": len(iterations) * 2,
        "client_scenarios": sum(int(item["client_scenarios"]) for item in iterations),
        "operation_assertions": sum(int(item["operation_assertions"]) for item in iterations),
        "operation_totals": totals,
        "long_thresholds": long_thresholds if mode == "long" else {},
        "duration_seconds": round(time.monotonic() - started, 3),
        "mapping_sha256": baseline_mapping,
        "pack_sha256": baseline_pack,
        "final_resources": final_resources,
        "failures": failures,
        "iterations": [{
            "iteration": item["iteration"],
            "result": item["result"],
            "duration_seconds": item["duration_seconds"],
            "client_scenarios": item["client_scenarios"],
            "mapping_sha256": item["mapping_sha256"],
            "pack_sha256": item["pack_sha256"],
        } for item in iterations],
    }
    _write_json(final_run / "summary.json", aggregate)
    _write_json(final_run / "resource-trends.json", trends)
    _write_text(final_run / "summary.md", _aggregate_markdown(aggregate))
    (final_run / "junit.xml").write_bytes(_aggregate_junit(iterations, failures))

    if LATEST_ROOT.exists():
        _safe_child(LATEST_ROOT, PLAYTEST_ROOT, "latest soak output")
        shutil.rmtree(LATEST_ROOT)
    shutil.copytree(final_run, LATEST_ROOT)
    _write_json(RUNS_ROOT / "latest.json", {
        "schema_version": 1,
        "run_id": run_id,
        "mode": mode,
        "result": aggregate["result"],
        "summary": f"{run_id}/summary.json",
    })
    print(f"RC soak evidence: {final_run} ({aggregate['result']}, "
          f"{len(iterations)}/{iteration_count} iterations)")
    return 0 if passed else 1


def _arguments(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--mode", choices=("short", "long"), default="short")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    arguments = _arguments(sys.argv[1:] if argv is None else argv)
    try:
        return run(arguments.iterations, arguments.mode)
    except (OSError, ValueError, json.JSONDecodeError, SoakFailure) as exception:
        print(f"RC soak infrastructure failure: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
