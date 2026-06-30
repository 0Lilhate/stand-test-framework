// stand-test-allure — Allure reporting adapter. A *consumer* of the core reporting events
// (ScenarioEvent / StepEvent / Attachment) that maps them to the Allure lifecycle: steps, labels,
// parameters and attachments. It executes no scenario and performs no transport IO; it knows nothing
// about REST/Kafka/DB/gRPC. core never depends on Allure — the dependency edge is one-way (§4, §5):
//   - allure -> core (the only internal dependency).
//
// External dependency (plan §4, Iteration 7): Allure Java commons (io.qameta.allure) — the
// AllureLifecycle + model API the adapter maps onto. It is `implementation`, not `api`: Allure types
// never leak through this module's public surface (the mapping is hidden behind AllureLifecycleFacade),
// so consumers are not forced to compile against Allure to depend on the adapter. allure-junit5 (the
// JUnit integration that opens the Allure test case) is intentionally NOT a dependency here — wiring
// the publisher into JUnit/Spring is a separate concern (plan §Phase 3); pulling it in would add JUnit
// to a pure reporting adapter's classpath.
//
// Shared Java / checkstyle / jacoco / publishing configuration comes from the root `subprojects { }`.

dependencies {
    api(project(":stand-test-core"))

    implementation(libs.allure.java.commons)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
