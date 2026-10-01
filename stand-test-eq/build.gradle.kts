dependencies {
  api(project(":stand-test-core"))
  implementation(project(":stand-test-http"))
  implementation(project(":stand-test-await"))
  implementation(libs.slf4j.api)
  implementation(libs.jackson.databind)
  implementation(libs.json.path)

  // Optional, license-gated (NFR-03, DEP-6, G0-JT400): the unit-phase reader loads jt400 reflectively,
  // so the module compiles and publishes without it. The BOM constrains the version a consumer adds.
  compileOnly(libs.jt400)

  testImplementation(testFixtures(project(":stand-test-http")))
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testRuntimeOnly(libs.junit.platform.launcher)
}
