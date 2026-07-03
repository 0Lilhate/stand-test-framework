// stand-test-example — technical usage examples (Iteration 8, docs/arch/stand-test-example-implementation-plan.md).
// TEST-ONLY: the scenarios live in src/test and run through the public SDK API as a black box against
// in-process doubles (JDK HttpServer for REST, H2 for DB), so `./gradlew build` is green offline without
// a real DEV/IFT stand (test doubles are allowed by plan §16). It is a pure consumer (a sink) of the SDK
// modules; nothing depends on it. No business logic, no real-stand config, no published artifact
// (plan §6/§7/§20). Phase 1 covers REST+DB via the manual runner; Phase 2 adds the canonical @StandTest
// path (StandTestExampleTest) and a tagged Kafka example (KafkaExampleTest, requires a broker — excluded
// from the default run).

dependencies {
    testImplementation(project(":stand-test-core"))
    testImplementation(project(":stand-test-junit"))
    testImplementation(project(":stand-test-rest"))
    testImplementation(project(":stand-test-db"))
    testImplementation(project(":stand-test-kafka"))
    testImplementation(project(":stand-test-grpc"))
    testImplementation(project(":stand-test-allure"))
    // The @StandTest path resolves its EnvironmentRegistry from src/test/resources/stand-test-environments.yml
    // through stand-test-config's FileEnvironmentRegistry SPI provider — the same wiring a real consumer uses.
    testImplementation(project(":stand-test-config"))
    // gRPC example: the grpc adapter keeps grpc-api/services/transport as implementation/runtimeOnly, so
    // the example declares what it needs at compile time to stand up a local gRPC double — grpc-api
    // (ServerBuilder) + grpc-services (HealthStatusManager, ProtoReflectionServiceV1) — plus the shaded
    // Netty transport at runtime so the SDK's default channel factory can dial a real loopback port.
    testImplementation(libs.grpc.api)
    testImplementation(libs.grpc.services)
    testRuntimeOnly(libs.grpc.netty.shaded)
    // AI-format parity: the scenario-yaml engine (AiScenarioParser) parses the AI document, and the
    // ai-schema module ships the JSON Schema it must first validate against. Both are core-only and
    // test-only here. The JSON Schema validator (networknt) + Jackson are declared directly: ai-schema
    // keeps them in its own test scope, so they do NOT reach this module transitively.
    testImplementation(project(":stand-test-scenario-yaml"))
    testImplementation(project(":stand-test-ai-schema"))
    testImplementation(libs.networknt.json.schema.validator)
    testImplementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// env-ref wiring for the doubles. DB: DbStepExecutor's no-arg form resolves datasource refs from the
// process environment (its passthrough-resolver ctor is package-private, so env-ref is the only
// cross-module path). REST on the @StandTest path: the default no-arg RestStepExecutor resolves the
// service base URL from CLIENT_SERVICE_URL via System.getenv, so the HTTP double must listen on a fixed
// port — pinned here and overridable in CI with -PexampleRestPort=NNNN (avoids port-collision). The
// manual-runner examples ignore CLIENT_SERVICE_URL (they use the passthrough seam on an ephemeral port).
// H2 stays in-memory for the JVM via DB_CLOSE_DELAY=-1.
// The gRPC example stands up a real (Netty) gRPC double on a fixed loopback port pinned by GRPC_TARGET,
// which the default no-arg GrpcStepExecutor resolves via System.getenv (like CLIENT_SERVICE_URL for REST).
// Override the port in CI with -PexampleGrpcPort=NNNN to avoid collisions.
val exampleRestPort = (findProperty("exampleRestPort") as String?)?.toInt() ?: 18080
val exampleGrpcPort = (findProperty("exampleGrpcPort") as String?)?.toInt() ?: 18090
tasks.withType<Test>().configureEach {
    // The Kafka example needs a live broker (kafka.expect arms a real KafkaConsumer), so it is tagged
    // `requires-broker` and excluded from the default offline run. Opt in with -PincludeRequiresBroker
    // against a reachable broker (override its address with -PkafkaBootstrapServers).
    useJUnitPlatform {
        if (!project.hasProperty("includeRequiresBroker")) {
            excludeTags("requires-broker")
        }
    }
    environment("MAIN_DB_URL", "jdbc:h2:mem:exampledb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
    environment("MAIN_DB_USER", "sa")
    environment("MAIN_DB_PASSWORD", "sa")
    environment("CLIENT_SERVICE_URL", "http://127.0.0.1:$exampleRestPort")
    environment("GRPC_TARGET", "127.0.0.1:$exampleGrpcPort")
    environment("KAFKA_BOOTSTRAP_SERVERS", (findProperty("kafkaBootstrapServers") as String?) ?: "localhost:9092")
}

// Examples are demonstrations, not production code: src/main is empty, so the 80% coverage gate is not
// applicable. The module is not a consumable artifact either — the root subprojects block creates no
// maven publication for it at all.
tasks.withType<JacocoCoverageVerification>().configureEach { enabled = false }
