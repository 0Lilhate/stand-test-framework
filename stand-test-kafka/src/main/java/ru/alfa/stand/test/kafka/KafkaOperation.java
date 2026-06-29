package ru.alfa.stand.test.kafka;

import java.util.Locale;

/**
 * The Kafka operations supported by {@link KafkaStep}.
 *
 * <p>Each operation maps to a core step type of the form {@code kafka.<operation>} (lower-case), which
 * the runner uses to dispatch to {@link KafkaStepExecutor}.
 */
public enum KafkaOperation {

    /** Publish a message to a topic. */
    SEND,

    /** Wait for and assert a message on a topic. */
    EXPECT;

    /**
     * Returns the core step type for this operation, for example {@code kafka.send}.
     *
     * @return the {@code kafka.<operation>} step type
     */
    public String stepType() {
        return KafkaStepParameters.TYPE_PREFIX + name().toLowerCase(Locale.ROOT);
    }
}
