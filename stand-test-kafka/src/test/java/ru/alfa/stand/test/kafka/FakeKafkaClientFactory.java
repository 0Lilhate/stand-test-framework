package ru.alfa.stand.test.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;

/**
 * In-memory {@link KafkaClientFactory} that hands back a pre-built Apache {@code MockProducer} /
 * {@code MockConsumer}, recording the resolved cluster and the consumer group id, so the executor can
 * be unit-tested without a broker.
 */
final class FakeKafkaClientFactory implements KafkaClientFactory {

    private final Producer<String, String> producer;
    private final Consumer<String, String> consumer;
    private ResolvedKafkaCluster producerCluster;
    private ResolvedKafkaCluster consumerCluster;
    private String groupId;
    private int producerCreations;
    private int consumerCreations;

    FakeKafkaClientFactory(Producer<String, String> producer, Consumer<String, String> consumer) {
        this.producer = producer;
        this.consumer = consumer;
    }

    @Override
    public Producer<String, String> createProducer(ResolvedKafkaCluster cluster) {
        this.producerCluster = cluster;
        this.producerCreations++;
        return this.producer;
    }

    @Override
    public Consumer<String, String> createConsumer(ResolvedKafkaCluster cluster, String groupId) {
        this.consumerCluster = cluster;
        this.groupId = groupId;
        this.consumerCreations++;
        return this.consumer;
    }

    int consumerCreations() {
        return this.consumerCreations;
    }

    ResolvedKafkaCluster producerCluster() {
        return this.producerCluster;
    }

    ResolvedKafkaCluster consumerCluster() {
        return this.consumerCluster;
    }

    String groupId() {
        return this.groupId;
    }
}
