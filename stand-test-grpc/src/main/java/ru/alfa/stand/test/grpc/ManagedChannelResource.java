package ru.alfa.stand.test.grpc;

import io.grpc.ManagedChannel;
import java.util.Objects;

/**
 * An {@link AutoCloseable} wrapper around a gRPC {@link ManagedChannel}, so a channel can live in the
 * run's {@link ru.alfa.stand.test.core.execution.ResourceScope} (which owns {@code AutoCloseable}
 * resources) and be shared across every {@code grpc.unary} step targeting the same alias.
 * {@link ManagedChannel} is not itself {@code AutoCloseable}; the runner closes this wrapper at the end
 * of the run.
 */
final class ManagedChannelResource implements AutoCloseable {

    private final ManagedChannel channel;

    ManagedChannelResource(ManagedChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
    }

    ManagedChannel channel() {
        return this.channel;
    }

    @Override
    public void close() {
        this.channel.shutdownNow();
    }
}
