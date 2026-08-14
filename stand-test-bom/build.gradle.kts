plugins {
  `java-platform`
  `maven-publish`
}

dependencies {
  constraints {
    api(project(":stand-test-core"))
    api(project(":stand-test-await"))
    api(project(":stand-test-junit"))
    api(project(":stand-test-rest"))
    api(project(":stand-test-kafka"))
    api(project(":stand-test-db"))
    api(project(":stand-test-grpc"))
    api(project(":stand-test-ui"))
    api(project(":stand-test-allure"))
    api(project(":stand-test-scenario-yaml"))
    api(project(":stand-test-config"))
    api(project(":stand-test-spring-boot-starter"))

    api(libs.slf4j.api)
    api(libs.spring.webflux)
    api(libs.json.path)
    api(libs.kafka.clients)
    api(libs.grpc.api)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    api(libs.grpc.services)
    api(libs.grpc.netty.shaded)
    api(libs.protobuf.java)
    api(libs.protobuf.java.util)
    api(libs.allure.java.commons)
    api(libs.snakeyaml)
    api(libs.playwright)
  }
}

val publishedModules: List<String> = rootProject.subprojects
  .map { it.name }
  .filter { it != project.name && it != "stand-test-example" }
  .sorted()

val constrainedModules: List<String> = configurations.getByName("api").dependencyConstraints
  .filter { it.group == project.group.toString() }
  .map { it.name }
  .sorted()

val verifyBomCoversEveryPublishedModule by tasks.registering {
  description = "Fails if the BOM's module constraints and the set of published subprojects disagree."
  val expected = publishedModules
  val declared = constrainedModules
  inputs.property("publishedModules", expected)
  inputs.property("constrainedModules", declared)
  doLast {
    val missing = expected - declared.toSet()
    val unknown = declared - expected.toSet()
    if (missing.isNotEmpty() || unknown.isNotEmpty()) {
      throw GradleException(
        buildString {
          append("stand-test-bom does not match the published modules.")
          if (missing.isNotEmpty()) {
            append("\n  Published but NOT constrained (a consumer would have to spell out a version): ")
            append(missing.joinToString())
            append("\n  Fix: add `api(project(\":<module>\"))` to the constraints block.")
          }
          if (unknown.isNotEmpty()) {
            append("\n  Constrained but not a published module: ")
            append(unknown.joinToString())
            append("\n  Fix: remove it from the constraints block.")
          }
        },
      )
    }
  }
}

tasks.named("check") {
  dependsOn(verifyBomCoversEveryPublishedModule)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["javaPlatform"])
      pom {
        name.set(project.name)
        description.set("Bill of materials for the stand-test SDK: aligned versions of every published module plus the curated third-party libraries the adapters are tested against")
      }
    }
  }
}
