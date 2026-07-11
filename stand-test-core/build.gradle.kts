// stand-test-core — foundational SPI contracts, scenario/context model, value objects, result
// model, validation and reporting-event contracts for the stand-test SDK. Root of the module
// dependency graph: depends on NO sibling module and on NO adapter/IO library.
//
// The single sanctioned external dependency is `slf4j-api` — the logging facade (plan §17). It is a
// pure facade (no binding, no IO, no transitives), so "core has no IO library" still holds; the
// consumer supplies the SLF4J binding. Everything else stays test-only (no REST/Kafka/JDBC/gRPC/
// Spring/Allure/YAML — this module has no IO).
//
// Shared Java / checkstyle / publishing configuration is applied by the root `subprojects { }`
// block, so this file stays intentionally minimal.

dependencies {
  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  // Test-only SLF4J binding so MDC works and log lines can be captured/asserted (ListAppender).
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.junit.platform.launcher)
}
