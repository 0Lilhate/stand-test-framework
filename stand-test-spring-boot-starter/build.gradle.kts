dependencies {
  api(project(":stand-test-core"))

  compileOnly(project(":stand-test-await"))
  compileOnly(project(":stand-test-allure"))
  compileOnly(project(":stand-test-rest"))
  compileOnly(project(":stand-test-kafka"))
  compileOnly(project(":stand-test-db"))
  compileOnly(project(":stand-test-grpc"))

  api(libs.spring.boot.autoconfigure)
  api(libs.spring.boot)
  annotationProcessor(libs.spring.boot.configuration.processor)

  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.logback.classic)
  testImplementation(libs.spring.boot.test)
  testImplementation(project(":stand-test-await"))
  testImplementation(project(":stand-test-allure"))
  testImplementation(project(":stand-test-rest"))
  testImplementation(project(":stand-test-kafka"))
  testImplementation(project(":stand-test-db"))
  testImplementation(project(":stand-test-grpc"))
  testImplementation(project(":stand-test-config"))
  testRuntimeOnly(libs.junit.platform.launcher)
}
