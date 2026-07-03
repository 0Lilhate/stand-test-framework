package ru.alfa.stand.test.core.environment;

/**
 * Definition of a logical Kafka topic alias.
 *
 * @param alias the logical topic alias used in scenarios (never blank)
 * @param name the actual topic name for the environment (never blank)
 * @param correlation how the correlation id is carried in messages (may be null)
 * @param cluster the alias of the named Kafka cluster this topic lives on (null = the environment's
 *     default {@code kafkaCluster}); when present it must be declared in the environment's
 *     {@code kafkaClusters} whitelist
 */
public record TopicDefinition(String alias, String name, CorrelationConfig correlation, String cluster) {

    public TopicDefinition {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("topic alias must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("topic name must not be blank");
        }
        if (cluster != null && cluster.isBlank()) {
            throw new IllegalArgumentException("topic cluster alias must not be blank (omit it for the default cluster)");
        }
    }

    /**
     * Creates a topic on the environment's default Kafka cluster, preserving the prior
     * three-argument shape.
     *
     * @param alias the logical topic alias used in scenarios (never blank)
     * @param name the actual topic name for the environment (never blank)
     * @param correlation how the correlation id is carried in messages (may be null)
     */
    public TopicDefinition(String alias, String name, CorrelationConfig correlation) {
        this(alias, name, correlation, null);
    }
}
