# Release Candidate API stability

The published coordinate is:

```text
io.github.polymcreborn:polymc-reborn-api:0.4.0-rc.1+26.1.2
```

It targets Java 25 and official Minecraft 26.1.2 names. It does not contain a
Polymer backend, commands, Mixins, client code, test fixtures, or caches.

## Stability levels

- `@Stable`: preserved from the 0.3 Beta baseline through the 0.4 RC. A breaking change requires
  deprecation where possible, migration notes, changelog, and an accepted
  signature-baseline update.
- `@Experimental`: public and usable, but may change between Betas with
  documented migration. The explicit GUI/entity contracts currently use this
  level because their interaction surface is still being hardened.
- `@Internal`: not an external contract and excluded from the signature
  baseline.
- `LEGACY_ADAPTED_DEPRECATED`: selected old package names for extensions ported
  and recompiled for 26.1.2. They are not binary compatible with old Minecraft
  releases.

The immutable compatibility baseline is `api/signatures/0.3.0-beta.1.txt`; the
exact RC signature is `api/signatures/0.4.0-rc.1.txt`. Run:

```text
./gradlew :api:build
./gradlew :api:checkApiSignature
./gradlew :api:checkApiCompatibility
```

`checkApiSignature` does not silently update either baseline. Maintainers may run
`:api:updateApiSignatureBaseline` only with the required compatibility review.
The signature generator and its negative verification test are build-only and
are absent from the API JAR.
