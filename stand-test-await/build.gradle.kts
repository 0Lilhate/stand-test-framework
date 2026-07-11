// stand-test-await — polling / retry / wait-for-condition primitives built on top of core.
//
// Transport-agnostic by contract: the awaiter waits on a caller-supplied probe/predicate and knows
// nothing about REST/Kafka/DB/gRPC (plan §4 stand-test-await). It is the single mechanism every
// adapter uses instead of `Thread.sleep` (plan §2.4/§2.5).
//
// Runtime dependencies are intentionally limited to `stand-test-core`: no third-party polling engine
// (Awaitility) is pulled in, so consumers of the SDK never inherit/conflict with a transitive
// Awaitility version. `api` is used because await re-exposes core types (e.g. StandTestException) on
// its public surface and every adapter that depends on await also needs core.
//
// Shared Java / checkstyle / jacoco / publishing configuration comes from the root `subprojects { }`.

dependencies {
  api(project(":stand-test-core"))
  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.junit.platform.launcher)
}
