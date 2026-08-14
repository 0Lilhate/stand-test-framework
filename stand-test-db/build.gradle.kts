dependencies {
  api(project(":stand-test-core"))
  implementation(project(":stand-test-await"))

  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.h2)
  // Test-only SLF4J binding so DEBUG log lines can be captured/asserted (ListAppender).
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.junit.platform.launcher)
}
