# stand-test-spring-boot-starter

**Group:** integrations · **Gradle plugin:** `java-library`

Aggregator / auto-configuration that wires the SDK modules (core, junit, allure, await and
the adapters) into a Spring test context.

This is a **library**, not a bootable application — the `org.springframework.boot` plugin is
deliberately not applied.

**Planned internal dependencies:** `stand-test-core`, `stand-test-junit`, `stand-test-allure`,
`stand-test-await`, `stand-test-rest`, `stand-test-kafka`, `stand-test-db`, `stand-test-grpc`.

> Skeleton stage: no Spring Boot auto-configuration implemented yet.
