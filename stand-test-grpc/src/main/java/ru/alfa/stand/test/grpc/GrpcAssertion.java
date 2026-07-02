package ru.alfa.stand.test.grpc;

import java.util.Objects;

/**
 * A single JSONPath equality assertion against the JSON rendering of a gRPC unary response message.
 *
 * @param jsonPath the JSONPath expression locating the value (never blank)
 * @param expectedValue the expected value at that path (never null)
 */
public record GrpcAssertion(String jsonPath, Object expectedValue) {

    public GrpcAssertion {
        if (jsonPath == null || jsonPath.isBlank()) {
            throw new IllegalArgumentException("jsonPath must not be blank");
        }
        Objects.requireNonNull(expectedValue, "expectedValue must not be null");
    }
}
