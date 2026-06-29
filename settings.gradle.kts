pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
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
  "stand-test-allure",
  "stand-test-spring-boot-starter",
  "stand-test-scenario-yaml",
  "stand-test-ai-schema"
)
