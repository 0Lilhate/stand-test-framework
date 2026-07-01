// stand-test-scenario-yaml — YAML scenario engine: parses declarative scenario files into the generic
// core Scenario model (a second input to the same model as the Java DSL); consumers then run the parsed
// Scenario through the shared core runner. CORE-ONLY: step executors are resolved via the core
// StepExecutor SPI at runtime, so there are NO compile-time edges to the adapter modules
// (rest/kafka/db/grpc) and the runner is not duplicated (plan §4/§5; design:
// docs/arch/stand-test-scenario-yaml-design.md). External dependency: SnakeYAML (safe-loaded).

dependencies {
    api(project(":stand-test-core"))
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
