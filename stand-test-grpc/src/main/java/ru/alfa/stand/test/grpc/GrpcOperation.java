package ru.alfa.stand.test.grpc;

import java.util.Locale;

/**
 * The gRPC operations supported by {@link GrpcStep}.
 *
 * <p>Each operation maps to a core step type of the form {@code grpc.<operation>} (lower-case), which the
 * runner uses to dispatch to {@link GrpcStepExecutor}. The MVP ships only the unary call; streaming is
 * deliberately out of scope (plan §"Не входит / отложено").
 */
public enum GrpcOperation {

    /** A single request / single response call. */
    UNARY;

    /**
     * Returns the core step type for this operation, for example {@code grpc.unary}.
     *
     * @return the {@code grpc.<operation>} step type
     */
    public String stepType() {
        return GrpcStepParameters.TYPE_PREFIX + name().toLowerCase(Locale.ROOT);
    }
}
