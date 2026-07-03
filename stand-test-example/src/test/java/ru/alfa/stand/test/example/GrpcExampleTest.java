package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.grpc.GrpcStep;

/**
 * Example: a {@code grpc.unary} call end to end. A local gRPC double ({@link ExampleGrpcServer}) exposes
 * the bundled Health service + Server Reflection on a loopback port; the SDK resolves the {@code health-grpc}
 * alias through the {@code GRPC_TARGET} env-ref (pinned by the build), calls {@code Health/Check} via the
 * default reflection-based invoker, and asserts the JSON response — proving the declarative → runner →
 * GrpcStepExecutor → real server path offline.
 */
class GrpcExampleTest {

    @Test
    @DisplayName("a grpc.unary Health/Check runs through the runner against a local gRPC double")
    void grpcUnary_checksHealthOverReflection() {
        String target = Objects.requireNonNull(
                System.getenv("GRPC_TARGET"), "GRPC_TARGET must be set (see stand-test-example/build.gradle.kts)");
        int port = Integer.parseInt(target.substring(target.lastIndexOf(':') + 1));
        try (ExampleGrpcServer server = new ExampleGrpcServer(port)) {
            StandClient stand = ExampleStand.grpcStand(ExampleStand.grpcRegistry());
            Scenario scenario = Scenario.builder("grpc-health-example")
                    .environment(ExampleStand.ENVIRONMENT)
                    .step(GrpcStep.unary(ExampleStand.GRPC_TARGET)
                            .method("grpc.health.v1.Health/Check")
                            .requestFromResource("fixtures/health-check.json")
                            .withinSeconds(5)
                            .assertPath("$.status", "SERVING")
                            .build())
                    .build();

            ScenarioResult result = stand.run(scenario);

            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.stepResults()).hasSize(1);
        }
    }
}
