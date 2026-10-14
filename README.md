# ArcContracts

ArcContracts is the standalone Paper plugin for RusCrafting's contracts and season gameplay. It owns contract gameplay while depending on ARC for host services and service registration.

The runtime and its Java API have independent immutable Maven coordinates:

- Runtime plugin: `ru.ruscrafting:ArcContracts:0.1.2`
- Java API: `ru.ruscrafting.arc:arc-contracts-api:0.1.0`

ARC shades the API once. ArcContracts consumes it as `compileOnly`; add-ons should do the same and must not embed the API or shared provider implementations. Change `contractsApiVersion` before publishing a contract change. Runtime releases do not require an API version change.

## Build and verification

Use JDK 25. Unit tests use the shared Paper test kit. Redis integration tests run against disposable services and require Docker. CI also builds separate Citizens test and crash-recovery fixture plugins; these fixtures are not part of the production JAR. `fixtureVersion` versions their immutable artifacts independently of `pluginVersion` and `contractsApiVersion`.

```bash
./gradlew test
./gradlew integrationTest
./gradlew shadowJar contractTestFixtureJar contractCrashFixtureJar
./scripts/verify-shadow-jar.sh
./scripts/verify-contract-fixtures.sh
```

CI runs the pinned `arc-core` consumer verifier, unit and integration tests, deterministic packaging, and JAR ownership checks. A green push to `main` publishes the runtime JAR/POM, both test-fixture JARs/POMs, and API JAR, sources JAR, POM, and Gradle module metadata to the RusCrafting Maven repository. The publisher checks all immutable coordinates before upload and verifies public readback. There is no GitHub Release step.

## Metrics

ArcContracts requests a 2048-handle metrics scope from `ArcMetricsService`;
the shared default remains 128. The largest snapshot allowed by the current
configuration is 1202 handles: 64 resource views × 12, 16 stages × (2 cash +
1 status), 64 season resource links × 2, 16 bound rewards × 2, 16 dungeon
contracts × 2 money gauges, 29 season-journal gauges, 19 submission-journal
gauges, 1 + 16 × 8 dungeon-observation gauges, 2 season-window gauges, and 15
global policy/state/availability/budget gauges. The remaining 846 handles are
fixed headroom. Snapshot calculation and publication serialize with reload;
unchanged metric keys reuse their handles, while a changed key set closes and
reopens the scope so old status and removed-contract series are removed.

For composite development, set `arcCoreDir` to a local `arc-core` checkout. CI resolves the immutable `2.7.23` release.
