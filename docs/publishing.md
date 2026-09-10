# Publishing the stand-test SDK

The build publishes **13 artifacts** — the 12 SDK modules plus `stand-test-bom` (a `java-platform`
carrying only a POM). Every module
artifact ships `.jar` + `-sources.jar` + `-javadoc.jar` (javadoc is generated with doclint disabled).

## Repository configuration

Publication is wired by the corporate `ru.alfalab.library-configurer`: it creates one publication per
module (named `artifact`) and one repository (named `alfa`). The endpoint is not in this repository —
it comes from the corporate properties, each of which is read as a Gradle property or an environment
variable of the same name:

| Property / env var | Default | Meaning |
| --- | --- | --- |
| `ARTIFACTORY_HOST` | `https://binary.alfabank.ru` | Artifactory base URL |
| `ARTIFACTORY_USER` | — | publish user |
| `ARTIFACTORY_PASSWORD` | — | publish token |
| `LIBRARY_SNAPSHOT_REPOSITORY` | `libs-snapshot-local` | repo key for `-SNAPSHOT` versions |
| `LIBRARY_RELEASE_REPOSITORY` | `libs-release-local` | repo key for release versions |

Snapshot or release is chosen by the version in `gradle.properties`: `*-SNAPSHOT` → the snapshot repo,
anything else → the release repo. `stand-test-bom` cannot use the configurer (it is a `java-platform`,
and the configurer applies `java-library`), so its own build script mirrors the very same properties —
keep the two in step if the corporate plugin ever changes them.

`./gradlew build` and `./gradlew publishToMavenLocal` need none of this.

```bash
# Snapshot to the internal repo
./gradlew publish -PARTIFACTORY_USER=ci-user -PARTIFACTORY_PASSWORD=***

# Same via environment (e.g. CI secrets)
ARTIFACTORY_USER=ci-user ARTIFACTORY_PASSWORD=*** ./gradlew publish
```

Note what the POMs now carry: the configurer adds the Spring Boot and Spring Cloud BOMs as `api`
platforms to **every** module, so each published POM imports them under `dependencyManagement` —
`stand-test-core` included. No jars come with it (a platform contributes constraints only), but a
consumer resolving our modules inherits those version constraints.

## Release process

1. Set the release version in `gradle.properties` (`version=0.1.0`), commit.
2. `./gradlew build` — full green build (compile + corporate analysis + tests; the analysis reports and
   does not block, and there is no coverage gate).
3. `./gradlew publish -PARTIFACTORY_USER=… -PARTIFACTORY_PASSWORD=…` — the release repo is chosen by the
   version having no `-SNAPSHOT` suffix.
4. Tag: `git tag v0.1.0 && git push --tags`.
5. Bump to the next snapshot (`version=0.2.0-SNAPSHOT`), commit.

Snapshots need no ceremony: leave the `-SNAPSHOT` version in place and run `publish`.

`gradle.properties` keeps the version static on purpose: `ru.alfalab.semantic-version` is applied, but it
honours an explicitly declared `version` and only computes one from git tags when none is set. `./gradlew
printVersion` prints exactly what the CI publish stage would read.

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

1. bump `SUPPORTED_VERSION` and register the section's first version (as `ui-applications` did for
   version 2), so a document using it must declare that version;
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
ls ~/.m2/repository/ru/alfa/stand/test/            # 13 directories
ls ~/.m2/repository/ru/alfa/stand/test/stand-test-core/<version>/   # jar + sources + javadoc + pom
```

For an end-to-end dry run against a real Maven layout (metadata + checksums included), publish to a
`file://` repository as shown above and inspect the tree.
