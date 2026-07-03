package ru.alfa.stand.test.example;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A local gRPC server double for the examples: the bundled Health service (a real unary method
 * {@code grpc.health.v1.Health/Check}) plus Server Reflection, so a {@code grpc.unary} scenario runs the
 * whole path — descriptor over reflection, request as {@code DynamicMessage} — against an in-JVM server on
 * a loopback port, with no external stand (plan §16). It is the gRPC analog of {@link ExampleHttpServer};
 * unlike the in-process transport, it binds a real port so the SDK's default channel factory
 * ({@code host:port}) connects to it. Synchronous lifecycle, no {@code Thread.sleep}.
 *
 * <p>Like {@link ExampleHttpServer#receivedHeader(String)}, it records the ASCII metadata of the last
 * Health call, so an example can prove the SDK injected its correlation id into the outbound gRPC
 * metadata ({@link #receivedMetadata(String)}).
 */
final class ExampleGrpcServer implements AutoCloseable {

    private final Server server;

    private final Map<String, String> receivedMetadata = new ConcurrentHashMap<>();

    ExampleGrpcServer(int port) {
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus("", ServingStatus.SERVING);
        try {
            this.server = ServerBuilder.forPort(port)
                    .addService(ServerInterceptors.intercept(health.getHealthService(), new MetadataRecorder()))
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
        } catch (IOException failure) {
            throw new IllegalStateException("could not start the example gRPC server on port " + port, failure);
        }
    }

    String receivedMetadata(String name) {
        return this.receivedMetadata.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void close() {
        // Block until the listening socket is actually released: two example classes bind the SAME fixed
        // GRPC_TARGET port sequentially, and shutdownNow() alone is asynchronous — returning before
        // termination would race the next test's bind.
        this.server.shutdown();
        try {
            if (!this.server.awaitTermination(5, TimeUnit.SECONDS)) {
                this.server.shutdownNow();
                this.server.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            this.server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private final class MetadataRecorder implements ServerInterceptor {

        @Override
        public <Q, S> ServerCall.Listener<Q> interceptCall(
                ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
            for (String key : headers.keys()) {
                if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                    String value = headers.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
                    if (value != null) {
                        ExampleGrpcServer.this.receivedMetadata.put(key.toLowerCase(Locale.ROOT), value);
                    }
                }
            }
            return next.startCall(call, headers);
        }
    }
}
