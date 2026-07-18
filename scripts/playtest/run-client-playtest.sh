#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
set -uo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
input_dir="$project_root/build/run/client-playtest-evidence-input"
evidence_dir="$project_root/build/playtest"
server_run_dir="$project_root/build/run/client-playtest-server"
client_log="$project_root/playtest/client-driver/build/run/client-playtest/logs/latest.log"
server_log="$server_run_dir/logs/latest.log"
ready_file="$server_run_dir/server-ready.json"
stop_file="$server_run_dir/stop.request"

case "$input_dir" in
  "$project_root"/build/run/client-playtest-evidence-input) ;;
  *) echo "Refusing to prepare unsafe input directory: $input_dir" >&2; exit 2 ;;
esac
case "$evidence_dir" in
  "$project_root"/build/playtest) ;;
  *) echo "Refusing to prepare unsafe evidence directory: $evidence_dir" >&2; exit 2 ;;
esac
rm -rf -- "$input_dir" "$evidence_dir"
mkdir -p -- "$input_dir"
orchestrator_log="$input_dir/orchestrator.log"

log_event() {
  local message="$1"
  printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$message" >>"$orchestrator_log"
  printf '%s\n' "$message"
}

rm -f -- "$ready_file" "$stop_file" "$client_log" "$server_log"
server_pid=''
server_grouped=0
client_pid=''
client_grouped=0
server_port=''
pack_port=''
server_started=0
server_readiness_marker=0
server_tcp_ready=0
server_exit_code=''
server_timed_out=0
server_forced_termination=0
server_clean_stop_requested=0
server_clean_shutdown=0
client_started=0
client_exit_code=''
client_timed_out=0
client_forced_termination=0
declare -a orchestration_failure_messages=()

add_failure() {
  orchestration_failure_messages+=("$1")
  log_event "Orchestration failure: $1"
}

process_alive() {
  [[ -n "$1" ]] && kill -0 "$1" 2>/dev/null
}

signal_process_tree() {
  local pid="$1"
  local grouped="$2"
  local signal="$3"
  if [[ "$grouped" -eq 1 ]]; then
    kill "-$signal" -- "-$pid" 2>/dev/null || true
  else
    kill "-$signal" -- "$pid" 2>/dev/null || true
  fi
}

force_stop_process_tree() {
  local pid="$1"
  local grouped="$2"
  signal_process_tree "$pid" "$grouped" TERM
  for _ in $(seq 1 20); do
    process_alive "$pid" || return
    sleep 0.5
  done
  signal_process_tree "$pid" "$grouped" KILL
}

write_orchestration_state() {
  local result='failed'
  if [[ "${#orchestration_failure_messages[@]}" -eq 0 \
      && "$server_started" -eq 1 && "$server_readiness_marker" -eq 1 && "$server_tcp_ready" -eq 1 \
      && "$server_exit_code" == 0 && "$server_timed_out" -eq 0 && "$server_forced_termination" -eq 0 \
      && "$server_clean_stop_requested" -eq 1 && "$server_clean_shutdown" -eq 1 \
      && "$client_started" -eq 1 && "$client_exit_code" == 0 \
      && "$client_timed_out" -eq 0 && "$client_forced_termination" -eq 0 ]]; then
    result='passed'
  fi
  python3 - "$input_dir/orchestration-state.json" "$result" \
    "$server_started" "$server_readiness_marker" "$server_tcp_ready" "$server_exit_code" \
    "$server_timed_out" "$server_forced_termination" "$server_clean_stop_requested" "$server_clean_shutdown" \
    "$client_started" "$client_exit_code" "$client_timed_out" "$client_forced_termination" \
    "${orchestration_failure_messages[@]}" <<'PY'
import json
import os
import sys
from pathlib import Path

target = Path(sys.argv[1])
result = sys.argv[2]
fixed = sys.argv[3:15]
failures = sys.argv[15:]

def flag(value):
    return value == "1"

def exit_code(value):
    return int(value) if value else None

state = {
    "schema_version": 1,
    "result": result,
    "failure_count": len(failures),
    "failures": failures,
    "server": {
        "started": flag(fixed[0]),
        "readiness_marker": flag(fixed[1]),
        "tcp_ready": flag(fixed[2]),
        "exit_code": exit_code(fixed[3]),
        "timed_out": flag(fixed[4]),
        "forced_termination": flag(fixed[5]),
        "clean_stop_requested": flag(fixed[6]),
        "clean_shutdown": flag(fixed[7]),
    },
    "client": {
        "started": flag(fixed[8]),
        "exit_code": exit_code(fixed[9]),
        "timed_out": flag(fixed[10]),
        "forced_termination": flag(fixed[11]),
    },
}
temporary = target.with_name(target.name + ".tmp")
temporary.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
os.replace(temporary, target)
PY
}

stop_client() {
  if [[ -z "$client_pid" ]]; then
    return
  fi
  if process_alive "$client_pid"; then
    client_forced_termination=1
    add_failure 'Production client required forced cleanup after an orchestration failure.'
    force_stop_process_tree "$client_pid" "$client_grouped"
  fi
  wait "$client_pid"
  client_exit_code=$?
  client_pid=''
}

stop_server() {
  if [[ -z "$server_pid" ]]; then
    return
  fi
  if kill -0 "$server_pid" 2>/dev/null; then
    mkdir -p -- "$server_run_dir"
    : >"$stop_file"
    server_clean_stop_requested=1
    log_event 'Requested a clean production server stop.'
    for _ in $(seq 1 240); do
      kill -0 "$server_pid" 2>/dev/null || break
      sleep 0.5
    done
  fi
  if kill -0 "$server_pid" 2>/dev/null; then
    server_timed_out=1
    server_forced_termination=1
    add_failure 'Production server exceeded the 120-second stop timeout and was terminated.'
    force_stop_process_tree "$server_pid" "$server_grouped"
  fi
  wait "$server_pid"
  server_exit_code=$?
  if [[ "$server_exit_code" -ne 0 ]]; then
    add_failure "Production server Gradle process exited with $server_exit_code."
  fi
  if [[ "$server_clean_stop_requested" -eq 1 && "$server_exit_code" -eq 0 \
      && "$server_forced_termination" -eq 0 ]]; then
    server_clean_shutdown=1
  fi
  server_pid=''
}

cleanup() {
  stop_client
  stop_server
}
trap cleanup EXIT

if ! port_values="$(python3 - <<'PY'
import socket
ports = []
while len(ports) < 2:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        if port not in ports:
            ports.append(port)
print(*ports)
PY
)"; then
  add_failure 'Could not allocate loopback playtest ports.'
else
  read -r server_port pack_port <<<"$port_values"
  log_event "Allocated loopback server port $server_port and resource-pack port $pack_port."
fi

if [[ "${#orchestration_failure_messages[@]}" -eq 0 ]]; then
  cd "$project_root" || add_failure 'Could not enter the project root.'
fi
if [[ "${#orchestration_failure_messages[@]}" -eq 0 ]]; then
  if command -v setsid >/dev/null 2>&1; then
    setsid ./gradlew --no-daemon --console=plain \
      "-PplaytestServerPort=$server_port" \
      "-PplaytestPackPort=$pack_port" \
      "-PplaytestReportDir=$input_dir" \
      runProductionServerPlaytest \
      >"$input_dir/server-launcher.stdout.log" 2>"$input_dir/server-launcher.stderr.log" &
    server_grouped=1
  else
    ./gradlew --no-daemon --console=plain \
    "-PplaytestServerPort=$server_port" \
    "-PplaytestPackPort=$pack_port" \
    "-PplaytestReportDir=$input_dir" \
      runProductionServerPlaytest \
      >"$input_dir/server-launcher.stdout.log" 2>"$input_dir/server-launcher.stderr.log" &
  fi
  server_pid=$!
  server_started=1
  log_event "Started independent production server process $server_pid."
fi

ready=0
if [[ "$server_started" -eq 1 ]]; then
  for _ in $(seq 1 1440); do
    if [[ -f "$ready_file" ]]; then
      ready=1
      break
    fi
    if ! process_alive "$server_pid"; then
      break
    fi
    sleep 0.25
  done
fi
if [[ "$server_started" -eq 0 ]]; then
  :
elif [[ "$ready" -eq 0 ]]; then
  if process_alive "$server_pid"; then
    server_timed_out=1
    add_failure 'Timed out waiting for the production server readiness marker.'
  else
    add_failure 'Production server exited before readiness.'
  fi
elif ! ready_values="$(python3 - "$ready_file" "$server_port" <<'PY'
import json
import re
import sys
from pathlib import Path

ready = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_port = int(sys.argv[2])
sha256 = str(ready.get("resource_pack_sha256", "")).lower()
sha1 = str(ready.get("resource_pack_sha1", "")).lower()
if ready.get("server_port") != expected_port:
    raise SystemExit("unexpected server readiness port")
if re.fullmatch(r"[0-9a-f]{64}", sha256) is None or re.fullmatch(r"[0-9a-f]{40}", sha1) is None:
    raise SystemExit("malformed resource-pack hashes in readiness marker")
print(expected_port, sha256, sha1)
PY
)"; then
  add_failure 'Server readiness marker contains an unexpected port or malformed resource-pack hashes.'
else
  read -r ready_port pack_sha256 pack_sha1 <<<"$ready_values"
  server_readiness_marker=1
  log_event 'Production server readiness marker validated.'

  if python3 - "$server_port" "$server_pid" <<'PY'
import os
import socket
import sys
import time

port = int(sys.argv[1])
server_pid = int(sys.argv[2])
deadline = time.monotonic() + 30
while time.monotonic() < deadline:
    try:
        os.kill(server_pid, 0)
    except OSError:
        raise SystemExit(1)
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            raise SystemExit(0)
    except OSError:
        time.sleep(0.25)
raise SystemExit(1)
PY
  then
    server_tcp_ready=1
    log_event 'Production server TCP listener accepted a loopback probe.'
  else
    server_timed_out=1
    add_failure 'Timed out waiting for the production server TCP listener.'
  fi

  if [[ "$server_tcp_ready" -eq 1 ]]; then
    if command -v setsid >/dev/null 2>&1; then
      setsid ./gradlew --no-daemon --console=plain \
        "-PplaytestAddress=127.0.0.1:$server_port" \
        "-PplaytestReportDir=$input_dir" \
        "-PplaytestPackSha256=$pack_sha256" \
        "-PplaytestPackSha1=$pack_sha1" \
        :playtest:client-driver:runIsolatedProductionClientDriver \
        >"$input_dir/client-launcher.stdout.log" 2>"$input_dir/client-launcher.stderr.log" &
      client_grouped=1
    else
      ./gradlew --no-daemon --console=plain \
      "-PplaytestAddress=127.0.0.1:$server_port" \
      "-PplaytestReportDir=$input_dir" \
      "-PplaytestPackSha256=$pack_sha256" \
      "-PplaytestPackSha1=$pack_sha1" \
        :playtest:client-driver:runIsolatedProductionClientDriver \
        >"$input_dir/client-launcher.stdout.log" 2>"$input_dir/client-launcher.stderr.log" &
    fi
    client_pid=$!
    client_started=1
    log_event "Started isolated production client process $client_pid."
    for _ in $(seq 1 1800); do
      process_alive "$client_pid" || break
      sleep 0.5
    done
    if process_alive "$client_pid"; then
      client_timed_out=1
      client_forced_termination=1
      add_failure 'Production client exceeded the 15-minute timeout and was terminated.'
      force_stop_process_tree "$client_pid" "$client_grouped"
    fi
    wait "$client_pid"
    client_exit_code=$?
    client_pid=''
  fi
  if [[ "$client_started" -eq 0 ]]; then
    :
  elif [[ "$client_exit_code" -ne 0 ]]; then
    add_failure "Production client driver exited with $client_exit_code."
  else
    log_event 'Production client driver exited successfully.'
  fi
fi

stop_server
trap - EXIT

write_orchestration_state
log_event 'Aggregating the strict whitelisted evidence bundle.'
python3 "$project_root/scripts/playtest/aggregate_evidence.py" \
  --project-root "$project_root" \
  --input-dir "$input_dir" \
  --output-dir "$evidence_dir" \
  --client-log "$client_log" \
  --server-log "$server_log" \
  --orchestrator-log "$orchestrator_log" \
  --server-ready "$ready_file"
aggregate_exit_code=$?

if [[ "${#orchestration_failure_messages[@]}" -ne 0 || "$aggregate_exit_code" -ne 0 ]]; then
  echo "Production client playtest failed; inspect build/playtest/summary.md and junit.xml." >&2
  exit 1
fi
log_event 'Production client playtest and evidence validation passed.'
echo 'Evidence bundle: build/playtest'
