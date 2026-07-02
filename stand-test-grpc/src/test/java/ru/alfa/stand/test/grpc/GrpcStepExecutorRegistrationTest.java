package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.execution.StepExecutor;

class GrpcStepExecutorRegistrationTest {

    @Test
    @DisplayName("the gRPC executor is discovered via the core StepExecutor ServiceLoader")
    void discoveredViaServiceLoader() {
        boolean found = ServiceLoader.load(StepExecutor.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(GrpcStepExecutor.class::isInstance);
        assertThat(found).as("GrpcStepExecutor must be registered in META-INF/services").isTrue();
    }

    @Test
    @DisplayName("the no-arg executor supports grpc.* step types")
    void noArgConstructorSupportsGrpc() {
        assertThat(new GrpcStepExecutor().supports("grpc.unary")).isTrue();
    }
}
