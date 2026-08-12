import java.security.MessageDigest

dependencies {
  testImplementation(project(":stand-test-core"))
  testImplementation(project(":stand-test-await"))
  testImplementation(project(":stand-test-junit"))
  testImplementation(project(":stand-test-rest"))
  testImplementation(project(":stand-test-db"))
  testImplementation(project(":stand-test-kafka"))
  testImplementation(libs.kafka.clients)
  testImplementation(project(":stand-test-grpc"))
  testImplementation(project(":stand-test-ui"))
  testImplementation(project(":stand-test-allure"))
  testImplementation(project(":stand-test-config"))
  testImplementation(libs.grpc.api)
  testImplementation(libs.grpc.services)
  testRuntimeOnly(libs.grpc.netty.shaded)
  testImplementation(project(":stand-test-scenario-yaml"))
  testImplementation(project(":stand-test-spring-boot-starter"))
  testImplementation(libs.spring.boot.test)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.archunit)
  testImplementation(libs.h2)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.logback.classic)
}

val standUiSystemProperties: Map<String, String> = buildMap {
  put("stand.test.ui.headless", "false")
  putAll(providers.systemPropertiesPrefixedBy("stand.test.ui.").get())
}

val standBoundEnvironment: Map<String, String> = buildMap {
  (findProperty("tksAdminUsername") as String?)?.let { put("TKS_ADMIN_1_USERNAME", it) }
  (findProperty("tksAdminPassword") as String?)?.let { put("TKS_ADMIN_1_PASSWORD", it) }
  listOf("TAKSA_", "TKS_", "PLAYWRIGHT_").forEach { prefix ->
    putAll(providers.environmentVariablesPrefixedBy(prefix).get())
  }
}

val standBoundEnvironmentDigest: String = MessageDigest.getInstance("SHA-256")
  .digest((standBoundEnvironment.toSortedMap().toString() + standUiSystemProperties.toSortedMap()).toByteArray())
  .joinToString("") { part -> "%02x".format(part) }

val exampleRestPort = (findProperty("exampleRestPort") as String?)?.toInt() ?: 18080
val exampleGrpcPort = (findProperty("exampleGrpcPort") as String?)?.toInt() ?: 18090

tasks.withType<Test>().configureEach {
  useJUnitPlatform {
    if (!project.hasProperty("includeRequiresBroker")) {
      excludeTags("requires-broker")
    }

  }
  environment("MAIN_DB_URL", "jdbc:h2:mem:exampledb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
  environment("MAIN_DB_USER", "sa")
  environment("MAIN_DB_PASSWORD", "sa")
  environment("CLIENT_SERVICE_URL", "http://127.0.0.1:$exampleRestPort")
  environment("CLIENT_USER", "Aladdin")
  environment("CLIENT_PASSWORD", "open sesame")
  environment("GRPC_TARGET", "127.0.0.1:$exampleGrpcPort")
  environment("KAFKA_BOOTSTRAP_SERVERS", (findProperty("kafkaBootstrapServers") as String?) ?: "localhost:9092")

  environment(standBoundEnvironment)
  standUiSystemProperties.forEach { (name, value) -> systemProperty(name, value) }

  inputs.property("standBoundEnvironment", standBoundEnvironmentDigest)
}

tasks.withType<JacocoCoverageVerification>().configureEach { enabled = false }

tasks.test {
  inputs.files(
    fileTree(rootDir.resolve("docs/ai-agent/.claude")) {
      include("skills/**", "commands/**", "rules/**")
    },
  ).withPropertyName("standTestAuthoringCrib")
}
