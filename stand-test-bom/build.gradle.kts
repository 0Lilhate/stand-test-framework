plugins {
  `java-platform`
  `maven-publish`
}

// stand-test-bom — the SDK's aggregate platform; see README.md. Two rules that are not visible from the
// code below:
//
//  - a module constrained here must NOT import the platform back (`core -> bom -> core` is a cycle).
//    Only EXTERNAL consumers import it.
//  - versions come from the same catalog the modules compile against, never spelled out here, so the
//    BOM cannot drift from what the SDK was actually built with.

dependencies {
  constraints {
    // Every published module. stand-test-example is test-only and stand-test-bom is this project;
    // `verifyBomCoversEveryPublishedModule` below fails the build if this list and the set of published
    // subprojects ever disagree.
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

    // Every third-party library a published module carries into a consumer's graph, at the version the
    // SDK was built and tested against. `implementation` deps belong here as much as `api` ones: the
    // consumer still resolves them at runtime, and an unpinned runtime version is exactly the drift a
    // BOM exists to prevent.
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
    // Playwright's version decides which browser binaries the bundled driver downloads, so a consumer
    // resolving a different one than stand-test-ui was tested against gets a driver/browser mismatch —
    // the most expensive kind of drift in this list, and the reason it is pinned rather than left to
    // whatever the graph settles on.
    api(libs.playwright)

    // Deliberately absent. Spring Boot: the starter takes it `compileOnly` and the consumer's own Boot
    // BOM or plugin governs that version — constraining it here would fight them. JUnit and AssertJ: the
    // consumer owns its test stack, and stand-test-junit already exports `junit-bom` as a platform, so
    // JUnit is aligned there rather than twice. H2, ArchUnit and Logback are test-only in this build and
    // reach no consumer at all.
  }
}

// The BOM's whole promise is that a consumer importing the platform needs no version for any published
// module — and nothing checked that the list above kept up. It is not a hypothetical: this BOM once
// shipped EMPTY, which the 2026-07 full-library review found by reading rather than by a failing build.
// Both sides are computed at configuration time so the check is configuration-cache friendly.
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
