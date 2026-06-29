package ru.alfa.stand.test.kafka;

import java.util.Objects;

/**
 * A single JSONPath equality assertion against the value of a matched Kafka message.
 *
 * @param jsonPath the JSONPath expression locating the value (never blank)
 * @param expectedValue the expected value at that path (never null)
 */
public record KafkaAssertion(String jsonPath, Object expectedValue) {

    public KafkaAssertion {
        if (jsonPath == null || jsonPath.isBlank()) {
            throw new IllegalArgumentException("jsonPath must not be blank");
        }
        Objects.requireNonNull(expectedValue, "expectedValue must not be null");
    }
}
