// stand-test-await — the SDK's single await mechanism; see README.md. Two dependency decisions:
//
//  - no third-party polling engine (Awaitility): a consumer of the SDK never inherits or conflicts with
//    a transitive version of one, which is worth more than the ~100 lines the engine costs to own.
//  - core is `api`, not `implementation`: await re-exposes core types (StandTestException) on its own
//    public surface, and every adapter depending on await needs core anyway.

dependencies {
  api(project(":stand-test-core"))
  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.junit.platform.launcher)
}
