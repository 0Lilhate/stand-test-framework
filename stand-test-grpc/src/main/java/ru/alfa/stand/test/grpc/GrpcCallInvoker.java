package ru.alfa.stand.test.grpc;

import io.grpc.Channel;
import io.grpc.Metadata;

/**
 * Abstraction over performing a single gRPC unary call.
 *
 * <p>This seam separates the call mechanics (descriptor resolution, marshalling, transport) from the
 * executor's SDK logic (alias resolution, correlation, assertions, capture), so {@link GrpcStepExecutor}
 * can be unit-tested with a fake invoker returning canned response JSON — while the
 * {@link DefaultGrpcCallInvoker} carries the real Server-Reflection + {@code DynamicMessage} path
 * (plan §"Ключевое решение", decision A).
 *
 * <p>A transport/status failure is signalled by letting the gRPC {@link io.grpc.StatusRuntimeException}
 * propagate (the executor maps it, preserving the status code); a descriptor/marshalling problem is a
 * {@link ru.alfa.stand.test.core.exception.StandTestException}.
 */
public interface GrpcCallInvoker {

    /**
     * Performs a unary call and returns the response rendered as JSON.
     *
     * @param channel the channel to call on
     * @param methodFullName the fully-qualified method name ({@code package.Service/Method})
     * @param requestJson the request payload as JSON (may be null/blank for an empty request)
     * @param metadata the request metadata (correlation id already injected)
     * @param deadlineMillis the call deadline, in milliseconds
     * @return the response message rendered as JSON
     */
    String invokeUnary(Channel channel, String methodFullName, String requestJson, Metadata metadata, long deadlineMillis);
}
