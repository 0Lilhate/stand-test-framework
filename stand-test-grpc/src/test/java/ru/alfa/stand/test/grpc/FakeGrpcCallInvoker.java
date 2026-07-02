package ru.alfa.stand.test.grpc;

import io.grpc.Channel;
import io.grpc.Metadata;

/**
 * In-memory {@link GrpcCallInvoker} that records the call it received and returns canned response JSON (or
 * throws a preset failure), so the executor's SDK logic (correlation, assertions, capture, error mapping)
 * can be unit-tested without a real gRPC server.
 */
final class FakeGrpcCallInvoker implements GrpcCallInvoker {

    private final String responseJson;
    private final RuntimeException failure;
    private Channel channel;
    private String methodFullName;
    private String requestJson;
    private Metadata metadata;
    private long deadlineMillis;
    private int calls;

    private FakeGrpcCallInvoker(String responseJson, RuntimeException failure) {
        this.responseJson = responseJson;
        this.failure = failure;
    }

    static FakeGrpcCallInvoker returning(String responseJson) {
        return new FakeGrpcCallInvoker(responseJson, null);
    }

    static FakeGrpcCallInvoker throwing(RuntimeException failure) {
        return new FakeGrpcCallInvoker(null, failure);
    }

    @Override
    public String invokeUnary(Channel channel, String methodFullName, String requestJson, Metadata metadata, long deadlineMillis) {
        this.channel = channel;
        this.methodFullName = methodFullName;
        this.requestJson = requestJson;
        this.metadata = metadata;
        this.deadlineMillis = deadlineMillis;
        this.calls++;
        if (this.failure != null) {
            throw this.failure;
        }
        return this.responseJson;
    }

    int calls() {
        return this.calls;
    }

    Channel channel() {
        return this.channel;
    }

    String methodFullName() {
        return this.methodFullName;
    }

    String requestJson() {
        return this.requestJson;
    }

    long deadlineMillis() {
        return this.deadlineMillis;
    }

    String metadataValue(String key) {
        if (this.metadata == null) {
            return null;
        }
        return this.metadata.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
    }
}
