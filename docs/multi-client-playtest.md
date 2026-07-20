# Production multi-client playtest

`runProductionMultiClientPlaytest` starts one production server and two real
Minecraft 26.1.2 Client Driver processes concurrently. A and B have separate
run directories, offline identities, resource caches, reports, and exact
eight-Mod allow-list files. Neither client contains Reborn, Polymer, the server
fixture, API consumer, or tested content Mods.

The server uses `OPTIONAL` pack policy. A accepts; B declines and must observe
only a safe vanilla carrier/model. Both open different server-authoritative GUI
containers at the same time and make different transactions. A sends entity
use, B sends attack. A disconnects, B proves it remains connected, and A then
reconnects and observes one surrogate rather than a duplicate.

On constrained runners A completes its native renderer cold start and waits in
the live online barrier before B is launched. A remains connected while B
starts; all GUI/entity/dimension/disconnect scenarios still execute with both
independent sessions online. This avoids treating simultaneous LWJGL/DataFixer
initialization pressure as multiplayer behavior. The cold-start barrier remains
bounded at ten minutes and does not replace the shared scenario timeout.

Evidence is accepted only when all three processes exit zero without forced
cleanup, exact join/disconnect and pack counters match, both client reports and
Mod lists pass, run roots differ, and the two GUI fingerprints differ. Output
is under `build/playtest/multi-client/`.

The RC regression also requires the accepted client to observe the explicit
property GUI and richer entity composition while the declined client remains
on safe vanilla carriers. Abnormal cleanup is player-scoped: disconnecting A
must not remove B's GUI, entity, or resource-pack state, and final active GUI,
projection, interaction-proxy, and pack-session counts must all be zero. This
is still a two-driver test, not a pure zero-Mod vanilla smoke.
