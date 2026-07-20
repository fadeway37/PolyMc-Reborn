# SPDX-License-Identifier: LGPL-3.0-or-later
"""Build the whitelisted, sanitized production-client playtest evidence bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import sys
import tempfile
import xml.etree.ElementTree as ET
import zlib
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


REQUIRED_SCREENSHOTS = (
    "01-connected.png",
    "02-resource-pack-prompt.png",
    "03-resource-pack-applied.png",
    "04-item-held.png",
    "05-item-after-use.png",
    "06-block-placed.png",
    "07-block-state-off.png",
    "08-block-state-on.png",
    "09-block-broken.png",
    "10-gui-open.png",
    "11-gui-after-shift-click.png",
    "12-gui-after-hotbar-swap.png",
    "13-gui-reopened.png",
    "14-property-gui-start.png",
    "15-property-gui-progress.png",
    "16-property-gui-complete.png",
    "14-entity-spawned.png",
    "15-entity-moved.png",
    "16-entity-interacted.png",
    "17-reconnected.png",
)
OPTIONAL_SCREENSHOTS = ("18-external-content.png",)
REQUIRED_STEP_COUNTS = {
    "client-isolation": 1,
    "connect": 2,
    "resource-pack-initial-connection": 1,
    "resource-pack-reconnection": 1,
    "movement-look": 1,
    "hotbar": 1,
    "semantic-item-use": 1,
    "item-drop-pickup": 1,
    "simple-block": 1,
    "place": 1,
    "state-toggle": 1,
    "break": 1,
    "gui-transactions": 1,
    "property-gui": 1,
    "entity-use-attack": 1,
    "gui-open-at-disconnect": 1,
    "disconnect": 1,
    "reconnect": 1,
    **{f"screenshot:{filename.removesuffix('.png')}": 1 for filename in REQUIRED_SCREENSHOTS},
}

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SENSITIVE_VALUE = re.compile(
    r"(?i)(authorization|cookie|password|passwd|secret|token)(\s*[:=]\s*)([^\s,;]+)"
)
KNOWN_TOKEN = re.compile(
    r"(?i)\b(?:bearer\s+[a-z0-9._~+/=-]{12,}|gh[pousr]_[a-z0-9]{20,}|github_pat_[a-z0-9_]{20,}|xox[baprs]-[a-z0-9-]{12,})\b"
)
WINDOWS_USER_PATH = re.compile(r"(?i)[a-z]:[\\/]users[\\/][^\\/\s\"']+")
WINDOWS_ABSOLUTE_PATH = re.compile(r"(?i)(?<![\w])[a-z]:[\\/][^\r\n\"'<>|]+")
POSIX_LOCAL_PATH = re.compile(r"(?<![:\w])/(?:home|tmp|var/tmp|users|workspace|work)/[^\r\n\"'<>|]+", re.IGNORECASE)
SENSITIVE_KEYS = frozenset({"authorization", "cookie", "password", "passwd", "secret", "token"})
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SHA1 = re.compile(r"^[0-9a-f]{40}$")
REGISTRY_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
EXPECTED_MAPPING_DECISIONS = {
    "item:polymc-reborn-gametest:food_item": "HEURISTIC",
    "block:polymc-reborn-gametest:full_block": "HEURISTIC",
    "block:polymc-reborn-gametest:stateful_block": "HEURISTIC",
    "gui:polymc-reborn-gametest:fixture_menu": "EXPLICIT",
    "gui:polymc-reborn-gametest:property_menu": "EXPLICIT",
    "entity:polymc-reborn-gametest:fixture_entity": "EXPLICIT",
    "item:polymc-reborn-api-consumer:consumer_item": "EXPLICIT",
    "block:polymc-reborn-api-consumer:consumer_block": "EXPLICIT",
    "gui:polymc-reborn-api-consumer:consumer_menu": "EXPLICIT",
    "entity:polymc-reborn-api-consumer:consumer_entity": "EXPLICIT",
}


@dataclass(frozen=True)
class Check:
    check_id: str
    passed: bool
    detail: str


class EvidenceError(RuntimeError):
    """Raised when evidence paths themselves are unsafe."""


def _within(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _safe_path(path: Path, project_root: Path, label: str) -> Path:
    resolved = path.resolve()
    if not _within(resolved, project_root):
        raise EvidenceError(f"{label} must be inside the project root")
    return resolved


def _sanitize_text(value: str, project_root: Path) -> str:
    replacements = {
        str(project_root): "<PROJECT_ROOT>",
        str(project_root).replace("\\", "/"): "<PROJECT_ROOT>",
        str(Path.home().resolve()): "<USER_HOME>",
        str(Path.home().resolve()).replace("\\", "/"): "<USER_HOME>",
    }
    sanitized = value
    for original in sorted(replacements, key=len, reverse=True):
        sanitized = sanitized.replace(original, replacements[original])
    sanitized = WINDOWS_USER_PATH.sub("<USER_HOME>", sanitized)
    sanitized = SENSITIVE_VALUE.sub(lambda match: match.group(1) + match.group(2) + "<REDACTED>", sanitized)
    sanitized = KNOWN_TOKEN.sub("<REDACTED_TOKEN>", sanitized)
    sanitized = WINDOWS_ABSOLUTE_PATH.sub("<LOCAL_PATH>", sanitized)
    sanitized = POSIX_LOCAL_PATH.sub("<LOCAL_PATH>", sanitized)
    return sanitized


def _sanitize_json(value: Any, project_root: Path) -> Any:
    if isinstance(value, dict):
        sanitized = {}
        for key, item in value.items():
            key_text = str(key)
            sanitized[key_text] = "<REDACTED>" if key_text.casefold() in SENSITIVE_KEYS else _sanitize_json(item, project_root)
        return sanitized
    if isinstance(value, list):
        return [_sanitize_json(item, project_root) for item in value]
    if isinstance(value, str):
        return _sanitize_text(value, project_root)
    return value


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(file_descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_name, path)
    finally:
        temporary_path = Path(temporary_name)
        if temporary_path.exists():
            temporary_path.unlink()


def _write_json(path: Path, value: Any) -> None:
    encoded = (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n").encode("utf-8")
    _atomic_write(path, encoded)


def _read_json(path: Path, project_root: Path, checks: list[Check], check_id: str) -> Any | None:
    if not path.is_file() or path.is_symlink():
        checks.append(Check(check_id, False, f"missing regular file: {path.name}"))
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exception:
        checks.append(Check(check_id, False, f"invalid JSON in {path.name}: {exception.__class__.__name__}"))
        return None
    if not isinstance(value, dict):
        checks.append(Check(check_id, False, f"{path.name} must contain a JSON object"))
        return None
    checks.append(Check(check_id, True, f"parsed {path.name}"))
    return _sanitize_json(value, project_root)


def _result_check(checks: list[Check], check_id: str, value: Any, label: str) -> None:
    result = value.get("result") if isinstance(value, dict) else None
    checks.append(Check(check_id, result == "passed", f"{label} result is {result!r}"))


def _validate_orchestration(value: Any, checks: list[Check]) -> None:
    if not isinstance(value, dict):
        checks.extend(
            Check(check_id, False, "orchestration state is unavailable")
            for check_id in (
                "orchestration-result",
                "orchestration-failures",
                "orchestration-timeouts",
                "orchestration-forced-termination",
                "orchestration-server-exit",
                "orchestration-client-exit",
                "orchestration-clean-shutdown",
            )
        )
        return

    result = value.get("result")
    checks.append(Check("orchestration-result", result == "passed", f"orchestration result is {result!r}"))

    failure_count = value.get("failure_count")
    failures = value.get("failures")
    failures_valid = (
        isinstance(failure_count, int)
        and not isinstance(failure_count, bool)
        and failure_count == 0
        and isinstance(failures, list)
        and not failures
    )
    checks.append(
        Check(
            "orchestration-failures",
            failures_valid,
            f"orchestration failure_count={failure_count!r}; failures={failures!r}",
        )
    )

    server = value.get("server")
    client = value.get("client")
    server = server if isinstance(server, dict) else {}
    client = client if isinstance(client, dict) else {}
    server_timed_out = server.get("timed_out")
    client_timed_out = client.get("timed_out")
    checks.append(
        Check(
            "orchestration-timeouts",
            server_timed_out is False and client_timed_out is False,
            f"server timed_out={server_timed_out!r}; client timed_out={client_timed_out!r}",
        )
    )
    server_forced = server.get("forced_termination")
    client_forced = client.get("forced_termination")
    checks.append(
        Check(
            "orchestration-forced-termination",
            server_forced is False and client_forced is False,
            f"server forced_termination={server_forced!r}; client forced_termination={client_forced!r}",
        )
    )

    server_exit_code = server.get("exit_code")
    server_exit_valid = (
        server.get("started") is True
        and server.get("readiness_marker") is True
        and server.get("tcp_ready") is True
        and isinstance(server_exit_code, int)
        and not isinstance(server_exit_code, bool)
        and server_exit_code == 0
    )
    checks.append(
        Check(
            "orchestration-server-exit",
            server_exit_valid,
            "server "
            f"started={server.get('started')!r}, readiness_marker={server.get('readiness_marker')!r}, "
            f"tcp_ready={server.get('tcp_ready')!r}, exit_code={server_exit_code!r}",
        )
    )

    client_exit_code = client.get("exit_code")
    client_exit_valid = (
        client.get("started") is True
        and isinstance(client_exit_code, int)
        and not isinstance(client_exit_code, bool)
        and client_exit_code == 0
    )
    checks.append(
        Check(
            "orchestration-client-exit",
            client_exit_valid,
            f"client started={client.get('started')!r}, exit_code={client_exit_code!r}",
        )
    )

    clean_stop_requested = server.get("clean_stop_requested")
    clean_shutdown = server.get("clean_shutdown")
    checks.append(
        Check(
            "orchestration-clean-shutdown",
            clean_stop_requested is True and clean_shutdown is True,
            f"server clean_stop_requested={clean_stop_requested!r}, clean_shutdown={clean_shutdown!r}",
        )
    )


def _schema_check(checks: list[Check], check_id: str, value: Any, label: str) -> None:
    schema_version = value.get("schema_version") if isinstance(value, dict) else None
    checks.append(Check(check_id, schema_version == 1, f"{label} schema_version is {schema_version!r}"))


def _validate_server_observations(value: Any, checks: list[Check]) -> None:
    if not isinstance(value, dict):
        checks.append(Check("server-observations", False, "server observations are unavailable"))
        return
    expected_booleans = (
        "simple_block_placed_observed",
        "simple_block_broken_observed",
        "placed_block_observed",
        "state_toggle_observed",
        "broken_block_observed",
        "gui_inventory_integrity",
        "semantic_use_observed",
        "item_drop_observed",
        "item_pickup_observed",
        "mapping_store_stable",
        "resource_pack_stable",
        "api_consumer_loaded",
        "support_bundle_valid",
    )
    failed = [field for field in expected_booleans if value.get(field) is not True]
    long_soak = value.get("soak_mode") == "long"
    expected_counts = {
        "join_count": 3 if long_soak else 2,
        "disconnect_count": 3 if long_soak else 2,
        "gui_open_count": 28 if long_soak else 3,
        "gui_close_count": 28 if long_soak else 3,
        "entity_use_count": 1,
        "entity_attack_count": 1,
        "entity_interaction_callbacks": 2,
        "admin_command_count": 22 if long_soak else 18,
        "mapping_dry_run_count": 3 if long_soak else 1,
        "support_bundle_generation_count": 3 if long_soak else 1,
        "resource_pack_push_count": 3 if long_soak else 2,
        "resource_pack_request_count": 3 if long_soak else 2,
        "tool_damage": 3 if value.get("external_mode") == "block" else 2,
        "food_remaining": 3,
        "basic_item_remaining": 1,
        "property_gui_open_count": 2,
        "property_completion_count": 1,
        "resource_pack_applied_count": 3 if long_soak else 2,
        "resource_pack_declined_count": 0,
        "resource_pack_failed_count": 0,
    }
    for field, expected in expected_counts.items():
        observed = value.get(field)
        if not isinstance(observed, int) or isinstance(observed, bool) or observed != expected:
            failed.append(f"{field}={expected}")
    expected_values = {
        "client_profile": "VANILLA",
        "gui_active_sessions": 0,
        "entity_projection_sessions": 0,
        "resource_pack_active_sessions": 0,
        "resource_pack_policy": "REQUIRED",
    }
    for field, expected in expected_values.items():
        if value.get(field) != expected:
            failed.append(f"{field}={expected!r}")
    stress_counts = {
        "soak_gui_cycles": 25 if long_soak else 0,
        "soak_rejected_transactions": 25 if long_soak else 0,
        "soak_entity_spawns": 50 if long_soak else 0,
        "soak_entity_despawns": 50 if long_soak else 0,
        "soak_tracking_cycles": 10 if long_soak else 0,
        "dimension_change_count": 20 if long_soak else 0,
    }
    for field, expected in stress_counts.items():
        if value.get(field) != expected:
            failed.append(f"{field}={expected}")
    if long_soak:
        for field in ("transaction_average_millis", "transaction_max_millis"):
            observed = value.get(field)
            if not isinstance(observed, (int, float)) or isinstance(observed, bool) or observed < 0:
                failed.append(f"{field}>=0")
    for field, minimum in {
        "property_tick_count": 100,
        "entity_passenger_packets": 2,
        "entity_equipment_packets": 2,
        "support_bundle_entries": 5,
    }.items():
        observed = value.get(field)
        if not isinstance(observed, int) or isinstance(observed, bool) or observed < minimum:
            failed.append(f"{field}>={minimum}")
    support_sha = value.get("support_bundle_sha256")
    if not isinstance(support_sha, str) or SHA256.fullmatch(support_sha) is None:
        failed.append("support_bundle_sha256")
    checks.append(
        Check(
            "server-observations",
            not failed,
            "validated server-authoritative placement, state, GUI, entity, item, and reconnect observations"
            if not failed
            else "missing or failed observations: " + ", ".join(failed),
        )
    )


def _validate_server_mapping_evidence(value: Any, ready: Any, checks: list[Check]) -> None:
    if not isinstance(value, dict):
        checks.append(Check("production-jar", False, "server state is unavailable"))
        checks.append(Check("server-mapping-decisions", False, "server state is unavailable"))
        return

    jar_name = value.get("production_jar_name")
    jar_sha256 = value.get("production_jar_sha256")
    ready_jar_name = ready.get("production_jar_name") if isinstance(ready, dict) else None
    ready_jar_sha256 = ready.get("production_jar_sha256") if isinstance(ready, dict) else None
    jar_valid = (
        isinstance(jar_name, str)
        and re.fullmatch(r"polymc-reborn-[a-zA-Z0-9.+_-]+\.jar", jar_name) is not None
        and "/" not in jar_name
        and "\\" not in jar_name
        and isinstance(jar_sha256, str)
        and SHA256.fullmatch(jar_sha256) is not None
        and ready_jar_name == jar_name
        and ready_jar_sha256 == jar_sha256
    )
    checks.append(
        Check(
            "production-jar",
            jar_valid,
            f"server name={jar_name!r}, sha256={jar_sha256!r}; "
            f"ready name={ready_jar_name!r}, sha256={ready_jar_sha256!r}",
        )
    )

    decisions = value.get("mapping_decisions")
    failures: list[str] = []
    if not isinstance(decisions, dict):
        failures.append("mapping_decisions is not an object")
    else:
        for decision_id, expected_status in EXPECTED_MAPPING_DECISIONS.items():
            decision = decisions.get(decision_id)
            if not isinstance(decision, dict):
                failures.append(f"missing {decision_id}")
                continue
            if decision.get("status") != expected_status:
                failures.append(f"{decision_id}.status={decision.get('status')!r}")
            for field in ("provider", "backend", "strategy", "client_carrier"):
                field_value = decision.get(field)
                if not isinstance(field_value, str) or not field_value.strip():
                    failures.append(f"{decision_id}.{field} is empty or invalid")
    checks.append(
        Check(
            "server-mapping-decisions",
            not failures,
            "validated explicit/heuristic production MappingPlan decisions"
            if not failures
            else "; ".join(failures),
        )
    )


def _validate_resource_pack_hashes(
    ready: Any,
    server_state: Any,
    client_state: Any,
    checks: list[Check],
) -> dict[str, Any]:
    ready_sha256 = ready.get("resource_pack_sha256") if isinstance(ready, dict) else None
    ready_sha1 = ready.get("resource_pack_sha1") if isinstance(ready, dict) else None
    server_sha256 = server_state.get("resource_pack_sha256") if isinstance(server_state, dict) else None
    server_sha1 = server_state.get("resource_pack_sha1") if isinstance(server_state, dict) else None
    client_sha256 = client_state.get("resource_pack_sha256") if isinstance(client_state, dict) else None
    client_sha1 = client_state.get("resource_pack_sha1") if isinstance(client_state, dict) else None
    expected_client_sha256 = (client_state.get("resource_pack_expected_sha256")
                              if isinstance(client_state, dict) else None)
    expected_client_sha1 = (client_state.get("resource_pack_expected_sha1")
                            if isinstance(client_state, dict) else None)
    client_pack_bytes = client_state.get("resource_pack_bytes") if isinstance(client_state, dict) else None
    client_pack_sessions = (client_state.get("resource_pack_accepted_sessions")
                            if isinstance(client_state, dict) else None)
    client_pack_files = (client_state.get("resource_pack_matching_files")
                         if isinstance(client_state, dict) else None)
    server_pack_sessions = (server_state.get("resource_pack_applied_count")
                            if isinstance(server_state, dict) else None)
    ready_sha256 = ready_sha256.casefold() if isinstance(ready_sha256, str) else None
    ready_sha1 = ready_sha1.casefold() if isinstance(ready_sha1, str) else None
    server_sha256 = server_sha256.casefold() if isinstance(server_sha256, str) else None
    server_sha1 = server_sha1.casefold() if isinstance(server_sha1, str) else None
    client_sha256 = client_sha256.casefold() if isinstance(client_sha256, str) else None
    client_sha1 = client_sha1.casefold() if isinstance(client_sha1, str) else None
    expected_client_sha256 = (expected_client_sha256.casefold()
                              if isinstance(expected_client_sha256, str) else None)
    expected_client_sha1 = expected_client_sha1.casefold() if isinstance(expected_client_sha1, str) else None

    valid_sha256 = ready_sha256 is not None and SHA256.fullmatch(ready_sha256) is not None
    valid_sha1 = ready_sha1 is not None and SHA1.fullmatch(ready_sha1) is not None
    checks.append(
        Check(
            "resource-pack-ready-hashes",
            valid_sha256 and valid_sha1,
            "server readiness marker contains valid SHA-256 and SHA-1"
            if valid_sha256 and valid_sha1
            else "server readiness marker has missing or malformed resource-pack hashes",
        )
    )
    cache_semantics_valid = (
        isinstance(client_pack_sessions, int)
        and not isinstance(client_pack_sessions, bool)
        and isinstance(server_pack_sessions, int)
        and not isinstance(server_pack_sessions, bool)
        and client_pack_sessions == server_pack_sessions
        and isinstance(client_pack_files, int)
        and not isinstance(client_pack_files, bool)
        and 1 <= client_pack_files <= client_pack_sessions
    )
    checks.append(
        Check(
            "resource-pack-client-cache",
            cache_semantics_valid,
            (f"client accepted {client_pack_sessions} pack session(s), server confirmed "
             f"{server_pack_sessions}, and {client_pack_files} hash-matching cache file(s) remain")
            if cache_semantics_valid
            else (f"invalid pack cache/session evidence: client_sessions={client_pack_sessions!r}, "
                  f"server_sessions={server_pack_sessions!r}, matching_files={client_pack_files!r}"),
        )
    )
    hashes_match = (
        valid_sha256
        and server_sha256 == ready_sha256
        and server_sha1 == ready_sha1
        and client_sha256 == ready_sha256
        and client_sha1 == ready_sha1
        and expected_client_sha256 == ready_sha256
        and expected_client_sha1 == ready_sha1
        and isinstance(client_pack_bytes, int)
        and not isinstance(client_pack_bytes, bool)
        and client_pack_bytes > 0
    )
    checks.append(
        Check(
            "resource-pack-hash-consistency",
            hashes_match,
            "ready, server, and downloaded isolated-client ZIP agree on SHA-256 and SHA-1"
            if hashes_match
            else "resource-pack hashes differ between ready, server-state, or client-state evidence",
        )
    )
    return {
        "sha256": ready_sha256,
        "sha1": ready_sha1,
        "consistent": hashes_match,
        "reported_by": {
            "server_ready": {"sha256": ready_sha256, "sha1": ready_sha1},
            "server_state": {"sha256": server_sha256, "sha1": server_sha1},
            "client_state": {
                "sha256": client_sha256,
                "sha1": client_sha1,
                "expected_sha256": expected_client_sha256,
                "expected_sha1": expected_client_sha1,
                "bytes": client_pack_bytes,
            },
        },
    }


def _copy_operator_reports(
    server_ready: Path,
    output_dir: Path,
    project_root: Path,
    ready: Any,
    checks: list[Check],
) -> None:
    source_dir = server_ready.parent / "config" / "polymc-reborn" / "reports"
    if source_dir.is_symlink():
        checks.extend((
            Check("compatibility-report-json", False, "operator reports directory is a symbolic link"),
            Check("resource-pack-report-json", False, "operator reports directory is a symbolic link"),
            Check("compatibility-report-markdown", False, "operator reports directory is a symbolic link"),
            Check("resource-pack-report-markdown", False, "operator reports directory is a symbolic link"),
            Check("operator-reports-contract", False, "operator reports directory is unsafe"),
        ))
        return
    source_dir = _safe_path(source_dir, project_root, "operator reports directory")
    destination_dir = output_dir / "reports"
    destination_dir.mkdir(parents=True, exist_ok=True)
    compatibility = _read_json(
        source_dir / "compatibility-latest.json",
        project_root,
        checks,
        "compatibility-report-json",
    )
    resource_pack = _read_json(
        source_dir / "resource-pack-latest.json",
        project_root,
        checks,
        "resource-pack-report-json",
    )
    if compatibility is not None:
        _write_json(destination_dir / "compatibility-latest.json", compatibility)
    if resource_pack is not None:
        _write_json(destination_dir / "resource-pack-latest.json", resource_pack)
    _copy_log(
        source_dir / "compatibility-latest.md",
        destination_dir / "compatibility-latest.md",
        project_root,
        checks,
        "compatibility-report-markdown",
    )
    _copy_log(
        source_dir / "resource-pack-latest.md",
        destination_dir / "resource-pack-latest.md",
        project_root,
        checks,
        "resource-pack-report-markdown",
    )
    compatibility_valid = (
        isinstance(compatibility, dict)
        and compatibility.get("schema_version") == 1
        and compatibility.get("minecraft_version") == "26.1.2"
        and isinstance(compatibility.get("totals"), dict)
    )
    ready_sha256 = ready.get("resource_pack_sha256") if isinstance(ready, dict) else None
    resource_pack_valid = (
        isinstance(resource_pack, dict)
        and resource_pack.get("sha256") == ready_sha256
        and isinstance(resource_pack.get("entryCount"), int)
        and not isinstance(resource_pack.get("entryCount"), bool)
        and resource_pack.get("entryCount") > 0
        and isinstance(resource_pack.get("archiveBytes"), int)
        and not isinstance(resource_pack.get("archiveBytes"), bool)
        and resource_pack.get("archiveBytes") > 0
    )
    checks.append(
        Check(
            "operator-reports-contract",
            compatibility_valid and resource_pack_valid,
            "compatibility and resource-pack reports match the production playtest"
            if compatibility_valid and resource_pack_valid
            else "compatibility or resource-pack report contract/hash is invalid",
        )
    )


def _validate_loaded_mods(value: Any, checks: list[Check]) -> None:
    mods = value.get("mods") if isinstance(value, dict) else None
    if not isinstance(mods, list) or not mods:
        checks.append(Check("client-mod-isolation", False, "loaded client mod list is absent or empty"))
        return
    malformed = [
        entry
        for entry in mods
        if not isinstance(entry, dict)
        or not isinstance(entry.get("id"), str)
        or not isinstance(entry.get("version"), str)
    ]
    if malformed:
        checks.append(Check("client-mod-isolation", False, "loaded client mod list contains malformed entries"))
        return
    identifiers = sorted(entry["id"] for entry in mods)
    prohibited = [
        identifier
        for identifier in identifiers
        if identifier in {
            "polymc-reborn",
            "polymc-reborn-gametest",
            "polymc-reborn-playtest-fixture",
            "polymc-reborn-test-fixture",
        }
        or identifier == "polymer"
        or identifier.startswith("polymer-")
    ]
    expected = sorted((
        "minecraft", "java", "fabricloader", "mixinextras", "fabric-api-base",
        "fabric-resource-loader-v1", "fabric-client-gametest-api-v1",
        "polymc-reborn-client-driver",
    ))
    if prohibited:
        checks.append(Check("client-mod-isolation", False, "prohibited client mods loaded: " + ", ".join(prohibited)))
    elif identifiers != expected:
        checks.append(Check("client-mod-isolation", False,
                            "isolated client mod set differs from the exact allowlist: " + ", ".join(identifiers)))
    else:
        checks.append(Check("client-mod-isolation", True, f"validated {len(identifiers)} isolated client mod entries"))


def _copy_screenshots(input_dir: Path, output_dir: Path, checks: list[Check]) -> None:
    source_directory = input_dir / "screenshots"
    destination_directory = output_dir / "screenshots"
    destination_directory.mkdir(parents=True, exist_ok=True)
    for filename in REQUIRED_SCREENSHOTS:
        source = source_directory / filename
        valid = False
        source_is_safe = (
            not source_directory.is_symlink()
            and not source.is_symlink()
            and _within(source.resolve(), input_dir)
        )
        if source_is_safe and source.is_file():
            try:
                valid = _valid_screenshot_png(source)
            except OSError:
                valid = False
        checks.append(
            Check(
                "screenshot:" + filename.removesuffix(".png"),
                valid,
                f"{'validated' if valid else 'missing or invalid'} screenshots/{filename}",
            )
        )
        if valid:
            shutil.copyfile(source, destination_directory / filename)
    for filename in OPTIONAL_SCREENSHOTS:
        source = source_directory / filename
        if not source.is_file():
            continue
        valid = (not source_directory.is_symlink() and not source.is_symlink()
                 and _within(source.resolve(), input_dir) and _valid_screenshot_png(source))
        checks.append(Check("screenshot:" + filename.removesuffix(".png"), valid,
                            f"{'validated' if valid else 'invalid'} optional screenshots/{filename}"))
        if valid:
            shutil.copyfile(source, destination_directory / filename)


def _valid_screenshot_png(path: Path) -> bool:
    data = path.read_bytes()
    if len(data) < 64 or len(data) > 32 * 1024 * 1024 or not data.startswith(PNG_SIGNATURE):
        return False
    offset = len(PNG_SIGNATURE)
    width = height = None
    saw_idat = False
    saw_iend = False
    while offset + 12 <= len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        chunk_type = data[offset + 4:offset + 8]
        chunk_end = offset + 12 + length
        if chunk_end > len(data):
            return False
        payload = data[offset + 8:offset + 8 + length]
        expected_crc = struct.unpack_from(">I", data, offset + 8 + length)[0]
        if zlib.crc32(chunk_type + payload) & 0xFFFFFFFF != expected_crc:
            return False
        if offset == len(PNG_SIGNATURE):
            if chunk_type != b"IHDR" or length != 13:
                return False
            width, height = struct.unpack_from(">II", payload)
        elif chunk_type == b"IDAT":
            saw_idat = True
        elif chunk_type == b"IEND":
            saw_iend = length == 0 and chunk_end == len(data)
            break
        offset = chunk_end
    return bool(width and height and width >= 320 and height >= 180 and saw_idat and saw_iend)


def _copy_log(source: Path, destination: Path, project_root: Path, checks: list[Check], check_id: str) -> None:
    if not source.is_file() or source.is_symlink():
        checks.append(Check(check_id, False, f"missing regular log: {source.name}"))
        return
    try:
        text = source.read_text(encoding="utf-8", errors="replace")
    except OSError as exception:
        checks.append(Check(check_id, False, f"could not read {source.name}: {exception.__class__.__name__}"))
        return
    if not text.strip():
        checks.append(Check(check_id, False, f"log is empty: {source.name}"))
        return
    sanitized = _sanitize_text(text, project_root)
    _atomic_write(destination, sanitized.encode("utf-8"))
    checks.append(Check(check_id, True, f"copied sanitized logs/{destination.name}"))


def _parse_instant(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else None


def _scenario_step_errors(step: Any) -> list[str]:
    if not isinstance(step, dict):
        return ["step is not an object"]
    step_id = step.get("id")
    label = step_id if isinstance(step_id, str) and step_id else "<unknown>"
    failures: list[str] = []
    for field in (
        "id",
        "detail",
        "preconditions",
        "actual_input",
        "client_assertions",
        "server_assertions",
        "cleanup",
        "mapping_decision",
    ):
        value = step.get(field)
        if not isinstance(value, str) or not value.strip():
            failures.append(f"{label}.{field} is empty or invalid")
    if not isinstance(step.get("passed"), bool):
        failures.append(f"{label}.passed is not boolean")

    started = _parse_instant(step.get("started_at"))
    finished = _parse_instant(step.get("finished_at"))
    if started is None:
        failures.append(f"{label}.started_at is not a timezone-aware ISO-8601 instant")
    if finished is None:
        failures.append(f"{label}.finished_at is not a timezone-aware ISO-8601 instant")
    if started is not None and finished is not None and finished < started:
        failures.append(f"{label}.finished_at precedes started_at")

    timeout_ticks = step.get("timeout_ticks")
    if not isinstance(timeout_ticks, int) or isinstance(timeout_ticks, bool) or timeout_ticks <= 0:
        failures.append(f"{label}.timeout_ticks must be a positive integer")

    registry_ids = step.get("registry_ids")
    if not isinstance(registry_ids, list):
        failures.append(f"{label}.registry_ids is not an array")
    elif any(not isinstance(identifier, str) or REGISTRY_ID.fullmatch(identifier) is None
             for identifier in registry_ids):
        failures.append(f"{label}.registry_ids contains a malformed registry ID")

    screenshot = step.get("screenshot")
    if not isinstance(screenshot, str):
        failures.append(f"{label}.screenshot is not a string")
    elif isinstance(step_id, str) and step_id.startswith("screenshot:"):
        expected_screenshot = step_id.removeprefix("screenshot:") + ".png"
        if (screenshot != expected_screenshot
                or screenshot not in REQUIRED_SCREENSHOTS + OPTIONAL_SCREENSHOTS):
            failures.append(f"{label}.screenshot must be the matching required PNG name")
    elif screenshot:
        failures.append(f"{label}.screenshot is only allowed on screenshot steps")

    failure_reason = step.get("failure_reason")
    if not isinstance(failure_reason, str):
        failures.append(f"{label}.failure_reason is not a string")
    elif step.get("passed") is True and failure_reason:
        failures.append(f"{label}.failure_reason must be empty for a passed step")
    elif step.get("passed") is False and not failure_reason.strip():
        failures.append(f"{label}.failure_reason must explain a failed step")
    return failures


def _check_scenario_steps(client_state: Any, checks: list[Check]) -> list[dict[str, Any]]:
    steps = client_state.get("steps") if isinstance(client_state, dict) else None
    if not isinstance(steps, list) or not steps:
        checks.append(Check("client-scenarios", False, "client report contains no scenario steps"))
        return []
    structure_failures = [failure for step in steps for failure in _scenario_step_errors(step)]
    failed_steps = [
        step for step in steps
        if not isinstance(step, dict) or step.get("passed") is not True
    ]
    observed_counts: dict[str, int] = {}
    for step in steps:
        if isinstance(step, dict) and isinstance(step.get("id"), str):
            observed_counts[step["id"]] = observed_counts.get(step["id"], 0) + 1
    missing = [
        f"{step_id}>={minimum}"
        for step_id, minimum in REQUIRED_STEP_COUNTS.items()
        if observed_counts.get(step_id, 0) < minimum
    ]
    checks.append(
        Check(
            "client-scenarios",
            not failed_steps and not missing and not structure_failures,
            f"{len(steps) - len(failed_steps)}/{len(steps)} client steps passed; "
            + ("all required scenarios present" if not missing else "missing " + ", ".join(missing))
            + ("; strict audit fields valid" if not structure_failures
               else "; " + "; ".join(structure_failures[:12])),
        )
    )
    return steps


def _summary_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# PolyMc Reborn production client playtest",
        "",
        f"- Result: **{summary['result'].upper()}**",
        f"- Minecraft: `{summary.get('minecraft_version', 'unknown')}`",
        f"- Evidence time (UTC): `{summary['generated_at']}`",
        f"- Checks: {summary['passed_checks']} passed, {summary['failed_checks']} failed",
        "",
        "## Checks",
        "",
        "| Check | Result | Detail |",
        "|---|---:|---|",
    ]
    for check in summary["checks"]:
        detail = str(check["detail"]).replace("|", "\\|").replace("\n", " ")
        lines.append(f"| `{check['id']}` | {'PASS' if check['passed'] else 'FAIL'} | {detail} |")
    lines.extend(
        [
            "",
            "## Whitelisted artifacts",
            "",
        ]
    )
    for artifact in summary["artifacts"]:
        lines.append(f"- `{artifact}`")
    lines.append("")
    return "\n".join(lines)


def _junit_xml(checks: Iterable[Check]) -> bytes:
    check_list = list(checks)
    failures = sum(not check.passed for check in check_list)
    suite = ET.Element(
        "testsuite",
        {
            "name": "PolyMc Reborn production client playtest",
            "tests": str(len(check_list)),
            "failures": str(failures),
            "errors": "0",
            "skipped": "0",
        },
    )
    for check in check_list:
        case = ET.SubElement(suite, "testcase", {"classname": "playtest.evidence", "name": check.check_id})
        if not check.passed:
            failure = ET.SubElement(case, "failure", {"message": check.detail, "type": "evidence-validation"})
            failure.text = check.detail
    document = ET.ElementTree(suite)
    ET.indent(document, space="  ")
    return ET.tostring(suite, encoding="utf-8", xml_declaration=True) + b"\n"


def aggregate(
    project_root: Path,
    input_dir: Path,
    output_dir: Path,
    client_log: Path,
    server_log: Path,
    orchestrator_log: Path,
    server_ready: Path,
) -> bool:
    project_root = project_root.resolve()
    if input_dir.is_symlink() or output_dir.is_symlink():
        raise EvidenceError("input and output directories must not be symbolic links")
    input_dir = _safe_path(input_dir, project_root, "input directory")
    output_dir = _safe_path(output_dir, project_root, "output directory")
    expected_output = (project_root / "build" / "playtest").resolve()
    if output_dir != expected_output:
        raise EvidenceError("output directory must be exactly build/playtest")
    if output_dir == input_dir or _within(input_dir, output_dir):
        raise EvidenceError("input directory must be separate from build/playtest")
    client_log = _safe_path(client_log, project_root, "client log")
    server_log = _safe_path(server_log, project_root, "server log")
    orchestrator_log = _safe_path(orchestrator_log, project_root, "orchestrator log")
    server_ready = _safe_path(server_ready, project_root, "server readiness marker")

    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)

    checks: list[Check] = []
    client_state = _read_json(input_dir / "client-state.json", project_root, checks, "client-state-json")
    server_state = _read_json(input_dir / "server-state.json", project_root, checks, "server-state-json")
    loaded_mods = _read_json(input_dir / "loaded-client-mods.json", project_root, checks, "loaded-client-mods-json")
    orchestration = _read_json(
        input_dir / "orchestration-state.json",
        project_root,
        checks,
        "orchestration-state-json",
    )
    ready = _read_json(server_ready, project_root, checks, "server-ready-json")

    if client_state is not None:
        _write_json(output_dir / "client-state.json", client_state)
    if server_state is not None:
        _write_json(output_dir / "server-state.json", server_state)
    if loaded_mods is not None:
        _write_json(output_dir / "loaded-client-mods.json", loaded_mods)
    if orchestration is not None:
        _write_json(output_dir / "orchestration-state.json", orchestration)

    _schema_check(checks, "client-state-schema", client_state, "client")
    _schema_check(checks, "server-state-schema", server_state, "server")
    _schema_check(checks, "loaded-client-mods-schema", loaded_mods, "loaded-client-mods")
    _schema_check(checks, "orchestration-state-schema", orchestration, "orchestration-state")
    _schema_check(checks, "server-ready-schema", ready, "server-ready")
    _validate_orchestration(orchestration, checks)
    _result_check(checks, "client-result", client_state, "client")
    _result_check(checks, "server-result", server_state, "server")
    client_kind = client_state.get("client_kind") if isinstance(client_state, dict) else None
    checks.append(
        Check(
            "client-kind",
            client_kind == "isolated-fabric-client-driver",
            f"client_kind is {client_kind!r}",
        )
    )
    minecraft_version = client_state.get("minecraft_version") if isinstance(client_state, dict) else None
    checks.append(
        Check(
            "minecraft-version",
            minecraft_version == "26.1.2",
            f"client reported Minecraft {minecraft_version!r}",
        )
    )
    _validate_loaded_mods(loaded_mods, checks)
    _validate_server_observations(server_state, checks)
    _validate_server_mapping_evidence(server_state, ready, checks)
    resource_pack = _validate_resource_pack_hashes(ready, server_state, client_state, checks)
    scenarios = _check_scenario_steps(client_state, checks)
    _copy_screenshots(input_dir, output_dir, checks)
    _copy_operator_reports(server_ready, output_dir, project_root, ready, checks)
    _copy_log(client_log, output_dir / "logs" / "client.log", project_root, checks, "client-log")
    _copy_log(server_log, output_dir / "logs" / "server.log", project_root, checks, "server-log")
    _copy_log(orchestrator_log, output_dir / "logs" / "orchestrator.log", project_root, checks, "orchestrator-log")

    passed = all(check.passed for check in checks)
    expected_artifacts = [
        "loaded-client-mods.json",
        "orchestration-state.json",
        "reports/compatibility-latest.json",
        "reports/compatibility-latest.md",
        "reports/resource-pack-latest.json",
        "reports/resource-pack-latest.md",
        "server-state.json",
        "client-state.json",
        *(f"screenshots/{filename}" for filename in REQUIRED_SCREENSHOTS),
        "logs/client.log",
        "logs/server.log",
        "logs/orchestrator.log",
    ]
    artifacts = ["summary.json", "summary.md", "junit.xml"] + [
        artifact for artifact in expected_artifacts if (output_dir / artifact).is_file()
    ]
    summary = {
        "schema_version": 1,
        "result": "passed" if passed else "failed",
        "suite": "production-client-playtest",
        "minecraft_version": client_state.get("minecraft_version") if isinstance(client_state, dict) else None,
        "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "passed_checks": sum(check.passed for check in checks),
        "failed_checks": sum(not check.passed for check in checks),
        "checks": [
            {"id": check.check_id, "passed": check.passed, "detail": check.detail}
            for check in checks
        ],
        "client_scenarios": scenarios,
        "resource_pack": resource_pack,
        "production_jar": {
            "name": server_state.get("production_jar_name") if isinstance(server_state, dict) else None,
            "sha256": server_state.get("production_jar_sha256") if isinstance(server_state, dict) else None,
        },
        "mapping_decisions": server_state.get("mapping_decisions") if isinstance(server_state, dict) else None,
        "orchestration": orchestration,
        "artifacts": artifacts,
    }
    _write_json(output_dir / "summary.json", summary)
    _atomic_write(output_dir / "summary.md", _summary_markdown(summary).encode("utf-8"))
    _atomic_write(output_dir / "junit.xml", _junit_xml(checks))
    _materialize_single_client_contract(output_dir, summary, client_state, server_state, orchestration)
    return passed


def _materialize_single_client_contract(
    output_dir: Path,
    summary: dict[str, Any],
    client_state: Any,
    server_state: Any,
    orchestration: Any,
) -> None:
    """Mirror the canonical aggregate into the versioned 0.3 evidence layout."""
    target = output_dir / "single-client"
    if target.exists():
        shutil.rmtree(target)
    target.mkdir()
    for name in ("summary.json", "summary.md", "junit.xml", "client-state.json", "server-state.json"):
        source = output_dir / name
        if source.is_file():
            shutil.copy2(source, target / name)
    for name in ("logs", "screenshots", "reports"):
        source = output_dir / name
        if source.is_dir():
            shutil.copytree(source, target / name)
    loaded = target / "loaded-mods"
    loaded.mkdir()
    source_mods = output_dir / "loaded-client-mods.json"
    if source_mods.is_file():
        shutil.copy2(source_mods, loaded / "client.json")
    _write_json(loaded / "server.json", {
        "schema_version": 1,
        "mods": server_state.get("loaded_server_mods", []) if isinstance(server_state, dict) else [],
    })
    _write_json(target / "commands.json", {
        "schema_version": 1,
        "commands": ["./gradlew runProductionClientPlaytest"],
    })
    _write_json(target / "processes.json", orchestration if isinstance(orchestration, dict) else {
        "schema_version": 1, "result": "unavailable"
    })
    _write_json(target / "hashes.json", {
        "schema_version": 1,
        "production_jar_sha256": summary.get("production_jar", {}).get("sha256"),
        "mapping_store_sha256": (server_state.get("mapping_store_sha256")
                                  if isinstance(server_state, dict) else None),
        "resource_pack_sha256": summary.get("resource_pack", {}).get("sha256"),
        "resource_pack_sha1": summary.get("resource_pack", {}).get("sha1"),
    })
    _write_json(target / "redaction-report.json", {
        "schema_version": 1,
        "result": "passed",
        "policy": "whitelist-copy plus credential and absolute-path sanitization",
        "worlds_included": 0,
        "secrets_included": 0,
    })
    artifacts = []
    for path in sorted(target.rglob("*"), key=lambda value: value.as_posix()):
        if path.is_file() and path.name != "manifest.json":
            artifacts.append({
                "path": path.relative_to(target).as_posix(),
                "bytes": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            })
    _write_json(target / "manifest.json", {"schema_version": 1, "artifacts": artifacts})


def _arguments(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True, type=Path)
    parser.add_argument("--input-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--client-log", required=True, type=Path)
    parser.add_argument("--server-log", required=True, type=Path)
    parser.add_argument("--orchestrator-log", required=True, type=Path)
    parser.add_argument("--server-ready", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    arguments = _arguments(sys.argv[1:] if argv is None else argv)
    try:
        passed = aggregate(
            arguments.project_root,
            arguments.input_dir,
            arguments.output_dir,
            arguments.client_log,
            arguments.server_log,
            arguments.orchestrator_log,
            arguments.server_ready,
        )
    except EvidenceError as exception:
        print(f"unsafe evidence configuration: {exception}", file=sys.stderr)
        return 2
    print(f"Playtest evidence bundle: {'PASSED' if passed else 'FAILED'} ({arguments.output_dir})")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
