# RC soak testing

`runSoakPlaytest` executes five complete production server/client iterations.
`runWindowsSoakPlaytest` and `runLinuxSoakPlaytest` are platform-guarded aliases.
`runLongSoakPlaytest` executes ten complete iterations and enables bounded
stress inside each real isolated client session.

The orchestrator writes its live handles under `build/soak-orchestrator/`,
outside the `build/playtest/` tree cleaned by the nested client run. Evidence
is staged per iteration, copied only after child processes exit, and finally
published beneath `build/playtest/soak-runs/<run-id>/`. A failed iteration is
never converted to success by forced cleanup. The completed sanitized snapshot
is also copied to `build/playtest/soak/` for CI artifact upload; no live writer
targets either published directory.

Each short iteration covers server/client startup, two connections, required
pack application, mapped item/block operations, GUI transactions, entity
use/attack, support bundle, mapping dry run, normal shutdown, stable mapping
and pack hashes, dynamic-port release, process cleanup, temporary-file scan,
and rename probes for closed handles.

Each long iteration adds 25 projected GUI open/close and stale-claim full
resync cycles, 50 explicit projection spawn/despawn cycles, 10 tracking
leave/enter cycles, and a second disconnect/reconnect. Ten iterations therefore
must record at least 250 GUI/resync operations, 500 projection cycles, 100
tracking cycles, 20 reconnects, 30 pack sessions, 30 support bundles, 30
mapping dry runs, and 10,000 aggregate server ticks.

`resource-trends.json` records final heap, Linux RSS, threads, Linux open file
descriptors, mapping-cache size, GC count, and average/maximum rejected
transaction latency. Unsupported platform metrics are `-1`. The leak heuristic
ignores two warm-up samples and fails only monotonic growth beyond a documented
JVM-noise allowance; zero active session/projection/proxy/pack counts, process
cleanup, port release, and file-handle probes remain absolute requirements.
