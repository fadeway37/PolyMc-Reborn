# 0.4 RC release checklist

This checklist is a gate definition, not a claim that an unexecuted check has
passed. The final release report and GitHub run evidence are authoritative.

- Exact 0.3 Beta base, tag, artifacts, API hashes, and known gaps audited.
- Java 25 clean build, JUnit, API signature/compatibility, GameTest, and
  dedicated-server smoke complete.
- 0.3 binary Consumer and RC Maven-coordinate Consumer pass real client runs.
- Single-client, two-client, pack-policy, three-Mod external matrix,
  0.3-to-RC upgrade, mod-set expansion, short soak, and long soak pass.
- Every final GUI, entity, interaction-proxy, pack, process, port, temporary
  file, and handle count is clean.
- Windows/Linux main and API archives reproduce; SBOM and both checksum files
  verify; release allow-list contains no fixture, driver, third-party JAR,
  world, screenshot, report, local path, or secret.
- The release workflow is registered on the default branch and dispatched from
  the exact candidate ref.
- Positive Artifact Attestation verification passes and the tampered negative
  fails.
- Only then may the annotated tag and a Draft Pre-release be created. Any P0
  failure leaves the branch pushed but blocks both tag and release.
