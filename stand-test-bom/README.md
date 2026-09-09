# stand-test-bom

**Group:** dependency management · **Gradle plugin:** `java-platform`

Aggregate Bill of Materials (platform) for the stand-test SDK. It pins the versions of all published
`stand-test-*` modules plus every third-party library those modules carry into a consumer's graph
(spring-webflux, json-path, kafka-clients, grpc/protobuf, allure-java-commons, snakeyaml, slf4j-api,
Playwright), so a consumer aligns on a single coordinate and never spells out a per-module version.

External test consumers import it as a platform:

```kotlin
testImplementation(platform("ru.alfa.stand.test:stand-test-bom:<version>"))
testImplementation("ru.alfa.stand.test:stand-test-junit")   // no explicit version needed
testImplementation("ru.alfa.stand.test:stand-test-rest")
```

Versions are never spelled out in this module: they come from `gradle/libs.versions.toml`, the same
catalog the modules compile against, so the BOM cannot claim a version the SDK was not built with.

## What is deliberately not constrained

| | why |
|---|---|
| Spring Boot | the starter takes it `compileOnly`; the consumer's own Boot BOM or plugin governs the version, and constraining it here would fight them |
| JUnit, AssertJ | the consumer owns its test stack — and `stand-test-junit` already exports `junit-bom` as a platform, so JUnit is aligned there rather than twice |
| H2, ArchUnit, Logback | test-only in this build; they reach no consumer |

## The guard, and the half it does not cover

`verifyBomCoversEveryPublishedModule` (wired into `check`) fails the build if the module constraints and
the set of published subprojects disagree in either direction, naming the modules and the fix. It exists
because this BOM once shipped **empty** — found by the 2026-07 full-library review by reading it, which is
not a way of finding things that scales.

What it does **not** check is the third-party half of the list: whether a new `implementation` dependency
in some adapter was also pinned here. Doing that would mean reaching into other projects' configurations
before Gradle has evaluated them. So that half stays eye-only: **adding a non-test dependency to any
module means adding it here too.** Playwright spent the whole of wave 1 unpinned for exactly this reason.

> Modules constrained by this BOM must never import it back — that would create a `core -> bom -> core`
> cycle. Only external consumers import the platform.
