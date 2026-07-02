package ru.alfa.stand.test.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.Objects;

/**
 * Default {@link GrpcChannelFactory} backed by the gRPC runtime.
 *
 * <p>Builds a plaintext channel for the resolved {@code host:port} target. Transport security (TLS) and
 * channel credentials are a later iteration (plan §"Не входит / отложено"); the MVP targets internal
 * DEV/IFT stands over plaintext. Building a channel does not open a connection — the first RPC does — so
 * registering it in the run scope before any call cannot leak a live connection.
 */
public final class DefaultGrpcChannelFactory implements GrpcChannelFactory {

    @Override
    public ManagedChannel create(ResolvedGrpcTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        return ManagedChannelBuilder.forTarget(target.target())
                .usePlaintext()
                .build();
    }
}
