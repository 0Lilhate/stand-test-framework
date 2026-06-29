package ru.alfa.stand.test.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;

/**
 * Abstraction over creating the real Kafka clients.
 *
 * <p>This seam keeps {@code kafka-clients} isolated behind one interface and lets
 * {@link KafkaStepExecutor} be unit-tested with the Apache {@code MockProducer}/{@code MockConsumer}
 * (no broker). Implementations create clients only — they apply no SDK logic (alias resolution,
 * correlation injection, selection and assertions all happen in the executor). Keys and values are
 * {@code String} (JSON as a string), mirroring how the REST adapter reads the body as a string.
 */
public interface KafkaClientFactory {

    /**
     * Creates a producer for the given cluster. The caller closes it.
     *
     * @param cluster the resolved cluster connection
     * @return a new producer
     */
    Producer<String, String> createProducer(ResolvedKafkaCluster cluster);

    /**
     * Creates a consumer for the given cluster and consumer group. The caller assigns partitions,
     * positions it and closes it.
     *
     * @param cluster the resolved cluster connection
     * @param groupId the consumer group id (unique per scenario run)
     * @return a new consumer
     */
    Consumer<String, String> createConsumer(ResolvedKafkaCluster cluster, String groupId);
}
