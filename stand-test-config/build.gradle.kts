// stand-test-config — file-based environment configuration loader. Reads a declarative YAML file into an
// immutable core EnvironmentRegistry and publishes it via the core EnvironmentRegistry SPI
// (META-INF/services), so a plain-JUnit consumer's StandTestExtension discovers a populated registry with
// no wiring code. The Spring Boot path builds its registry from @ConfigurationProperties instead; this
// module is the non-Spring counterpart.
//
// Internal dependencies: core-only (docs/arch §4/§5). core is `api` — the module returns a core
// EnvironmentRegistry built from core *Definition records, so those types are part of its public surface.
// There are NO edges to the adapter modules (rest/kafka/db/grpc), junit, allure or the Spring starter.
//
// External dependency: SnakeYAML (safe-loaded via SafeConstructor + conservative alias/nesting limits),
// mirroring stand-test-scenario-yaml. The file stores only *references* (environment-variable names),
// never URLs or secret values — resolution stays in the adapters at run time (plan §9).
//
// Shared Java / checkstyle / jacoco / publishing configuration comes from the root `subprojects { }`.

dependencies {
    api(project(":stand-test-core"))
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // The deprecation channel of the registry format (ADR-UI-004) is observable only as a log line, so
    // the surface test needs a binding to capture it. Test-only: the SDK ships the facade, never a binding.
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.junit.platform.launcher)
}
