package ru.alfa.stand.test.core.environment;

/**
 * Definition of a logical Kafka topic alias.
 *
 * @param alias the logical topic alias used in scenarios (never blank)
 * @param name the actual topic name for the environment (never blank)
 * @param correlation how the correlation id is carried in messages (may be null)
 */
public record TopicDefinition(String alias, String name, CorrelationConfig correlation) {

    public TopicDefinition {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("topic alias must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("topic name must not be blank");
        }
    }
}
