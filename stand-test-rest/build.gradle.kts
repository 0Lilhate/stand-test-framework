// stand-test-rest — REST / HTTP adapter. Owns the typed `RestStep` model and the REST `StepExecutor`
// (registered via the core SPI in META-INF/services). It is the single point of real HTTP IO to a stand.
//
// Internal dependencies follow the target graph (docs/arch §4, §5): rest -> core, rest -> await.
//   - core is `api`: RestStep produces core `ScenarioStep`s and RestStepExecutor implements the core
//     `StepExecutor` SPI, so those types are part of this module's public surface.
//   - await is `implementation`: it is an internal detail of (future) polling, not re-exposed.
//
// External dependencies (plan §4): a mature HTTP client — Spring WebClient (`spring-webflux`) driven
// through the JDK HttpClient connector (`JdkClientHttpConnector`), so reactor-netty is intentionally
// NOT pulled in — plus JSONPath (`json-path`, json-smart provider, no Jackson). The SDK never ships
// its own HTTP client (plan §4, §20).
//
// Shared Java / checkstyle / jacoco / publishing configuration comes from the root `subprojects { }`.

dependencies {
    api(project(":stand-test-core"))
    implementation(project(":stand-test-await"))

    implementation(libs.spring.webflux)
    implementation(libs.json.path)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
