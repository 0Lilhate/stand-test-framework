package ru.alfa.stand.test.grpc;

import io.grpc.ManagedChannel;

/**
 * Abstraction over creating the real gRPC {@link ManagedChannel}.
 *
 * <p>This seam keeps the gRPC transport isolated behind one interface and lets {@link GrpcStepExecutor}
 * be unit-tested with an in-process channel (or a channel that is never dialed, when the call itself is
 * faked). Implementations create channels only — they apply no SDK logic (alias resolution, correlation
 * injection, assertions all happen in the executor). The created channel is owned by the run's
 * {@link ru.alfa.stand.test.core.execution.ResourceScope} and closed by the runner.
 */
public interface GrpcChannelFactory {

    /**
     * Creates a channel for the given resolved target. The caller owns and later closes it.
     *
     * @param target the resolved target address
     * @return a new managed channel
     */
    ManagedChannel create(ResolvedGrpcTarget target);
}
