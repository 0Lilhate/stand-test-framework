# stand-test-bom

**Group:** dependency management · **Gradle plugin:** `java-platform`

Aggregate Bill of Materials (platform) for the stand-test SDK. It will centralise the
versions of all `stand-test-*` modules (and, optionally, curated third-party versions) so
consumers align on a single coordinate.

External test consumers import it as a platform:

```kotlin
testImplementation(platform("ru.alfa.stand.test:stand-test-bom:<version>"))
```

> Skeleton stage: no dependency constraints are declared yet (awaiting an approved dependency
> list). Modules constrained by this BOM must never import it back — that would create a cycle.
