// stand-test-junit — JUnit 5 integration layer: the bridge between the JUnit lifecycle and the SDK.
//
// It registers a StandTestExtension that resolves a StandClient (assembled from StepExecutor SPI
// implementations discovered on the classpath) and an Awaiter as test parameters, without Spring.
// SDK failures need no translation: StandTestAssertionError extends AssertionError and
// StandTestException extends RuntimeException, so the runner's thrown failures are native JUnit
// failures/errors.
//
// Internal dependencies follow the target graph (docs/arch §4, §5): junit -> core, junit -> await.
// Both are `api`: the resolved StandClient/Scenario and Awaiter parameter types are part of this
// module's public test API.
//
// Shared Java / checkstyle / jacoco / publishing configuration comes from the root `subprojects { }`.

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
