# Verification guide

PolyMc Reborn uses separate evidence layers. A successful build does not prove
a client scenario, and a successful workflow conclusion does not replace
inspection of its reports and artifacts.

## Evidence layers

| Layer | Boundary | What success can support |
| --- | --- | --- |
| JUnit | In-process logic and temporary files | Determinism, validation, hostile-input, and API behavior |
| Fabric GameTest | Development server plus internal fixture | Registry, lifecycle, and Polymer integration |
| Dedicated-server smoke | Production server source set | Server startup and absence of client-only linkage |
| Client playtest | Independent client and server processes | Named protocol, presentation, input, and reconnect observations |
| Multi-client playtest | Two clients and one server | Session, pack-policy, and disconnect isolation |
| External-mod matrix | Exact hash-locked server mods | Only the named features and versions tested |
| Upgrade and expansion | One persisted world/configuration across versions and mod sets | State preservation and deterministic allocation behavior |
| Short and long soak | Repeated complete scenarios | Cleanup and bounded resource-trend observations |
| Reproducibility | Independent Windows and Linux archives | Byte identity for the compared archive set |
| Release artifact audit | Bounded release directory | Allow-listed files, metadata, hashes, and content isolation |
| GitHub provenance | Hosted workflow identity and subject digest | Repository, workflow, commit, and exact subject association |

The automated client is a real Minecraft 26.1.2 process with minimal Fabric
test modules and an isolated driver. It is not a pure zero-mod client. Pure
zero-mod vanilla automation remains a separate roadmap layer and is not
inferred from the client playtest.

## Result rules

- Record the exact command, exit code, commit, and evidence location.
- Validate structured JSON/JUnit, loaded-mod lists, scenario counts,
  screenshots when required, process exits, timeouts, and cleanup state.
- Treat missing assertions, missing files, forced cleanup, timeout, or a
  non-zero process result as failed or incomplete.
- Keep reports, worlds, screenshots, logs, downloaded third-party mods, and
  machine-specific evidence below ignored build directories.
- Inspect release archives directly; checked-in harness source is never proof
  that the harness ran.
- Describe compatibility only for the exact features and dependency versions
  observed.

## Release records

- [0.4.0 RC1](../releases/0.4.0-rc.1.md)
- [0.4.0 RC2](0.4.0-rc.2.md)

For command details and scenario contracts, see [testing.md](../testing.md),
[soak-testing.md](../soak-testing.md), and
[artifact-attestation.md](../artifact-attestation.md).
