#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later

set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
cd "$repository_root"

server_log='build/server-smoke/logs/latest.log'
if [[ ! -s "$server_log" ]]; then
    printf 'Dedicated-server smoke test did not produce %s.\n' "$server_log" >&2
    exit 1
fi

if ! grep -Eq 'Done \([^)]*\)! For help' "$server_log"; then
    printf 'Dedicated-server log does not show a completed server startup.\n' >&2
    tail -n 80 "$server_log" >&2
    exit 1
fi

if grep -Ei \
    '(net[./]minecraft[./]client|NoClassDefFoundError.*client|ClassNotFoundException.*client|Could not execute entrypoint.*client)' \
    "$server_log"; then
    printf 'Dedicated-server log contains evidence of client-only class loading.\n' >&2
    exit 1
fi

printf 'Dedicated-server startup log passed server-only class-loading checks.\n'
