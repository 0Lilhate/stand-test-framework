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

tasks.withType<Test>().configureEach {
  useJUnitPlatform {
    excludeTags("standtest-fixture")
  }
}
