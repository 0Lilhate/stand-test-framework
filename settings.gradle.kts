
pluginManagement {
  val repoUrl = providers.gradleProperty("binaryPublicRepoUrl").orNull
  if (repoUrl != null) {
    repositories {
      maven {
        name = "alfaArtifactoryPublic"
        url = uri(repoUrl)
        isAllowInsecureProtocol = repoUrl.startsWith("http://")
        credentials {
          username = providers.gradleProperty("artifactoryUser").orNull
          password = providers.gradleProperty("artifactoryPassword").orNull
        }
      }
    }
  }
}

fun Settings.requiredRepositoryProperty(name: String): String =
  providers.gradleProperty(name).orNull
    ?: error(
      """
      Missing Gradle property '$name'.

      This build resolves every dependency and plugin through the internal Alfa Artifactory mirror and has
      no public fallback (mavenCentral / gradlePluginPortal are unreachable from the corporate network), so
      it cannot configure itself without this value.

      Set it in your Gradle User Home — NOT in the repository, these are per-developer settings:
        ~/.gradle/gradle.properties
          binaryPublicRepoUrl=<public proxy repo mirroring Maven Central and the Gradle Plugin Portal>
          artifactoryUrl=<Artifactory base URL>
          artifactorySnapshotRepo=<snapshot repo key>
          artifactoryUser=<your read user>
          artifactoryPassword=<your read token>

      In the IDE these are normally provisioned already; on a fresh machine or a CI agent supply them as
      '-P$name=<value>' or via the environment variable 'ORG_GRADLE_PROJECT_$name'.
      """.trimIndent(),
    )

fun RepositoryHandler.alfaArtifactory(repoName: String, repoUrl: String, repoUser: String?, repoPassword: String?) {
  maven {
    name = repoName
    url = uri(repoUrl)
    val plaintext = repoUrl.startsWith("http://")
    isAllowInsecureProtocol = plaintext
    if (plaintext && (repoUser != null || repoPassword != null)) {
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
val publicRepoUrl = requiredRepositoryProperty("binaryPublicRepoUrl")
val snapshotsRepoUrl = requiredRepositoryProperty("artifactoryUrl").trimEnd('/') +
  "/" + requiredRepositoryProperty("artifactorySnapshotRepo")

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
  "stand-test-scenario-yaml",
  "stand-test-config",
  "stand-test-spring-boot-starter",
  "stand-test-example",
)
