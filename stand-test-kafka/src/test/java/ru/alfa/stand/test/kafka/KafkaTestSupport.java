package ru.alfa.stand.test.kafka;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.KafkaClusterDefinition;
import ru.alfa.stand.test.core.environment.TopicDefinition;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Shared fixtures for the Kafka adapter tests: a whitelisted environment with a Kafka cluster and
 * topics, broker-free {@code MockConsumer} helpers, and a ready-made {@link StepExecutionContext}.
 */
final class KafkaTestSupport {

    static final String ENVIRONMENT = "ift";
    static final String REQUEST_ALIAS = "request-topic";
    static final String REQUEST_NAME = "pakt.request.ift";
    static final String RESPONSE_ALIAS = "response-topic";
    static final String RESPONSE_NAME = "pakt.response.ift";
    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String BOOTSTRAP_REF = "KAFKA_BOOTSTRAP_SERVERS";

    private KafkaTestSupport() {
    }

    static TopicDefinition headerTopic(String alias, String name) {
        return new TopicDefinition(alias, name, new CorrelationConfig(CorrelationSource.HEADER, CORRELATION_HEADER));
    }

    static TopicDefinition topicWithoutCorrelation(String alias, String name) {
        return new TopicDefinition(alias, name, null);
    }

    static EnvironmentRegistry registry(TopicDefinition... topics) {
        return registry(KafkaClusterDefinition.of(BOOTSTRAP_REF), topics);
    }

    static EnvironmentRegistry registry(KafkaClusterDefinition cluster, TopicDefinition... topics) {
        Map<String, TopicDefinition> byAlias = new java.util.LinkedHashMap<>();
        for (TopicDefinition topic : topics) {
            byAlias.put(topic.alias(), topic);
        }
        EnvironmentDefinition environment = new EnvironmentDefinition(ENVIRONMENT, Map.of(), byAlias, Map.of(), Map.of(), cluster);
        return new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
    }

    static EnvironmentRegistry registryWithoutCluster(TopicDefinition topic) {
        EnvironmentDefinition environment = new EnvironmentDefinition(ENVIRONMENT, Map.of(), Map.of(topic.alias(), topic), Map.of(), Map.of());
        return new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
    }

    static StepExecutionContext context(EnvironmentRegistry registry, VariableStore store) {
        ScenarioContext scenarioContext = ScenarioContext.start(ScenarioId.of("scenario-1"), ENVIRONMENT);
        return new StepExecutionContext(scenarioContext, store, registry, NoOpReportingEventPublisher.INSTANCE);
    }

    static MockConsumer<String, String> emptyConsumer(String realTopic) {
        return consumer(realTopic, 0L);
    }

    /**
     * A single-partition {@code MockConsumer} whose log end is at {@code endOffset} — i.e. the topic
     * already holds {@code endOffset} records before the consumer arms, so {@code seekToEnd} positions
     * past that backlog (start-from-now). A regression that seeked to the beginning would land at 0 and
     * replay the backlog, which lets a test discriminate the two.
     */
    static MockConsumer<String, String> consumer(String realTopic, long endOffset) {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
        consumer.updatePartitions(realTopic, List.of(new PartitionInfo(realTopic, 0, (Node) null, new Node[0], new Node[0])));
        TopicPartition partition = new TopicPartition(realTopic, 0);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.updateEndOffsets(Map.of(partition, endOffset));
        return consumer;
    }

    static ConsumerRecord<String, String> record(String topic, long offset, String key, String value, Map<String, String> headers) {
        RecordHeaders recordHeaders = new RecordHeaders();
        headers.forEach((name, headerValue) -> recordHeaders.add(new RecordHeader(name, headerValue.getBytes(StandardCharsets.UTF_8))));
        return new ConsumerRecord<>(topic, 0, offset, 0L, TimestampType.CREATE_TIME, -1, -1, key, value, recordHeaders, Optional.empty());
    }
}
