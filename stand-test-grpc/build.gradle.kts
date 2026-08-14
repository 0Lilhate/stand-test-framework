dependencies {
  api(project(":stand-test-core"))
  implementation(project(":stand-test-await"))

  implementation(libs.grpc.api)
  implementation(libs.grpc.stub)
  implementation(libs.grpc.protobuf)
  implementation(libs.grpc.services)
  implementation(libs.protobuf.java)
  implementation(libs.protobuf.java.util)
  implementation(libs.json.path)
  implementation(libs.slf4j.api)

  runtimeOnly(libs.grpc.netty.shaded)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.grpc.inprocess)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.junit.platform.launcher)
}
