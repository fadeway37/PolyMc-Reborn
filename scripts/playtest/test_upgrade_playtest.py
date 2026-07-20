# SPDX-License-Identifier: LGPL-3.0-or-later
"""Cold-start and fail-closed tests for the RC upgrade orchestrator."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock

import upgrade_playtest as upgrade


class UpgradePlaytestTest(unittest.TestCase):
    def test_server_bootstrap_uses_requested_port_and_accepts_eula(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            server = Path(temporary) / "upgrade server"
            with mock.patch.object(upgrade, "SERVER", server):
                upgrade.write_server_bootstrap(24567)

            self.assertEqual("eula=true\n", (server / "eula.txt").read_text(encoding="utf-8"))
            properties = (server / "server.properties").read_text(encoding="utf-8")
            self.assertIn("server-port=24567\n", properties)
            self.assertIn("online-mode=false\n", properties)

    def test_only_clean_pre_ready_eula_exit_allows_cold_retry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "server.log"
            log.write_text(
                "You need to agree to the EULA in order to run the server.\n",
                encoding="utf-8",
            )

            self.assertTrue(upgrade.cold_bootstrap_retry_allowed(log, 0, False))
            self.assertFalse(upgrade.cold_bootstrap_retry_allowed(log, 1, False))
            self.assertFalse(upgrade.cold_bootstrap_retry_allowed(log, 0, True))

    def test_unrelated_clean_exit_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "server.log"
            log.write_text("Server stopped before fixture readiness.\n", encoding="utf-8")

            self.assertFalse(upgrade.cold_bootstrap_retry_allowed(log, 0, False))


if __name__ == "__main__":
    unittest.main()
