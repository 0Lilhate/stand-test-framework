// stand-test-junit — the JUnit 5 ↔ SDK bridge; see README.md. One dependency decision worth stating:
// core and await are `api`, not `implementation`, because the types this extension RESOLVES INTO a
// consumer's test signature (StandClient, Scenario, Awaiter) come from them — a consumer cannot write
// `void test(StandClient stand)` without them on its compile classpath.

dependencies {
  api(platform(libs.junit.bom))
  api(project(":stand-test-core"))
  api(project(":stand-test-await"))
  api(libs.junit.jupiter.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.junit.platform.testkit)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// EngineTestKit fixtures (intentionally passing/failing nested test classes used to drive the
// extension in-process) are tagged `standtest-fixture` and excluded from the normal test run so their
// deliberate failures never break the build; EngineTestKit selects them directly, bypassing this tag
// filter.
tasks.withType<Test>().configureEach {
  useJUnitPlatform {
    excludeTags("standtest-fixture")
  }
}
