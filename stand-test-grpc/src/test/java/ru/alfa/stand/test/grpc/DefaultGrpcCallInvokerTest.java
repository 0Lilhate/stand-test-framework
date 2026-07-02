package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * End-to-end coverage of the reflection + DynamicMessage path (decision A) against an in-process gRPC
 * server that exposes the bundled Health service plus Server Reflection — no external stand, no generated
 * stubs of our own. The Health service is a real, reflection-discoverable unary method
 * ({@code grpc.health.v1.Health/Check}).
 */
class DefaultGrpcCallInvokerTest {

    private static final long DEADLINE_MILLIS = 5_000L;

    private final DefaultGrpcCallInvoker invoker = new DefaultGrpcCallInvoker();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startServer() throws IOException {
        String name = InProcessServerBuilder.generateName();
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus("", ServingStatus.SERVING);
        this.server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(health.getHealthService())
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        this.channel = InProcessChannelBuilder.forName(name).build();
    }

    @AfterEach
    void stopServer() {
        this.channel.shutdownNow();
        this.server.shutdownNow();
    }

    @Test
    @DisplayName("resolves the method over reflection and returns the response as JSON")
    void unaryOverReflection() {
        String response = this.invoker.invokeUnary(this.channel, "grpc.health.v1.Health/Check", "{\"service\":\"\"}", new Metadata(), DEADLINE_MILLIS);
        assertThat(response).contains("SERVING");
    }

    @Test
    @DisplayName("an unknown method on a known service is a configuration error")
    void unknownMethod() {
        assertThatThrownBy(() -> this.invoker.invokeUnary(this.channel, "grpc.health.v1.Health/Nope", "{}", new Metadata(), DEADLINE_MILLIS))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("an unknown service surfaces the reflection error")
    void unknownService() {
        assertThatThrownBy(() -> this.invoker.invokeUnary(this.channel, "does.not.Exist/Do", "{}", new Metadata(), DEADLINE_MILLIS))
                .isInstanceOf(StandTestException.class);
    }
}
