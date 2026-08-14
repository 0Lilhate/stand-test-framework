dependencies {
  api(project(":stand-test-core"))

  implementation(libs.allure.java.commons)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testRuntimeOnly(libs.junit.platform.launcher)
}
