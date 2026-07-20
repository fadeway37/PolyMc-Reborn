# SPDX-License-Identifier: LGPL-3.0-or-later
"""Identity tests for the immutable 0.3 API Consumer input."""

from __future__ import annotations

import copy
import os
import unittest
from unittest.mock import patch

import legacy_api_consumer_playtest as legacy


class LegacyApiConsumerInputTest(unittest.TestCase):
    def metadata(self) -> dict[str, object]:
        return {
            "id": legacy.ARTIFACT_ID,
            "name": legacy.ARTIFACT_NAME,
            "digest": legacy.ARTIFACT_DIGEST,
            "expired": False,
            "expires_at": "2026-08-02T20:38:23Z",
            "workflow_run": {
                "id": legacy.ARTIFACT_RUN_ID,
                "head_sha": legacy.BASE_COMMIT,
            },
        }

    def test_exact_audited_artifact_is_accepted(self) -> None:
        metadata = self.metadata()

        self.assertIs(metadata, legacy.validate_actions_artifact(metadata))

    def test_different_baseline_commit_is_rejected(self) -> None:
        metadata = copy.deepcopy(self.metadata())
        metadata["workflow_run"]["head_sha"] = "0" * 40

        with self.assertRaisesRegex(RuntimeError, "head_sha"):
            legacy.validate_actions_artifact(metadata)

    def test_expired_artifact_is_rejected_for_source_fallback(self) -> None:
        metadata = self.metadata()
        metadata["expired"] = True

        with self.assertRaisesRegex(RuntimeError, "expired"):
            legacy.validate_actions_artifact(metadata)

    def test_archive_digest_mismatch_is_rejected(self) -> None:
        metadata = self.metadata()
        metadata["digest"] = "sha256:" + "0" * 64

        with self.assertRaisesRegex(RuntimeError, "digest"):
            legacy.validate_actions_artifact(metadata)

    def test_source_reproduction_pins_the_audited_git_identity(self) -> None:
        with patch.dict(os.environ, {"GITHUB_SHA": "0" * 40}):
            environment = legacy.audited_build_environment()

        self.assertEqual(legacy.BASE_COMMIT, environment["GITHUB_SHA"])


if __name__ == "__main__":
    unittest.main()
