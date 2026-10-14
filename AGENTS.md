# ArcContracts repository guidance

- Keep the ArcContracts runtime plugin and `arc-contracts-api` independently versioned.
- ARC owns and shades `ru.arc.contracts.api`; ArcContracts compiles against the API but must not package it.
- Do not depend on the full ARC plugin JAR. Use only public `arc-core` modules and server-provided APIs required by this plugin.
- Keep Paper, Citizens, RedisEconomy, Vault, and other server-provided plugin APIs `compileOnly`; do not shade those classes.
- Do not package ARC-owned `ru.arc.paper.api` contracts, shared SQL/Redis providers, or the Prometheus registry/exporter runtime. Keep the production JAR verifier in CI.
- Keep every `arc-core` coordinate on the single version in `arc-core-consumer.toml`.
- API coordinates are immutable. Bump `contractsApiVersion` for a public API change; runtime plugin releases can advance independently.
- Contract test and crash fixtures are separate test-only plugins. Keep their descriptors, artifacts, and package out of the production JAR.
- Integration tests use disposable Redis services and Docker. CI publishes only after the architecture verifier, unit tests, integration tests, and deterministic JAR checks pass.

Use JDK 25. Do not publish from local builds. Main-branch CI is the only publisher and compares every existing Maven file before uploading any missing file, then verifies anonymous readback checksums.
