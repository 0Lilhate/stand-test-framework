# Publishing the stand-test SDK

The build publishes **13 artifacts** — the 12 SDK modules plus `stand-test-bom` (a `java-platform`
carrying only a POM). `stand-test-example` is a test-only showcase and is never published. Every module
artifact ships `.jar` + `-sources.jar` + `-javadoc.jar` (javadoc is generated with doclint disabled).

## Repository configuration

The internal Nexus/Artifactory endpoint is **not hardcoded** — it is supplied per invocation via Gradle
properties, each with an environment-variable fallback (property wins):

| Gradle property | Env fallback | Meaning |
| --- | --- | --- |
| `standTestPublishReleasesUrl` | `STAND_TEST_PUBLISH_RELEASES_URL` | release repository |
| `standTestPublishSnapshotsUrl` | `STAND_TEST_PUBLISH_SNAPSHOTS_URL` | snapshot repository |
| `standTestPublishUrl` | `STAND_TEST_PUBLISH_URL` | fallback for both of the above |
| `standTestPublishUsername` | `STAND_TEST_PUBLISH_USERNAME` | credentials (omit for `file://` repos) |
| `standTestPublishPassword` | `STAND_TEST_PUBLISH_PASSWORD` | credentials |
| `standTestPublishAllowInsecure` | `STAND_TEST_PUBLISH_ALLOW_INSECURE` | `true` permits plain `http://` (in-perimeter Nexus) |

The snapshot/release repository is chosen by the version suffix in `gradle.properties`:
`*-SNAPSHOT` → snapshots URL, otherwise → releases URL (each falling back to the common URL).

Without any URL configured, `./gradlew publish` **fails with a clear message** (instead of silently
publishing nothing — a CI footgun); `./gradlew build` and `./gradlew publishToMavenLocal` never need
these properties.

```bash
# Snapshot to the internal repo
./gradlew publish \
  -PstandTestPublishSnapshotsUrl=https://nexus.internal/repository/maven-snapshots \
  -PstandTestPublishUsername=ci-user -PstandTestPublishPassword=***

# Same via environment (e.g. CI secrets)
STAND_TEST_PUBLISH_URL=https://nexus.internal/repository/maven-snapshots \
STAND_TEST_PUBLISH_USERNAME=ci-user STAND_TEST_PUBLISH_PASSWORD=*** ./gradlew publish

# Local smoke test against a file repository (no credentials needed)
./gradlew publish -PstandTestPublishUrl=file:///tmp/m2repo
```

## Release process

1. Set the release version in `gradle.properties` (`version=0.1.0`), commit.
2. `./gradlew build` — full green build (compile + checkstyle + tests + coverage gate).
3. `./gradlew publish -PstandTestPublishReleasesUrl=… -PstandTestPublishUsername=… -PstandTestPublishPassword=…`
4. Tag: `git tag v0.1.0 && git push --tags`.
5. Bump to the next snapshot (`version=0.2.0-SNAPSHOT`), commit.

Snapshots need no ceremony: leave the `-SNAPSHOT` version in place and run `publish` against the
snapshot repository (step 3 with the snapshots URL).

## Environment-registry format version (a second, slower version number)

The artifact version above is not the only compatibility surface. Consumers share a configuration file
(`stand-test-environments.yml`, or `stand.test.*` in `application.yml`), and that file has its own
**format version** — the root key `version` / `stand.test.version`, whose supported value is the
constant `EnvironmentConfigFormat.SUPPORTED_VERSION` in `stand-test-core`.

Why it exists: the registry loader is fail-closed, so before versioning, a file carrying a section
introduced by a newer SDK failed on an older one with `Unknown field '<section>'` — a message that
says nothing about what to do. With the key, that same file produces "format version N, this SDK
supports up to M — upgrade `stand-test-*`".

**The rule this puts on a release.** A change that adds a section to the registry format must:

1. bump `SUPPORTED_VERSION`, so a document using the new section declares a version an older SDK can
   recognise as newer than its own;
2. be released **after** a version of the SDK that already understands the `version` key — otherwise
   the older SDK still meets `Unknown field` and the promise is void.

Point 2 costs nothing while the SDK is unpublished (no consumer holds a version that would refuse the
key), and it is the reason the key was introduced before the first release rather than after it. Once
published, the ordering is a real constraint on release planning.

| Compatibility question | Answer |
|---|---|
| Old file, new SDK | reads (a file without `version` is format version 1) |
| New file, new SDK | reads |
| New file (higher `version`), old SDK that knows the key | refuses with a version message naming both versions and the action |
| New file, SDK predating the key | `Unknown field 'version'` — the case point 2 above exists to prevent |

## Consumption

External consumers import the BOM once and reference modules without versions:

```kotlin
testImplementation(platform("ru.alfa.stand.test:stand-test-bom:0.1.0"))
testImplementation("ru.alfa.stand.test:stand-test-junit")
testImplementation("ru.alfa.stand.test:stand-test-rest")
```

## Verifying a publication

```bash
./gradlew publishToMavenLocal
ls ~/.m2/repository/ru/alfa/stand/test/            # 13 directories, no stand-test-example
ls ~/.m2/repository/ru/alfa/stand/test/stand-test-core/<version>/   # jar + sources + javadoc + pom
```

For an end-to-end dry run against a real Maven layout (metadata + checksums included), publish to a
`file://` repository as shown above and inspect the tree.
