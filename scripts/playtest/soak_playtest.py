# SPDX-License-Identifier: LGPL-3.0-or-later
"""Bounded Beta soak: five complete production client/reconnect lifecycles."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "build" / "playtest" / "soak"


def main() -> int:
    build = (ROOT / "build").resolve()
    if OUTPUT.resolve() == build or build not in OUTPUT.resolve().parents:
        raise RuntimeError("unsafe soak output path")
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    OUTPUT.mkdir(parents=True)
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    iterations: list[dict[str, object]] = []
    failures: list[str] = []
    baseline_mapping = baseline_pack = None
    for index in range(1, 6):
        log = OUTPUT / f"iteration-{index}.log"
        with log.open("w", encoding="utf-8", newline="\n") as stream:
            result = subprocess.run([str(wrapper), "--no-daemon", "--console=plain",
                                     "runProductionClientPlaytest"], cwd=ROOT,
                                    stdout=stream, stderr=subprocess.STDOUT, text=True)
        summary_path = ROOT / "build" / "playtest" / "single-client" / "summary.json"
        server_path = ROOT / "build" / "playtest" / "single-client" / "server-state.json"
        if result.returncode != 0 or not summary_path.is_file() or not server_path.is_file():
            failures.append(f"iteration {index} launcher/evidence failed with {result.returncode}")
            break
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        server = json.loads(server_path.read_text(encoding="utf-8"))
        mapping_hash = server.get("mapping_store_sha256")
        pack_hash = server.get("resource_pack_sha256")
        stable = (baseline_mapping in (None, mapping_hash) and baseline_pack in (None, pack_hash)
                  and server.get("gui_active_sessions") == 0
                  and summary.get("result") == "passed")
        baseline_mapping = mapping_hash if baseline_mapping is None else baseline_mapping
        baseline_pack = pack_hash if baseline_pack is None else baseline_pack
        iterations.append({"iteration": index, "result": summary.get("result"),
                           "mapping_sha256": mapping_hash, "pack_sha256": pack_hash,
                           "gui_active_sessions": server.get("gui_active_sessions"),
                           "entity_projection_sessions": server.get("entity_projection_sessions"),
                           "stable": stable})
        shutil.copytree(ROOT / "build" / "playtest" / "single-client", OUTPUT / f"evidence-{index}")
        if not stable:
            failures.append(f"iteration {index} violated bounded stability/session assertions")
            break
    passed = len(iterations) == 5 and not failures
    summary = {"schema_version": 1, "result": "passed" if passed else "failed",
               "iterations": iterations, "completed_iterations": len(iterations),
               "connection_lifecycles": len(iterations) * 2, "failures": failures,
               "scope": "bounded five-run Beta soak; not the future 10,000-tick/full-stress target"}
    (OUTPUT / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n",
                                         encoding="utf-8")
    print(f"Soak evidence: {OUTPUT} ({summary['result']}, {len(iterations)}/5 iterations)")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
