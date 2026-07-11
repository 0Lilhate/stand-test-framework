// stand-test-kafka — Kafka adapter. Owns the typed `KafkaStep` model (step types `kafka.send` /
// `kafka.expect`) and the Kafka `StepExecutor` (registered via the core SPI in META-INF/services).
// It is the single point of real Kafka IO to a stand.
//
// Internal dependencies follow the target graph (docs/arch §4, §5): kafka -> core, kafka -> await.
//   - core is `api`: KafkaStep produces core `ScenarioStep`s and KafkaStepExecutor implements the core
//     `StepExecutor` SPI, so those types are part of this module's public surface.
//   - await is `implementation`: the `kafka.expect` poll loop runs through the `Awaiter`, an internal
//     detail not re-exposed.
//
// External dependencies (plan §4): raw Apache `kafka-clients` (for assign/seek control — the SDK never
// ships its own Kafka client, plan §4/§20) plus `json-path` (JSONPath; the message value is read as a
// string, so a separate JSON binding / Jackson is not required, mirroring stand-test-rest). Tests use
// the Apache MockProducer/MockConsumer (no broker).
//
// Shared Java / checkstyle / jacoco / publishing configuration comes from the root `subprojects { }`.

dependencies {
    api(project(":stand-test-core"))
    implementation(project(":stand-test-await"))

    implementation(libs.kafka.clients)
    implementation(libs.json.path)
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // Test-only SLF4J binding so log lines can be captured/asserted (ListAppender).
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.junit.platform.launcher)
}
