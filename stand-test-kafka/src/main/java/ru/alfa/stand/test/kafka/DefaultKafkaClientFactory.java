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
 */
public final class DefaultKafkaClientFactory implements KafkaClientFactory {

    @Override
    public Producer<String, String> createProducer(ResolvedKafkaCluster cluster) {
        Objects.requireNonNull(cluster, "cluster must not be null");
        Properties properties = baseProperties(cluster);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
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
