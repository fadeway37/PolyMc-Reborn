# SPDX-License-Identifier: LGPL-3.0-or-later
"""Self-tests for the production-client playtest evidence aggregator."""

from __future__ import annotations

import json
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

import aggregate_evidence


class EvidenceAggregatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name).resolve()
        self.input_directory = self.root / "build" / "run" / "evidence-input"
        self.output_directory = self.root / "build" / "playtest"
        self.input_directory.mkdir(parents=True)
        self.client_log = self.root / "build" / "run" / "client" / "logs" / "latest.log"
        self.server_log = self.root / "build" / "run" / "server" / "logs" / "latest.log"
        self.orchestrator_log = self.root / "build" / "run" / "orchestrator.log"
        self.server_ready = self.root / "build" / "run" / "server" / "server-ready.json"
        self.pack_sha256 = "a" * 64
        self.pack_sha1 = "b" * 40
        for path, content in (
            (self.client_log, f"client from {self.root} token=client-secret\n"),
            (self.server_log, "server ran on 127.0.0.1\n"),
            (self.orchestrator_log, "orchestrator started\n"),
        ):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self._write_json(
            "client-state.json",
            {
                "schema_version": 1,
                "result": "passed",
                "minecraft_version": "26.1.2",
                "client_kind": "isolated-fabric-client-driver",
                "resource_pack_sha256": self.pack_sha256,
                "resource_pack_sha1": self.pack_sha1,
                "resource_pack_expected_sha256": self.pack_sha256,
                "resource_pack_expected_sha1": self.pack_sha1,
                "resource_pack_bytes": 4096,
                "completed_at": "2026-07-18T00:00:00Z",
                "local_debug_path": str(self.root / "private"),
                "token": "json-secret",
                "steps": [
                    {
                        "id": step_id,
                        "passed": True,
                        "detail": "real test action",
                        "started_at": "2026-07-18T00:00:00Z",
                        "finished_at": "2026-07-18T00:00:01Z",
                        "preconditions": "fixture is ready",
                        "actual_input": "real client input",
                        "client_assertions": "client state changed",
                        "server_assertions": "server state changed",
                        "timeout_ticks": 200,
                        "screenshot": (step_id.removeprefix("screenshot:") + ".png"
                                       if step_id.startswith("screenshot:") else ""),
                        "failure_reason": "",
                        "cleanup": "bounded cleanup",
                        "registry_ids": [],
                        "mapping_decision": "verified MappingPlan decision",
                    }
                    for step_id, minimum in aggregate_evidence.REQUIRED_STEP_COUNTS.items()
                    for _ in range(minimum)
                ],
            },
        )
        self._write_json(
            "server-state.json",
            {
                "schema_version": 1,
                "result": "passed",
                "join_count": 2,
                "disconnect_count": 2,
                "placed_block_observed": True,
                "simple_block_placed_observed": True,
                "simple_block_broken_observed": True,
                "state_toggle_observed": True,
                "broken_block_observed": True,
                "gui_open_count": 3,
                "gui_close_count": 3,
                "gui_inventory_integrity": True,
                "entity_use_count": 1,
                "entity_attack_count": 1,
                "entity_interaction_callbacks": 2,
                "semantic_use_observed": True,
                "item_drop_observed": True,
                "item_pickup_observed": True,
                "mapping_store_stable": True,
                "resource_pack_stable": True,
                "api_consumer_loaded": True,
                "support_bundle_valid": True,
                "admin_command_count": 17,
                "resource_pack_request_count": 2,
                "resource_pack_push_count": 2,
                "resource_pack_applied_count": 2,
                "resource_pack_declined_count": 0,
                "resource_pack_failed_count": 0,
                "resource_pack_policy": "REQUIRED",
                "property_gui_open_count": 2,
                "property_completion_count": 1,
                "property_tick_count": 100,
                "entity_passenger_packets": 2,
                "entity_equipment_packets": 2,
                "support_bundle_entries": 5,
                "support_bundle_sha256": "e" * 64,
                "tool_damage": 2,
                "food_remaining": 3,
                "basic_item_remaining": 1,
                "client_profile": "VANILLA",
                "gui_active_sessions": 0,
                "entity_projection_sessions": 0,
                "resource_pack_active_sessions": 0,
                "production_jar_name": "polymc-reborn-0.2.0-alpha.1+26.1.2.jar",
                "production_jar_sha256": "d" * 64,
                "resource_pack_sha256": self.pack_sha256,
                "resource_pack_sha1": self.pack_sha1,
                "mapping_decisions": {
                    decision_id: {
                        "status": expected_status,
                        "provider": "test-provider",
                        "backend": "test-backend",
                        "strategy": "test-strategy",
                        "client_carrier": "minecraft:stone",
                    }
                    for decision_id, expected_status in aggregate_evidence.EXPECTED_MAPPING_DECISIONS.items()
                },
            },
        )
        self.server_ready.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "server_port": 25576,
                    "production_jar_name": "polymc-reborn-0.2.0-alpha.1+26.1.2.jar",
                    "production_jar_sha256": "d" * 64,
                    "resource_pack_sha256": self.pack_sha256,
                    "resource_pack_sha1": self.pack_sha1,
                }
            ),
            encoding="utf-8",
        )
        report_directory = self.server_ready.parent / "config" / "polymc-reborn" / "reports"
        report_directory.mkdir(parents=True)
        (report_directory / "compatibility-latest.json").write_text(
            json.dumps({
                "schema_version": 1,
                "minecraft_version": "26.1.2",
                "totals": {"HEURISTIC": 3, "EXPLICIT": 2},
            }),
            encoding="utf-8",
        )
        (report_directory / "resource-pack-latest.json").write_text(
            json.dumps({"sha256": self.pack_sha256, "entryCount": 29, "archiveBytes": 4096}),
            encoding="utf-8",
        )
        (report_directory / "compatibility-latest.md").write_text(
            "# Compatibility report\n\nValidated fixture decisions.\n", encoding="utf-8"
        )
        (report_directory / "resource-pack-latest.md").write_text(
            "# Resource-pack report\n\nValidated deterministic pack.\n", encoding="utf-8"
        )
        self._write_json(
            "loaded-client-mods.json",
            {
                "schema_version": 1,
                "mods": [
                    {"id": "minecraft", "version": "26.1.2"},
                    {"id": "java", "version": "25"},
                    {"id": "fabricloader", "version": "0.19.3"},
                    {"id": "mixinextras", "version": "0.5.4"},
                    {"id": "fabric-api-base", "version": "test"},
                    {"id": "fabric-resource-loader-v1", "version": "test"},
                    {"id": "fabric-client-gametest-api-v1", "version": "test"},
                    {"id": "polymc-reborn-client-driver", "version": "test"},
                ],
            },
        )
        self._write_json(
            "orchestration-state.json",
            {
                "schema_version": 1,
                "result": "passed",
                "failure_count": 0,
                "failures": [],
                "server": {
                    "started": True,
                    "readiness_marker": True,
                    "tcp_ready": True,
                    "exit_code": 0,
                    "timed_out": False,
                    "forced_termination": False,
                    "clean_stop_requested": True,
                    "clean_shutdown": True,
                },
                "client": {
                    "started": True,
                    "exit_code": 0,
                    "timed_out": False,
                    "forced_termination": False,
                },
            },
        )
        screenshot_directory = self.input_directory / "screenshots"
        screenshot_directory.mkdir()
        for filename in aggregate_evidence.REQUIRED_SCREENSHOTS:
            (screenshot_directory / filename).write_bytes(self._png())
        (screenshot_directory / "not-whitelisted.png").write_bytes(aggregate_evidence.PNG_SIGNATURE + b"ignored")

    @staticmethod
    def _png(width: int = 320, height: int = 180) -> bytes:
        def chunk(kind: bytes, payload: bytes) -> bytes:
            return (struct.pack(">I", len(payload)) + kind + payload
                    + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))
        rows = b"".join(b"\x00" + bytes((row % 251, 80, 160)) * width for row in range(height))
        return (aggregate_evidence.PNG_SIGNATURE
                + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows))
                + chunk(b"IEND", b""))

    def _write_json(self, filename: str, value: object) -> None:
        (self.input_directory / filename).write_text(json.dumps(value), encoding="utf-8")

    def _aggregate(self) -> bool:
        return aggregate_evidence.aggregate(
            self.root,
            self.input_directory,
            self.output_directory,
            self.client_log,
            self.server_log,
            self.orchestrator_log,
            self.server_ready,
        )

    def test_passed_bundle_is_complete_whitelisted_and_sanitized(self) -> None:
        self.assertTrue(self._aggregate())

        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("passed", summary["result"])
        self.assertEqual(0, summary["failed_checks"])
        self.assertEqual(
            set(aggregate_evidence.REQUIRED_SCREENSHOTS),
            {path.name for path in (self.output_directory / "screenshots").iterdir()},
        )
        self.assertFalse((self.output_directory / "not-whitelisted.png").exists())
        for relative_path in (
            "summary.md",
            "junit.xml",
            "loaded-client-mods.json",
            "orchestration-state.json",
            "reports/compatibility-latest.json",
            "reports/compatibility-latest.md",
            "reports/resource-pack-latest.json",
            "reports/resource-pack-latest.md",
            "server-state.json",
            "client-state.json",
            "logs/client.log",
            "logs/server.log",
            "logs/orchestrator.log",
        ):
            self.assertTrue((self.output_directory / relative_path).is_file(), relative_path)

        client_state = (self.output_directory / "client-state.json").read_text(encoding="utf-8")
        client_log = (self.output_directory / "logs" / "client.log").read_text(encoding="utf-8")
        self.assertNotIn(str(self.root), client_state)
        self.assertNotIn("json-secret", client_state)
        self.assertNotIn(str(self.root), client_log)
        self.assertNotIn("client-secret", client_log)
        self.assertIn("<PROJECT_ROOT>", client_state)
        self.assertIn("<REDACTED>", client_log)
        suite = ET.parse(self.output_directory / "junit.xml").getroot()
        self.assertEqual("0", suite.attrib["failures"])

    def test_orchestration_failure_is_in_summary_and_junit(self) -> None:
        state = json.loads((self.input_directory / "orchestration-state.json").read_text(encoding="utf-8"))
        state["result"] = "failed"
        state["failure_count"] = 1
        state["failures"] = ["client process failed"]
        state["client"]["exit_code"] = 17
        self._write_json("orchestration-state.json", state)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", summary["result"])
        self.assertEqual(17, summary["orchestration"]["client"]["exit_code"])
        failures = ET.parse(self.output_directory / "junit.xml").getroot().findall(".//failure")
        messages = [failure.attrib.get("message", "") for failure in failures]
        self.assertTrue(any("failure_count=1" in message for message in messages))
        self.assertTrue(any("exit_code=17" in message for message in messages))

    def test_timeout_and_forced_termination_cannot_pass(self) -> None:
        state = json.loads((self.input_directory / "orchestration-state.json").read_text(encoding="utf-8"))
        # Even a forged top-level PASS cannot hide the process-level flags.
        state["client"]["timed_out"] = True
        state["client"]["forced_termination"] = True
        self._write_json("orchestration-state.json", state)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", summary["result"])
        self.assertTrue(summary["orchestration"]["client"]["timed_out"])
        self.assertTrue(summary["orchestration"]["client"]["forced_termination"])
        failed_ids = {
            check["id"] for check in summary["checks"] if not check["passed"]
        }
        self.assertIn("orchestration-timeouts", failed_ids)
        self.assertIn("orchestration-forced-termination", failed_ids)

    def test_server_exit_and_unclean_shutdown_cannot_pass(self) -> None:
        state = json.loads((self.input_directory / "orchestration-state.json").read_text(encoding="utf-8"))
        state["server"]["exit_code"] = 9
        state["server"]["clean_shutdown"] = False
        self._write_json("orchestration-state.json", state)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", summary["result"])
        failed_ids = {
            check["id"] for check in summary["checks"] if not check["passed"]
        }
        self.assertIn("orchestration-server-exit", failed_ids)
        self.assertIn("orchestration-clean-shutdown", failed_ids)

    def test_missing_orchestration_state_cannot_pass(self) -> None:
        (self.input_directory / "orchestration-state.json").unlink()

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", summary["result"])
        self.assertIsNone(summary["orchestration"])
        suite = ET.parse(self.output_directory / "junit.xml").getroot()
        self.assertGreater(int(suite.attrib["failures"]), 0)

    def test_missing_required_screenshot_produces_failure_evidence(self) -> None:
        missing = aggregate_evidence.REQUIRED_SCREENSHOTS[8]
        (self.input_directory / "screenshots" / missing).unlink()

        self.assertFalse(self._aggregate())

        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", summary["result"])
        self.assertNotIn(f"screenshots/{missing}", summary["artifacts"])
        failures = ET.parse(self.output_directory / "junit.xml").getroot().findall(".//failure")
        self.assertTrue(any(missing in (failure.attrib.get("message") or "") for failure in failures))

    def test_failed_client_result_is_not_promoted(self) -> None:
        client = json.loads((self.input_directory / "client-state.json").read_text(encoding="utf-8"))
        client["result"] = "failed"
        failed_step = dict(client["steps"][0])
        failed_step.update({"id": "entity", "passed": False, "detail": "timeout",
                            "failure_reason": "timed out waiting for the entity"})
        client["steps"].append(failed_step)
        self._write_json("client-state.json", client)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", summary["result"])
        self.assertGreaterEqual(summary["failed_checks"], 2)

    def test_malformed_scenario_audit_fields_cannot_pass(self) -> None:
        client = json.loads((self.input_directory / "client-state.json").read_text(encoding="utf-8"))
        client["steps"][0]["finished_at"] = "2026-07-17T23:59:59Z"
        client["steps"][0]["actual_input"] = ""
        client["steps"][0]["timeout_ticks"] = 0
        self._write_json("client-state.json", client)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        scenario = next(check for check in summary["checks"] if check["id"] == "client-scenarios")
        self.assertFalse(scenario["passed"])
        self.assertIn("finished_at precedes", scenario["detail"])
        self.assertIn("actual_input is empty", scenario["detail"])
        self.assertIn("timeout_ticks", scenario["detail"])

    def test_item_drop_pickup_scenario_is_required(self) -> None:
        client = json.loads((self.input_directory / "client-state.json").read_text(encoding="utf-8"))
        client["steps"] = [
            step for step in client["steps"] if step["id"] != "item-drop-pickup"
        ]
        self._write_json("client-state.json", client)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        scenario = next(check for check in summary["checks"] if check["id"] == "client-scenarios")
        self.assertFalse(scenario["passed"])
        self.assertIn("item-drop-pickup>=1", scenario["detail"])

    def test_server_must_observe_item_leave_and_return(self) -> None:
        server = json.loads((self.input_directory / "server-state.json").read_text(encoding="utf-8"))
        server["item_pickup_observed"] = False
        server["basic_item_remaining"] = 0
        self._write_json("server-state.json", server)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        observations = next(
            check for check in summary["checks"] if check["id"] == "server-observations"
        )
        self.assertFalse(observations["passed"])
        self.assertIn("item_pickup_observed", observations["detail"])
        self.assertIn("basic_item_remaining=1", observations["detail"])

    def test_production_jar_and_mapping_decisions_are_required(self) -> None:
        server = json.loads((self.input_directory / "server-state.json").read_text(encoding="utf-8"))
        server["production_jar_sha256"] = "not-a-hash"
        del server["mapping_decisions"]["gui:polymc-reborn-gametest:fixture_menu"]
        self._write_json("server-state.json", server)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        failed_ids = {check["id"] for check in summary["checks"] if not check["passed"]}
        self.assertIn("production-jar", failed_ids)
        self.assertIn("server-mapping-decisions", failed_ids)

    def test_polymer_on_client_fails_isolation(self) -> None:
        mods = json.loads((self.input_directory / "loaded-client-mods.json").read_text(encoding="utf-8"))
        mods["mods"].append({"id": "polymer-core", "version": "unexpected"})
        self._write_json("loaded-client-mods.json", mods)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        isolation = next(check for check in summary["checks"] if check["id"] == "client-mod-isolation")
        self.assertFalse(isolation["passed"])
        self.assertIn("polymer-core", isolation["detail"])

    def test_resource_pack_hash_mismatch_is_reported(self) -> None:
        client = json.loads((self.input_directory / "client-state.json").read_text(encoding="utf-8"))
        client["resource_pack_sha256"] = "c" * 64
        self._write_json("client-state.json", client)

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        self.assertFalse(summary["resource_pack"]["consistent"])
        consistency = next(
            check for check in summary["checks"] if check["id"] == "resource-pack-hash-consistency"
        )
        self.assertFalse(consistency["passed"])

    def test_downloaded_sha1_request_count_and_pack_report_hash_are_gated(self) -> None:
        client = json.loads((self.input_directory / "client-state.json").read_text(encoding="utf-8"))
        client["resource_pack_sha1"] = "c" * 40
        self._write_json("client-state.json", client)
        server = json.loads((self.input_directory / "server-state.json").read_text(encoding="utf-8"))
        server["resource_pack_request_count"] = 0
        server["resource_pack_push_count"] = 1
        self._write_json("server-state.json", server)
        report = (self.server_ready.parent / "config" / "polymc-reborn" / "reports"
                  / "resource-pack-latest.json")
        report.write_text(
            json.dumps({"sha256": "e" * 64, "entryCount": 29, "archiveBytes": 4096}),
            encoding="utf-8",
        )

        self.assertFalse(self._aggregate())
        summary = json.loads((self.output_directory / "summary.json").read_text(encoding="utf-8"))
        failed_ids = {check["id"] for check in summary["checks"] if not check["passed"]}
        self.assertIn("server-observations", failed_ids)
        self.assertIn("resource-pack-hash-consistency", failed_ids)
        self.assertIn("operator-reports-contract", failed_ids)

    def test_output_outside_standard_location_is_rejected(self) -> None:
        with self.assertRaises(aggregate_evidence.EvidenceError):
            aggregate_evidence.aggregate(
                self.root,
                self.input_directory,
                self.root / "evidence",
                self.client_log,
                self.server_log,
                self.orchestrator_log,
                self.server_ready,
            )


if __name__ == "__main__":
    unittest.main()
