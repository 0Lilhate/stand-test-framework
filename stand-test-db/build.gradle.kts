// stand-test-db — DB / JDBC adapter. Owns the typed `DbStep` model (step types `db.query` /
// `db.expectEventually` / `db.seed` / `db.cleanup`) and the DB `StepExecutor` (registered via the core
// SPI in META-INF/services). It is the single point of real JDBC IO to a stand.
//
// Internal dependencies follow the target graph (docs/arch §4, §5): db -> core, db -> await.
//   - core is `api`: DbStep produces core `ScenarioStep`s and DbStepExecutor implements the core
//     `StepExecutor` SPI (and consumes the core `SqlStatementClassifier`, the §8.8 prerequisite), so
//     those types are part of this module's public surface.
//   - await is `implementation`: the `db.expectEventually` poll loop runs through the `Awaiter`, an
//     internal detail not re-exposed.
//
// External dependencies (plan §4): JDK-only — the production adapter uses `java.sql` (JDBC) and the
// JDBC driver is supplied by the consumer. Named parameters (`:name`) are handled by this module's own
// `:name` -> `?` rewriter over `PreparedStatement` (no Spring-JDBC / HikariCP). The SDK ships no ORM and
// no generic "arbitrary SQL" mode (plan §4, §8.8, §20). Tests use H2 in-memory (no real stand), the
// analog of the REST HttpServer / Kafka MockConsumer.
//
// Shared Java / checkstyle / jacoco / publishing configuration comes from the root `subprojects { }`.

dependencies {
    api(project(":stand-test-core"))
    implementation(project(":stand-test-await"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
