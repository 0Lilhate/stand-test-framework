// All dependency and plugin resolution goes through the internal Alfa Artifactory mirror, NOT public
// mavenCentral()/gradlePluginPortal() — those hosts are not reachable from the corporate network and
// caused `Connection ...` failures. There is deliberately no public fallback: a silent fall-through to
// Maven Central would either hang on an unreachable host or resolve artefacts the org has not vetted.
//
// Nothing here is hardcoded. URLs and credentials come from the org-standard properties in every
// developer's ~/.gradle/gradle.properties (Gradle User Home, configured via the IDE):
//   binaryPublicRepoUrl     — full URL of the public repo (proxies Maven Central + Gradle Plugin Portal)
//   artifactoryUrl          — Artifactory base, joined with the repo key below for the snapshots URL
//   artifactorySnapshotRepo — snapshot repo key (e.g. tksc-maven-snapshots)
//   artifactoryUser / artifactoryPassword — read credentials. NEVER commit these.

// NOTE ON STRUCTURE: `pluginManagement { }` is compiled in ISOLATION by the Kotlin DSL — it cannot see
// anything else declared in this file, which is why the helpers below are repeated in miniature inside
// it. Keep the two wordings in step; there is no way to share them.

pluginManagement {
  repositories {
    maven {
      name = "alfaArtifactoryPublic"
      val repoUrl = providers.gradleProperty("binaryPublicRepoUrl").orNull
        ?: error(
          """
          Missing Gradle property 'binaryPublicRepoUrl' (the public proxy repo that mirrors Maven Central and the Gradle Plugin Portal).

          This build resolves every dependency and plugin through the internal Alfa Artifactory mirror and
          has no public fallback, so it cannot configure itself without this value.

          Set it in your Gradle User Home (NOT in the repository — these are per-developer settings):
            ~/.gradle/gradle.properties
              binaryPublicRepoUrl=<full URL of the public proxy repo>
              artifactoryUrl=<Artifactory base URL>
              artifactorySnapshotRepo=<snapshot repo key>
              artifactoryUser=<your read user>
              artifactoryPassword=<your read token>

          In the IDE these are normally provisioned already; on a fresh machine or a CI agent supply them
          as '-PbinaryPublicRepoUrl=<value>' or via the environment variable
          'ORG_GRADLE_PROJECT_binaryPublicRepoUrl'.
          """.trimIndent(),
        )
      url = uri(repoUrl)
      isAllowInsecureProtocol = repoUrl.startsWith("http://")
      credentials {
        username = providers.gradleProperty("artifactoryUser").orNull
        password = providers.gradleProperty("artifactoryPassword").orNull
      }
    }
  }
}

/**
 * Reads a required repository-coordinate property, failing with an actionable message instead of the
 * bare "No value has been specified" that `Provider.get()` throws.
 *
 * Why this matters: these properties are read while the SETTINGS script is evaluated, before any task
 * exists, so a missing one aborts the build with a stack trace and no hint about which property, where
 * it belongs, or that the value is expected to come from Gradle User Home rather than the repository.
 */
fun Settings.requiredRepositoryProperty(name: String, purpose: String): String =
  providers.gradleProperty(name).orNull
    ?: error(
      """
      Missing Gradle property '$name' ($purpose).

      This build resolves every dependency and plugin through the internal Alfa Artifactory mirror and
      has no public fallback, so it cannot configure itself without this value.

      Set it in your Gradle User Home (NOT in the repository — these are per-developer settings):
        ~/.gradle/gradle.properties
          binaryPublicRepoUrl=<full URL of the public proxy repo>
          artifactoryUrl=<Artifactory base URL>
          artifactorySnapshotRepo=<snapshot repo key>
          artifactoryUser=<your read user>
          artifactoryPassword=<your read token>

      In the IDE these are normally provisioned already; on a fresh machine or a CI agent supply them as
      '-P$name=<value>' or via the environment variable 'ORG_GRADLE_PROJECT_$name'.
      """.trimIndent(),
    )

/**
 * Configures one Artifactory repository.
 *
 * `isAllowInsecureProtocol` is derived from the URL rather than pinned to `true`. The previous blanket
 * `true` covered the https snapshots repo as well, which meant a future downgrade of that URL to http —
 * or a typo — would have been accepted silently while shipping the credentials below in cleartext. Now
 * only a repository that is actually plaintext gets the opt-in, and it says so on every configuration.
 */
fun RepositoryHandler.alfaArtifactory(repoName: String, repoUrl: String, repoUser: String?, repoPassword: String?) {
  maven {
    name = repoName
    url = uri(repoUrl)
    val plaintext = repoUrl.startsWith("http://")
    isAllowInsecureProtocol = plaintext
    if (plaintext && (repoUser != null || repoPassword != null)) {
      // HTTP Basic over cleartext: the credentials are on the wire in base64 on every fetch, readable by
      // anything between this machine and the mirror. Nothing here can fix that — the URL is supplied by
      // the org — so make it visible rather than silent, and treat those credentials as low-trust
      // (read-only mirror access, rotated like any other shared secret). The fix is an https endpoint.
      logger.warn("stand-test-framework: repository '$repoName' is plaintext http:// and is being sent Basic credentials. Ask for an https endpoint for '$repoUrl'.")
    }
    credentials {
      username = repoUser
      password = repoPassword
    }
  }
}

val artifactoryUser: String? = providers.gradleProperty("artifactoryUser").orNull
val artifactoryPassword: String? = providers.gradleProperty("artifactoryPassword").orNull
val publicRepoUrl = requiredRepositoryProperty("binaryPublicRepoUrl", "the public proxy repo that mirrors Maven Central and the Gradle Plugin Portal")
val snapshotsRepoUrl = buildString {
  append(requiredRepositoryProperty("artifactoryUrl", "the Artifactory base URL").trimEnd('/'))
  append('/')
  append(requiredRepositoryProperty("artifactorySnapshotRepo", "the snapshot repo key joined onto the Artifactory base URL"))
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    alfaArtifactory("alfaArtifactoryPublic", publicRepoUrl, artifactoryUser, artifactoryPassword)
    alfaArtifactory("alfaArtifactorySnapshots", snapshotsRepoUrl, artifactoryUser, artifactoryPassword)
  }
}

rootProject.name = "stand-test-framework"

include(
  "stand-test-bom",
  "stand-test-core",
  "stand-test-await",
  "stand-test-junit",
  "stand-test-rest",
  "stand-test-kafka",
  "stand-test-db",
  "stand-test-grpc",
  "stand-test-ui",
  "stand-test-allure",
  "stand-test-example",
  "stand-test-spring-boot-starter",
  "stand-test-scenario-yaml",
  "stand-test-ai-schema",
  "stand-test-config"
)
