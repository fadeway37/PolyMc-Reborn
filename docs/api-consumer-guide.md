# External API consumer guide

Resolve the RC API from the publication repository as a compile-only
dependency; the server's PolyMc Reborn Mod supplies the same API classes at
runtime:

```groovy
dependencies {
    compileOnly 'io.github.polymcreborn:polymc-reborn-api:0.4.0-rc.1+26.1.2'
}
```

Declare a server-only Mod dependency on exact Minecraft 26.1.2 and a compatible
PolyMc Reborn version. Implement `PolyMcRebornEntrypoint` and register through
the `polymc-reborn` Fabric entrypoint during initialization. Providers return
explainable candidates; they do not mutate registries during evaluation.
Resource contributors receive a path-validating sink and must supply
deterministic bytes.

The repository's independent `playtest/api-consumer` fixture demonstrates a
provider, explicit item/block decisions, GUI/entity adapters, and resource
contribution. Its build does not include the `api` project. These commands
publish to an ignored temporary Maven repository and then build the consumer:

```text
./gradlew publishApiToTestRepository
./gradlew buildApiConsumer
./gradlew runApiConsumerPlaytest
./gradlew runLegacyApiConsumerPlaytest
```

The fixture JAR is test-only. It must never enter the main Mod, API artifact,
or client runtime.
