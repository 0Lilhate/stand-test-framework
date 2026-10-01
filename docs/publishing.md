# Publishing the stand-test SDK

The build publishes **15 artifacts** — the 14 SDK modules plus `stand-test-bom` (a `java-platform`
carrying only a POM). Every module
artifact ships `.jar` + `-sources.jar` + `-javadoc.jar` (javadoc is generated with doclint disabled).
`stand-test-http` also has Gradle test fixtures for SDK tests; their variants are excluded from its Maven publication.
`stand-test-eq` implements the confirmed showcases organisation slice (executor registered through both
SPI and the starter); its `gateway` backend and individuals remain fail-closed, so do not release this
working tree as a fully consumer-ready EQ SDK.

## Repository configuration

Publication is wired by the corporate `ru.alfalab.library-configurer`: it creates one publication per
module (named `artifact`) and one repository (named `alfa`). The endpoint is not in this repository —
it comes from the corporate settings below.

| Setting | Default | Meaning |
| --- | --- | --- |
| `ARTIFACTORY_HOST` | `https://binary.alfabank.ru` | Artifactory base URL; the repository URL is `$host/artifactory/$repo` |
| `ARTIFACTORY_USER` | — | publish user |
| `ARTIFACTORY_PASSWORD` | — | publish password or token |
| `LIBRARY_SNAPSHOT_REPOSITORY` | `libs-snapshot-local` | repo key for `-SNAPSHOT` versions |
| `LIBRARY_RELEASE_REPOSITORY` | `libs-release-local` | repo key for release versions |

Snapshot or release is chosen by the version the build carries: `*-SNAPSHOT` → the snapshot repo,
anything else → the release repo. `stand-test-bom` cannot use the configurer (it is a `java-platform`,
and the configurer applies `java-library`), so its own build script mirrors the very same settings —
keep the two in step if the corporate plugin ever changes them.

`./gradlew build` and `./gradlew publishToMavenLocal` need none of this.

### Pass them as environment variables, not as `-P`

**A `-PARTIFACTORY_USER=…` on the command line is silently ignored by the 14 SDK modules.** The configurer
reads every one of these settings through `PropertyUtils` in `ru.alfalab.gradle:base`:

```groovy
static Provider<String> globalProperty(ProviderFactory providers, String name) {
    return providers.environmentVariable(name.toUpperCase())
            .orElse(providers.gradleProperty(name.toLowerCase()))   // ← lowercase
}
```

So a *Gradle property* only counts when it is spelled in **lower case** (`artifactory_user`), while
`stand-test-bom/build.gradle.kts` reads its own `corporateProperty("ARTIFACTORY_USER")` in **upper
case**. No single `-P` spelling reaches all 15 artifacts; an **environment variable** (upper case)
does, and that is the one way to spell it that is right everywhere.

```bash
export ARTIFACTORY_HOST=https://binary.alfabank.ru
export LIBRARY_SNAPSHOT_REPOSITORY=libs-snapshot-local
export LIBRARY_RELEASE_REPOSITORY=libs-release-local
export ARTIFACTORY_USER=ci-user
read -rs ARTIFACTORY_PASSWORD && export ARTIFACTORY_PASSWORD   # keeps it out of the shell history

./gradlew publish --console=plain
```

### Which repository can be published to

The deploy target must be a **local** repository. `.../artifactory/public/` — the URL most consumers
have in their settings — is a **virtual** repository aggregating ~57 others, and a virtual repository
without a default deployment repository refuses a PUT. It is a read address, not a publish address.

`libs-snapshot-local` / `libs-release-local`, the configurer's defaults, are members of both `public`
and `maven-secure` (the repository this build itself resolves from), so publishing there needs no
change on the consumer side. A team-local repository such as `tksc-maven-snapshots` works too, but it
is in neither aggregate, so consumers would have to declare it explicitly:

```kotlin
repositories { maven { url = uri("https://binary.alfabank.ru/artifactory/tksc-maven-snapshots") } }
```

`binary.alfabank.ru` and `binary.moscow.alfaintra.net` are two names for the same instance; both serve
https. The configurer sets `allowInsecureProtocol = true`, so a plain-`http` host also works, but
prefer https.

### When `publish` fails at the first PUT

**`401`** — the credentials were rejected. Check that the user and password actually reach Gradle: a
trailing space or a `\r` on a `gradle.properties` line is enough to break one.

**`403`** — the login succeeded and the account simply lacks the deploy right on that repository or
path. Ask the Artifactory owners for Deploy/Cache on `ru/alfa/stand/test/**` in the target repository,
or publish from CI under its own account.

A quick way to tell the two apart without running the build: `curl -u user:pass` any Artifactory API
endpoint. `401` means the login itself failed; `403` means it succeeded and the account merely lacks
the right. Nothing is written on a `403`, so a failed run leaves no partial upload behind.

Note what the POMs now carry: the configurer adds the Spring Boot and Spring Cloud BOMs as `api`
platforms to **every** module, so each published POM imports them under `dependencyManagement` —
`stand-test-core` included. No jars come with it (a platform contributes constraints only), but a
consumer resolving our modules inherits those version constraints.

## The version is computed, not declared

`gradle.properties` carries **no `version`**, and that is deliberate: `ru.alfalab.semantic-version`
honours an explicitly declared version and only computes one from git tags when none is set — so
declaring it switched the plugin off. Two tasks read the result:

| Task | Prints | Used for |
| --- | --- | --- |
| `./gradlew printVersion` | `0.1.0-<branch>-SNAPSHOT` | snapshots; this is what the CI publish stage reads |
| `./gradlew printReleaseVersion` | `0.1.0` | the release number the next tag would carry |

The snapshot is **branch-qualified** (on `target-solution` it is `0.1.0-target.solution-SNAPSHOT`), so
two branches publish under two coordinates instead of overwriting one another. The base number comes
from the git tags: with no release tag yet the plugin starts at `0.1.0`.

## Release process

1. `./gradlew build` — full green build (compile + corporate analysis + tests). The analysis is a gate:
   `strict = true`, so any checkstyle or SpotBugs finding fails the build. There is no coverage gate.
2. `./gradlew printReleaseVersion` — confirm the number the release will carry.
3. Tag it: `git tag -a 0.1.0 -m 'Release 0.1.0' && git push origin 0.1.0`. The tag is what makes the
   version a release; nothing is edited in `gradle.properties`.
4. `./gradlew publish` from the tagged commit, with the credentials exported as environment
   variables (see above) — the release repo is chosen by the version having no `-SNAPSHOT` suffix.

Snapshots need no ceremony at all: publish from any branch and the coordinates carry the branch name.

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
ls ~/.m2/repository/ru/alfa/stand/test/            # 15 directories
ls ~/.m2/repository/ru/alfa/stand/test/stand-test-core/<version>/   # jar + sources + javadoc + pom
```

For an end-to-end dry run against a real Maven layout (metadata + checksums included), publish to a
`file://` repository as shown above and inspect the tree.
