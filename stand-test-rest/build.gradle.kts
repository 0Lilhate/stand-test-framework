dependencies {
  api(project(":stand-test-core"))
  implementation(project(":stand-test-await"))

  implementation(libs.spring.webflux)
  implementation(libs.json.path)
  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.junit.platform.launcher)
}
