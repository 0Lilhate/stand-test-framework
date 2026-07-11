plugins {
  `java-platform`
  `maven-publish`
}

// Aggregate BOM (platform) for the stand-test SDK.
//
// It constrains (1) every published SDK module to this build's version and (2) the curated
// third-party libraries the adapters expose or rely on, so a consumer importing the platform gets a
// consistent, tested dependency set:
//
//   testImplementation(platform("ru.alfa.stand.test:stand-test-bom:<version>"))
//   testImplementation("ru.alfa.stand.test:stand-test-junit")   // no explicit versions needed
//
// Versions come from the same catalog the modules build against (gradle/libs.versions.toml), so the
// BOM can never drift from the SDK's own compile-time versions. Deliberately NOT constrained:
// test-only libraries of this build (h2, networknt/jackson, junit/assertj — the consumer owns its
// test stack) and Spring Boot (governed by the consumer's own Boot BOM/plugin).
//
// IMPORTANT: modules constrained by this BOM must NOT import it back (that would create a
// `core -> bom -> core` cycle). Only EXTERNAL consumers import it.

dependencies {
  constraints {
    // SDK modules (stand-test-example is test-only and not published).
    api(project(":stand-test-core"))
    api(project(":stand-test-await"))
    api(project(":stand-test-junit"))
    api(project(":stand-test-rest"))
    api(project(":stand-test-kafka"))
    api(project(":stand-test-db"))
    api(project(":stand-test-grpc"))
    api(project(":stand-test-allure"))
    api(project(":stand-test-scenario-yaml"))
    api(project(":stand-test-ai-schema"))
    api(project(":stand-test-config"))
    api(project(":stand-test-spring-boot-starter"))

    // Logging facade every SDK module compiles against (plan §17). Constrained so a consumer's binding
    // resolves against the same slf4j-api the SDK was built with; the binding itself is the consumer's.
    api(libs.slf4j.api)

    // Curated third-party versions the adapters are built and tested against.
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
  }
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
