package ru.alfa.stand.test.grpc;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;

/**
 * In-memory {@link GrpcChannelFactory} that hands back an in-process channel (never dialed, because the
 * call itself is faked) and records the resolved target, so the executor can be unit-tested without a
 * real gRPC server.
 */
final class FakeGrpcChannelFactory implements GrpcChannelFactory {

    private int creations;
    private ResolvedGrpcTarget lastTarget;

    @Override
    public ManagedChannel create(ResolvedGrpcTarget target) {
        this.creations++;
        this.lastTarget = target;
        return InProcessChannelBuilder.forName("grpc-executor-test").build();
    }

    int creations() {
        return this.creations;
    }

    ResolvedGrpcTarget lastTarget() {
        return this.lastTarget;
    }
}
