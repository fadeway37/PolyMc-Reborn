# SPDX-License-Identifier: LGPL-3.0-or-later
"""Negative and cross-platform tests for the RC soak orchestrator."""

from __future__ import annotations

import json
import os
import socket
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import soak_playtest as soak


class SoakPlaytestTest(unittest.TestCase):
    def test_control_output_survives_nested_playtest_clean(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            build = Path(temporary) / "build"
            control = build / "soak-orchestrator" / "run"
            nested = build / "playtest"
            control.mkdir(parents=True)
            nested.mkdir(parents=True)
            marker = control / "orchestrator.log"
            marker.write_text("open outside nested output\n", encoding="utf-8")

            soak._reset(nested / "single-client", nested, "nested output")

            self.assertEqual("open outside nested output\n", marker.read_text(encoding="utf-8"))

    def test_atomic_writers_close_handles_and_replace_content(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            document = root / "证据 with spaces" / "summary.json"
            soak._write_json(document, {"iteration": 1})
            soak._write_json(document, {"iteration": 2})

            self.assertEqual({"iteration": 2}, json.loads(document.read_text(encoding="utf-8")))
            self.assertFalse(document.with_name("summary.json.tmp").exists())
            self.assertTrue(soak._rename_probe(document.parent))

    def test_long_unicode_path_is_supported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root.joinpath(*(["很长的-soak-路径"] * 8), "summary.md")
            soak._write_text(target, "稳定\n")

            self.assertEqual("稳定\n", target.read_text(encoding="utf-8"))
            self.assertTrue(soak._within(target.resolve(), root.resolve()))

    def test_occupied_directory_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "occupied"
            target.mkdir()
            with mock.patch.object(soak.shutil, "rmtree", side_effect=PermissionError("in use")):
                with self.assertRaisesRegex(PermissionError, "in use"):
                    soak._reset(target, parent, "occupied test directory")

    def test_reset_is_reentrant_and_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "run"
            soak._reset(target, parent, "run")
            (target / "old.txt").write_text("old", encoding="utf-8")

            soak._reset(target, parent, "run")

            self.assertTrue(target.is_dir())
            self.assertFalse((target / "old.txt").exists())
            with self.assertRaisesRegex(soak.SoakFailure, "unsafe"):
                soak._reset(parent, parent, "parent")

    @unittest.skipUnless(hasattr(os, "symlink"), "symbolic links are unavailable")
    def test_symbolic_link_escape_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary) / "parent"
            outside = Path(temporary) / "outside"
            parent.mkdir()
            outside.mkdir()
            link = parent / "link"
            try:
                link.symlink_to(outside, target_is_directory=True)
            except OSError as exception:
                self.skipTest(f"symbolic links are not permitted: {exception}")

            with self.assertRaisesRegex(soak.SoakFailure, "unsafe|symbolic link"):
                soak._safe_child(link, parent, "link")

    def test_port_probe_changes_from_bound_to_released(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
            self.assertFalse(soak._port_is_released(port))

        self.assertTrue(soak._port_is_released(port))

    def test_failed_iteration_junit_preserves_failure(self) -> None:
        output = soak._aggregate_junit([], ["child process exited abnormally"])
        self.assertIn(b'failures="1"', output)
        self.assertIn(b"child process exited abnormally", output)


if __name__ == "__main__":
    unittest.main()
