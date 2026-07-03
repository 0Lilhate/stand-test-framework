# stand-test-bom

**Group:** dependency management · **Gradle plugin:** `java-platform`

Aggregate Bill of Materials (platform) for the stand-test SDK. It centralises the versions of all
published `stand-test-*` modules plus the curated third-party libraries the adapters are built and
tested against (spring-webflux, json-path, kafka-clients, grpc/protobuf, allure-java-commons,
snakeyaml), so consumers align on a single coordinate and never spell out per-module versions.

External test consumers import it as a platform:

```kotlin
testImplementation(platform("ru.alfa.stand.test:stand-test-bom:<version>"))
testImplementation("ru.alfa.stand.test:stand-test-junit")   // no explicit version needed
testImplementation("ru.alfa.stand.test:stand-test-rest")
```

Deliberately **not** constrained: this build's test-only libraries (H2, networknt/jackson,
JUnit/AssertJ — the consumer owns its test stack) and Spring Boot (governed by the consumer's own
Boot BOM/plugin). `stand-test-example` is test-only and not published, so it is not listed.

> Modules constrained by this BOM must never import it back — that would create a cycle. Only
> external consumers import the platform.
