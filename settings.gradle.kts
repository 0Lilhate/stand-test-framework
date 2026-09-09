pluginManagement {
  repositories {
    maven {
      name = "maven-secure"
      url = uri("https://binary.alfabank.ru/artifactory/maven-secure")
    }
  }
}

dependencyResolutionManagement {
  repositories {
    maven {
      name = "maven-secure"
      url = uri("https://binary.alfabank.ru/artifactory/maven-secure")
    }
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
)
