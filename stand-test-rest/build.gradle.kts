dependencies {
  api(project(":stand-test-http"))
  implementation(project(":stand-test-await"))

  implementation(libs.json.path)
  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.logback.classic)
  testImplementation(testFixtures(project(":stand-test-http")))
  testRuntimeOnly(libs.junit.platform.launcher)
}

// Planned internal dependencies: http and await.
