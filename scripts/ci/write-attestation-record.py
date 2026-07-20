# SPDX-License-Identifier: LGPL-3.0-or-later
"""Create a bounded record from real `gh attestation verify` output."""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verification", type=Path, required=True)
    parser.add_argument("--negative-stderr", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--bundle-url", required=True)
    args = parser.parse_args()
    verified = [json.loads(line) for line in args.verification.read_text(encoding="utf-8").splitlines()
                if line.strip()]
    negative = args.negative_stderr.read_text(encoding="utf-8", errors="replace")
    if len(verified) != 5 or not negative.strip():
        raise SystemExit("attestation verification evidence is incomplete")
    document = {
        "schema_version": 1,
        "result": "passed",
        "repository": args.repository,
        "commit": args.commit,
        "verified_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "bundle_url": args.bundle_url,
        "positive_subject_count": len(verified),
        "positive_verifications": verified,
        "tampered_negative_result": "rejected",
        "tampered_negative_stderr": negative[-4096:],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + ".tmp")
    temporary.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
