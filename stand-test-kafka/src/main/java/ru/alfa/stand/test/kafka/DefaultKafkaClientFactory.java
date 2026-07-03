package ru.alfa.stand.test.kafka;

import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Default {@link KafkaClientFactory} backed by raw Apache {@code kafka-clients}.
 *
 * <p>Keys and values are serialized/deserialized as UTF-8 strings. The consumer disables auto-commit
 * and uses manual partition assignment — the executor positions it with {@code assign}/{@code seekToEnd}
 * (plan §8.7), so no consumer-group coordination is involved. Optional SASL/SSL settings are applied
 * only when the resolved cluster carries them.
 *
 * <p><strong>Bounded, fail-fast clients.</strong> The kafka-clients defaults are tuned for resilient
 * production services ({@code max.block.ms} 60s, {@code delivery.timeout.ms} 120s) — against a down
 * broker a {@code kafka.send} would silently hang for minutes, contradicting the SDK's bounded-wait
 * thesis. Both clients therefore get explicit test-grade bounds: the producer blocks at most
 * {@link #PRODUCER_MAX_BLOCK_MS} on metadata/buffer, retries a request for at most
 * {@link #PRODUCER_REQUEST_TIMEOUT_MS} and gives a send up to {@link #PRODUCER_DELIVERY_TIMEOUT_MS}
 * end-to-end; the consumer's blocking admin-style calls ({@code partitionsFor} in the arm phase) are
 * bounded by {@link #CONSUMER_DEFAULT_API_TIMEOUT_MS}.
 */
public final class DefaultKafkaClientFactory implements KafkaClientFactory {

    /** Producer bound: max time {@code send()}/{@code flush()} may block on metadata or a full buffer. */
    public static final int PRODUCER_MAX_BLOCK_MS = 10_000;

    /** Producer bound: max time to await a single broker response. */
    public static final int PRODUCER_REQUEST_TIMEOUT_MS = 10_000;

    /** Producer bound: end-to-end time budget for one send (must be &gt;= request timeout + linger). */
    public static final int PRODUCER_DELIVERY_TIMEOUT_MS = 30_000;

    /** Consumer bound: default timeout of blocking calls without an explicit timeout ({@code partitionsFor}). */
    public static final int CONSUMER_DEFAULT_API_TIMEOUT_MS = 15_000;

    @Override
    public Producer<String, String> createProducer(ResolvedKafkaCluster cluster) {
        Objects.requireNonNull(cluster, "cluster must not be null");
        Properties properties = baseProperties(cluster);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, PRODUCER_MAX_BLOCK_MS);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, PRODUCER_REQUEST_TIMEOUT_MS);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, PRODUCER_DELIVERY_TIMEOUT_MS);
        return new KafkaProducer<>(properties);
    }

    @Override
    public Consumer<String, String> createConsumer(ResolvedKafkaCluster cluster, String groupId) {
        Objects.requireNonNull(cluster, "cluster must not be null");
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        Properties properties = baseProperties(cluster);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, CONSUMER_DEFAULT_API_TIMEOUT_MS);
        return new KafkaConsumer<>(properties);
    }

    private static Properties baseProperties(ResolvedKafkaCluster cluster) {
        Properties properties = new Properties();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
        if (cluster.securityProtocol() != null && !cluster.securityProtocol().isBlank()) {
            properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, cluster.securityProtocol());
        }
        if (cluster.saslJaasConfig() != null && !cluster.saslJaasConfig().isBlank()) {
            properties.put(SaslConfigs.SASL_JAAS_CONFIG, cluster.saslJaasConfig());
        }
        return properties;
    }
}
