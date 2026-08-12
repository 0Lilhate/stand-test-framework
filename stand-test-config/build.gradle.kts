dependencies {
  api(project(":stand-test-core"))

  implementation(libs.snakeyaml)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.junit.platform.launcher)
}
