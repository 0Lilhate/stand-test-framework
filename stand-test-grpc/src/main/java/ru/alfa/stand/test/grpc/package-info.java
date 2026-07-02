/**
 * Stand test SDK — gRPC adapter.
 *
 * <p>Owns the typed lazy-builder {@link ru.alfa.stand.test.grpc.GrpcStep} (step type {@code grpc.unary})
 * and the gRPC {@link ru.alfa.stand.test.grpc.GrpcStepExecutor} (registered via the core
 * {@code StepExecutor} SPI in {@code META-INF/services}). The executor is the single point of real gRPC IO
 * to a stand; the SDK never ships its own gRPC stack (plan §4, §20) and generates no service contracts.
 *
 * <p><strong>{@code grpc.unary}</strong> resolves a logical target alias to a
 * {@link ru.alfa.stand.test.core.environment.GrpcTargetDefinition} via the
 * {@link ru.alfa.stand.test.core.environment.EnvironmentRegistry}; the {@code targetRef} is resolved to a
 * {@code host:port} at run time (plan §9), never hardcoded. Because declarative scenarios carry no
 * generated stubs, the {@link ru.alfa.stand.test.grpc.DefaultGrpcCallInvoker} resolves the method
 * descriptor over gRPC <strong>Server Reflection</strong>, builds the request from JSON into a
 * {@code com.google.protobuf.DynamicMessage}, calls the method generically under the mandatory deadline,
 * and renders the response back to JSON — so assertions and captures use the same JSONPath model as the
 * REST/Kafka adapters (plan §"Ключевое решение", decision A). The SDK-owned correlation id is injected
 * into gRPC {@code Metadata} (METADATA carrier).
 *
 * <p>The channel is created once per target alias and lives in the run's
 * {@link ru.alfa.stand.test.core.execution.ResourceScope}, closed by the runner. Two seams keep the
 * executor unit-testable without a real server: {@link ru.alfa.stand.test.grpc.GrpcChannelFactory}
 * (channel creation) and {@link ru.alfa.stand.test.grpc.GrpcCallInvoker} (the unary call). Failure
 * semantics follow plan §8.3: assertion failures are {@code StandTestAssertionError}s; configuration or
 * transport/status problems are {@code StandTestException}s that preserve the gRPC status code.
 */
package ru.alfa.stand.test.grpc;
