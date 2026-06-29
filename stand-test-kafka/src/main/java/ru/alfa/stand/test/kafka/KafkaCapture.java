package ru.alfa.stand.test.kafka;

/**
 * Extraction of a value from a matched Kafka message into the run's {@code VariableStore}.
 *
 * @param variableName the variable name to store the captured value under (never blank)
 * @param jsonPath the JSONPath expression locating the value (never blank)
 */
public record KafkaCapture(String variableName, String jsonPath) {

    public KafkaCapture {
        if (variableName == null || variableName.isBlank()) {
            throw new IllegalArgumentException("variableName must not be blank");
        }
        if (jsonPath == null || jsonPath.isBlank()) {
            throw new IllegalArgumentException("jsonPath must not be blank");
        }
    }
}
