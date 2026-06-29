// stand-test-core — foundational SPI contracts, scenario/context model, value objects, result
// model, validation and reporting-event contracts for the stand-test SDK. Root of the module
// dependency graph: depends on NO sibling module and on NO adapter/IO library.
//
// Shared Java / checkstyle / publishing configuration is applied by the root `subprojects { }`
// block, so this file stays intentionally minimal. Only unit-test dependencies are declared here
// (no REST/Kafka/JDBC/gRPC/Spring/Allure/YAML — this module has no IO).

dependencies {
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testRuntimeOnly(libs.junit.platform.launcher)
}
