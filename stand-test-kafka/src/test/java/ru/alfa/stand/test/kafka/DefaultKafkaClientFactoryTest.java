package ru.alfa.stand.test.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultKafkaClientFactoryTest {

    private static final ResolvedKafkaCluster PLAIN = new ResolvedKafkaCluster("localhost:9092", null, null);

    private final DefaultKafkaClientFactory factory = new DefaultKafkaClientFactory();

    @Test
    @DisplayName("creates a String producer for a plain cluster")
    void createsProducer() {
        try (Producer<String, String> producer = this.factory.createProducer(PLAIN)) {
            assertThat(producer).isNotNull();
        }
    }

    @Test
    @DisplayName("creates a String consumer bound to the given group id")
    void createsConsumer() {
        try (Consumer<String, String> consumer = this.factory.createConsumer(PLAIN, "stand-test-run-1-response-topic")) {
            assertThat(consumer).isNotNull();
        }
    }

    @Test
    @DisplayName("applies optional security settings when present")
    void appliesSecuritySettings() {
        ResolvedKafkaCluster secured = new ResolvedKafkaCluster("localhost:9092", "PLAINTEXT", "jaas-config-reference-value");
        try (Producer<String, String> producer = this.factory.createProducer(secured)) {
            assertThat(producer).isNotNull();
        }
    }

    @Test
    @DisplayName("rejects a null cluster and a blank group id")
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> this.factory.createProducer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.factory.createConsumer(PLAIN, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
