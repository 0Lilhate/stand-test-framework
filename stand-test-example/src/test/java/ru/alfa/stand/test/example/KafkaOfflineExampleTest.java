package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.kafka.KafkaClientFactory;
import ru.alfa.stand.test.kafka.KafkaStep;
import ru.alfa.stand.test.kafka.ResolvedKafkaCluster;

/**
 * The OFFLINE Kafka composition example — the counterpart to {@link KafkaExampleTest} (which needs a live
 * broker and is excluded from the default run). It drives {@code kafka.send}/{@code kafka.expect} end-to-end
 * with no broker by injecting a {@link KafkaClientFactory} test-double (the sanctioned public seam) that
 * hands the executor an in-JVM Apache {@code MockProducer}/{@code MockConsumer}. So the default offline
 * {@code ./gradlew build} now exercises Kafka round-trip too, not only REST/DB/gRPC.
 *
 * <p>The double is a loopback: the produced record (with the SDK correlation id injected as a header — on
 * by default because the topic declares a HEADER carrier) is echoed onto the same topic's consumer, so
 * {@code kafka.expect} — whose consumer the runner's prepare phase armed at the log end before the send —
 * matches it by that correlation id, asserts a JSON field and captures another. This is the offline
 * analogue of a broker storing the record and the consumer reading it back; no downstream service runs.
 */
class KafkaOfflineExampleTest {

    @Test
    @DisplayName("offline: kafka.send injects correlation; kafka.expect matches it via an in-JVM loopback double, asserts and captures")
    void kafkaOfflineRoundTrip() {
        MockConsumer<String, String> consumer = armedConsumer(ExampleStand.TOPIC_NAME);
        KafkaClientFactory factory = new LoopbackKafkaClientFactory(consumer, ExampleStand.TOPIC_NAME);
        StandClient stand = ExampleStand.kafkaStand(ExampleStand.kafkaRegistry(), factory);

        Scenario scenario = Scenario.builder("kafka-offline-example")
                .environment(ExampleStand.ENVIRONMENT)
                // correlationId is injected by default: the topic declares a HEADER correlation carrier.
                .step(KafkaStep.send(ExampleStand.TOPIC)
                        .body("{\"status\":\"SUCCESS\",\"entityId\":\"kafka-1\"}")
                        .build())
                .step(KafkaStep.expect(ExampleStand.TOPIC)
                        .correlationIdFromContext()
                        .assertPath("$.status", "SUCCESS")
                        .capture("entityId", "$.entityId")
                        .withinSeconds(10)
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).hasSize(2);
    }

    /**
     * A single-partition {@code MockConsumer} whose log end is at 0, so the executor's {@code seekToEnd}
     * positions at start-from-now; the loopback then echoes records at offset 0+ which the consumer polls.
     */
    private static MockConsumer<String, String> armedConsumer(String realTopic) {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
        consumer.updatePartitions(realTopic, List.of(new PartitionInfo(realTopic, 0, (Node) null, new Node[0], new Node[0])));
        TopicPartition partition = new TopicPartition(realTopic, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, 0L));
        return consumer;
    }

    /**
     * In-JVM loopback {@link KafkaClientFactory}: each produced record is echoed onto the same topic's
     * consumer (correlation header + value preserved), so {@code kafka.expect} finds it by the run's
     * correlation id. No real broker, no system under test.
     */
    private static final class LoopbackKafkaClientFactory implements KafkaClientFactory {

        private final MockConsumer<String, String> consumer;
        private final String realTopic;
        private long echoOffset;

        LoopbackKafkaClientFactory(MockConsumer<String, String> consumer, String realTopic) {
            this.consumer = consumer;
            this.realTopic = realTopic;
        }

        @Override
        public Producer<String, String> createProducer(ResolvedKafkaCluster cluster) {
            return new MockProducer<>(true, (Partitioner) null, new StringSerializer(), new StringSerializer()) {
                @Override
                public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
                    RecordHeaders headers = new RecordHeaders();
                    for (Header header : record.headers()) {
                        headers.add(new RecordHeader(header.key(), header.value()));
                    }
                    LoopbackKafkaClientFactory.this.consumer.addRecord(new ConsumerRecord<>(
                            LoopbackKafkaClientFactory.this.realTopic, 0, LoopbackKafkaClientFactory.this.echoOffset++, 0L,
                            TimestampType.CREATE_TIME, -1, -1, record.key(), record.value(), headers, Optional.empty()));
                    return super.send(record, callback);
                }
            };
        }

        @Override
        public Consumer<String, String> createConsumer(ResolvedKafkaCluster cluster, String groupId) {
            return this.consumer;
        }
    }
}
