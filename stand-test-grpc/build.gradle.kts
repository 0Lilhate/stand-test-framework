// stand-test-grpc — gRPC adapter. Owns the typed `GrpcStep` model (step type `grpc.unary`) and the gRPC
// `StepExecutor` (registered via the core SPI in META-INF/services). It is the single point of real gRPC
// IO to a stand.
//
// Internal dependencies follow the target graph (docs/arch §4, §5): grpc -> core, grpc -> await.
//   - core is `api`: GrpcStep produces core `ScenarioStep`s and GrpcStepExecutor implements the core
//     `StepExecutor` SPI (and resolves the core `GrpcTargetDefinition`), so those types are part of this
//     module's public surface.
//   - await is `implementation`: reserved for future polling/streaming waits, not re-exposed. The MVP
//     unary call uses the native gRPC deadline (`CallOptions.withDeadlineAfter`), mirroring how the REST
//     adapter declares `await` ahead of its own future polling.
//
// External dependencies (plan §4): grpc-java (`grpc-api`/`grpc-stub`/`grpc-protobuf`) + `grpc-services`
// (the server-reflection client) + `protobuf-java`/`protobuf-java-util` (JsonFormat) + `json-path`
// (JSONPath assertions/capture over the response JSON, like REST/Kafka). Declarative scenarios carry no
// generated stubs, so the executor resolves the method descriptor over Server Reflection and marshals a
// `DynamicMessage` (plan §"Ключевое решение", decision A). The transport (`grpc-netty-shaded`) is
// `runtimeOnly` so it never reaches the consumer's compile graph; the in-process transport is test-only.
// The SDK never ships its own gRPC stack (plan §4, §20).
//
// Shared Java / checkstyle / jacoco / publishing configuration comes from the root `subprojects { }`.

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
