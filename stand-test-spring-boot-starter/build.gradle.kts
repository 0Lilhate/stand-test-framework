// stand-test-spring-boot-starter — Spring Boot auto-configuration for the stand-test SDK. Dropping it
// on a Spring Boot test project's classpath makes an `@Autowired StandClient` (and the underlying
// runner/validator/registry/publisher/executors) available, mirroring what
// `StandTestExtension.buildStandClient()` assembles for JUnit — but through Spring beans instead of the
// ServiceLoader.
//
// This is a LIBRARY, not a bootable application — the `org.springframework.boot` plugin is deliberately
// NOT applied. Shared java-library/checkstyle/jacoco/publishing config comes from the root
// `subprojects { }`.
//
// Dependency direction is strictly one-way (docs/arch §4/§5): the starter is a SINK over core + the
// runtime modules; nothing in the SDK depends back on it, and core never depends on Spring.
//
// CLASSPATH POLICY — the starter forces ONLY `stand-test-core`. Every other wired module is
// `compileOnly` (an "optional" dependency): the auto-configuration compiles against the adapter/reporting
// types (referenced by `@ConditionalOnClass`-guarded `@Bean` methods), but they are NOT re-exported
// transitively. A consumer adds just the modules they actually use, and each executor/publisher bean
// appears only when its module is on the classpath (`@ConditionalOnClass`). This keeps heavy transitive
// deps (spring-webflux, kafka-clients, grpc, allure-java-commons) off a consumer's classpath unless requested.
// (This supersedes the plan's original all-`api` aggregator choice — see the plan's key-decisions note.)
//
//   - The same modules are on `testImplementation` so this module's own context tests exercise the
//     full-classpath wiring; the `@ConditionalOnClass` "absent" paths are covered via FilteredClassLoader.

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

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.test)
    testImplementation(project(":stand-test-await"))
    testImplementation(project(":stand-test-allure"))
    testImplementation(project(":stand-test-rest"))
    testImplementation(project(":stand-test-kafka"))
    testImplementation(project(":stand-test-db"))
    testImplementation(project(":stand-test-grpc"))
    // Parity guard only: the starter's properties->registry mapper and stand-test-config's YAML mapper
    // describe the same logical schema; EnvironmentRegistryParityTest pins them to identical core records.
    testImplementation(project(":stand-test-config"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
