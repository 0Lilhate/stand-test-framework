package ru.alfa.stand.test.example;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;

/**
 * A local gRPC server double for the examples: the bundled Health service (a real unary method
 * {@code grpc.health.v1.Health/Check}) plus Server Reflection, so a {@code grpc.unary} scenario runs the
 * whole path — descriptor over reflection, request as {@code DynamicMessage} — against an in-JVM server on
 * a loopback port, with no external stand (plan §16). It is the gRPC analog of {@link ExampleHttpServer};
 * unlike the in-process transport, it binds a real port so the SDK's default channel factory
 * ({@code host:port}) connects to it. Synchronous lifecycle, no {@code Thread.sleep}.
 */
final class ExampleGrpcServer implements AutoCloseable {

    private final Server server;

    ExampleGrpcServer(int port) {
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus("", ServingStatus.SERVING);
        try {
            this.server = ServerBuilder.forPort(port)
                    .addService(health.getHealthService())
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
        } catch (IOException failure) {
            throw new IllegalStateException("could not start the example gRPC server on port " + port, failure);
        }
    }

    @Override
    public void close() {
        this.server.shutdownNow();
    }
}
