package ru.alfa.stand.test.kafka;

import com.jayway.jsonpath.DocumentContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.AwaitResult;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.await.TimeoutDiagnostics;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.KafkaClusterDefinition;
import ru.alfa.stand.test.core.environment.TopicDefinition;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.ResourceScope;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableResolver;

/**
 * Kafka {@link StepExecutor}: the single point of real Kafka IO to a stand (step types
 * {@code kafka.send} / {@code kafka.expect}).
 *
 * <p>Discovered via {@link java.util.ServiceLoader} (registered in {@code META-INF/services}); the class
 * is public with a public no-arg constructor for that reason. It is stateless and thread-safe — all
 * per-run state arrives through the {@link StepExecutionContext} (the variable store and the run-scoped
 * {@link ResourceScope}), so one instance is safely shared across concurrent runs.
 *
 * <p><strong>Seek-race resolution (plan §8.7).</strong> {@link #prepare} arms one consumer per topic
 * alias per run — positioned at the log end before any step runs — so a {@code kafka.expect} is already
 * listening when an earlier step (a {@code rest.post} or {@code kafka.send}) injects the SDK-owned
 * correlation id outbound. The armed consumer lives in the {@code ResourceScope} and is closed by the
 * runner.
 *
 * <p><strong>Failure semantics (plan §8.3).</strong> A missing expected message (timeout), a failed
 * JSONPath assertion or a non-JSON/empty matched value is raised as a {@link StandTestAssertionError};
 * a configuration or infrastructure problem (unknown alias, no Kafka cluster, unresolved reference,
 * HEADER-only carrier violated, transport error) is raised as a {@link StandTestException}.
 *
 * <p><strong>MVP carrier.</strong> Only the HEADER correlation carrier is implemented for both inject
 * (send) and match (expect); KEY/PAYLOAD_FIELD are a later sub-iteration (plan §4/§8.4), mirroring the
 * REST adapter's HEADER-only injection.
 */
public final class KafkaStepExecutor implements StepExecutor {

    // Mode (a) of plan §4: the blocking consumer.poll(pollTimeout) carries the pause, so the await
    // poll interval is kept near-zero (AwaitPolicy forbids exactly zero) rather than adding a second,
    // independent wait between probes.
    private static final Duration POLL_INTERVAL = Duration.ofMillis(1);
    private static final int TIMEOUT_SAMPLE_LIMIT = 5;

    private final KafkaClientFactory clientFactory;
    private final ReferenceResolver referenceResolver;
    private final Awaiter awaiter;

    /**
     * Creates an executor with the default kafka-clients factory, environment reference resolver and a
     * system-backed awaiter.
     */
    public KafkaStepExecutor() {
        this(new DefaultKafkaClientFactory(), new EnvironmentReferenceResolver(), Awaiter.create());
    }

    /**
     * Creates an executor with explicit collaborators (for tests).
     *
     * @param clientFactory the Kafka client factory
     * @param referenceResolver the cluster reference resolver
     * @param awaiter the await engine used by the expect poll loop
     */
    KafkaStepExecutor(KafkaClientFactory clientFactory, ReferenceResolver referenceResolver, Awaiter awaiter) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
        this.referenceResolver = Objects.requireNonNull(referenceResolver, "referenceResolver must not be null");
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter must not be null");
    }

    @Override
    public boolean supports(String stepType) {
        return stepType != null && stepType.startsWith(KafkaStepParameters.TYPE_PREFIX);
    }

    @Override
    public void prepare(ScenarioStep step, StepExecutionContext context) {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (!KafkaOperation.EXPECT.stepType().equals(step.type())) {
            return;
        }
        String topicAlias = KafkaStepParameters.requireString(parameters(step), KafkaStepParameters.TOPIC);
        armConsumer(topicAlias, context);
    }

    @Override
    public StepResult execute(ScenarioStep step, StepExecutionContext context) {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(context, "context must not be null");
        String type = step.type();
        if (KafkaOperation.SEND.stepType().equals(type)) {
            return executeSend(step, context);
        }
        if (KafkaOperation.EXPECT.stepType().equals(type)) {
            return executeExpect(step, context);
        }
        throw new StandTestException("KafkaStepExecutor cannot handle step type '" + type + "'");
    }

    private StepResult executeSend(ScenarioStep step, StepExecutionContext context) {
        final Instant startedAt = Instant.now();
        Map<String, Object> parameters = parameters(step);
        EnvironmentDefinition environment = environment(context);
        String topicAlias = KafkaStepParameters.requireString(parameters, KafkaStepParameters.TOPIC);
        TopicDefinition topic = topic(environment, topicAlias, context);
        KafkaClusterDefinition clusterDefinition = cluster(environment, context);
        VariableResolver resolver = context.resolver();
        String value = resolveBody(parameters, resolver);
        if (value == null) {
            throw new StandTestException("A kafka.send step requires '" + KafkaStepParameters.BODY + "' or '" + KafkaStepParameters.BODY_RESOURCE + "'");
        }
        String key = KafkaStepParameters.optionalString(parameters, KafkaStepParameters.KEY).map(resolver::resolve).orElse(null);
        Map<String, String> headers = resolveValues(KafkaStepParameters.stringMap(parameters, KafkaStepParameters.HEADERS), resolver);
        injectCorrelationId(parameters, topic, topicAlias, headers, context);
        ResolvedKafkaCluster cluster = resolveClusterReferences(clusterDefinition);
        RecordMetadata metadata = send(cluster, topic.name(), key, value, headers);
        return sendSuccess(step, startedAt, topicAlias, topic.name(), key, metadata);
    }

    private StepResult executeExpect(ScenarioStep step, StepExecutionContext context) {
        final Instant startedAt = Instant.now();
        Map<String, Object> parameters = parameters(step);
        EnvironmentDefinition environment = environment(context);
        String topicAlias = KafkaStepParameters.requireString(parameters, KafkaStepParameters.TOPIC);
        TopicDefinition topic = topic(environment, topicAlias, context);
        ArmedConsumer armed = armedConsumer(context, topicAlias);
        VariableResolver resolver = context.resolver();
        String expectedCorrelationId = expectedCorrelationId(parameters, topic, topicAlias, context);
        String correlationHeaderName = (expectedCorrelationId == null) ? null : topic.correlation().name();
        String keyDiscriminator = KafkaStepParameters.optionalString(parameters, KafkaStepParameters.KEY).map(resolver::resolve).orElse(null);
        Predicate<ConsumerRecord<String, String>> selection = record -> matches(record, correlationHeaderName, expectedCorrelationId, keyDiscriminator);

        Duration pollTimeout = Duration.ofMillis(KafkaStepParameters.positiveMillis(parameters, KafkaStepParameters.POLL_TIMEOUT_MILLIS, KafkaStepParameters.DEFAULT_POLL_TIMEOUT_MILLIS));
        AwaitPolicy policy = AwaitPolicy.builder("kafka.expect " + topicAlias)
                .timeout(Duration.ofMillis(KafkaStepParameters.positiveMillis(parameters, KafkaStepParameters.TIMEOUT_MILLIS, KafkaStepParameters.DEFAULT_TIMEOUT_MILLIS)))
                .pollInterval(POLL_INTERVAL)
                .ignoreExceptions(false)
                .build();
        AwaitResult<Optional<ConsumerRecord<String, String>>> result = this.awaiter.await(
                policy, () -> armed.pollAndSelect(pollTimeout, selection), Optional::isPresent);
        ConsumerRecord<String, String> record = result
                .orElseThrow(diagnostics -> timeout(diagnostics, topicAlias, armed, correlationHeaderName, expectedCorrelationId, keyDiscriminator))
                .orElseThrow();

        List<KafkaAssertion> assertions = KafkaStepParameters.assertions(parameters);
        List<KafkaCapture> captures = KafkaStepParameters.captures(parameters);
        if (!assertions.isEmpty() || !captures.isEmpty()) {
            DocumentContext document = MessageAssertions.parse(record.value());
            MessageAssertions.verify(assertions, document);
            MessageAssertions.applyCaptures(captures, document, context.variableStore());
        }
        return expectSuccess(step, startedAt, topicAlias, armed, record);
    }

    private void armConsumer(String topicAlias, StepExecutionContext context) {
        ResourceScope scope = context.resourceScope();
        if (scope.contains(topicAlias)) {
            return;
        }
        EnvironmentDefinition environment = environment(context);
        TopicDefinition topic = topic(environment, topicAlias, context);
        ResolvedKafkaCluster cluster = resolveClusterReferences(cluster(environment, context));
        String groupId = "stand-test-" + context.scenarioContext().testRunId().value() + "-" + topicAlias;
        Consumer<String, String> consumer = this.clientFactory.createConsumer(cluster, groupId);
        ArmedConsumer armed = new ArmedConsumer(consumer, topicAlias, topic.name());
        // Register before arming, so a failure during positioning still hands the consumer to the
        // runner's ResourceScope.closeAll() and never leaks it.
        scope.register(topicAlias, armed);
        try {
            armed.arm();
        } catch (StandTestException alreadyDescribed) {
            throw alreadyDescribed;
        } catch (RuntimeException failure) {
            throw new StandTestException("Failed to arm Kafka consumer for topic '" + topicAlias + "'", failure);
        }
    }

    private ArmedConsumer armedConsumer(StepExecutionContext context, String topicAlias) {
        return context.resourceScope().get(topicAlias)
                .filter(ArmedConsumer.class::isInstance)
                .map(ArmedConsumer.class::cast)
                .orElseThrow(() -> new StandTestException("No armed Kafka consumer for topic '" + topicAlias + "'; the runner's prepare phase did not arm it (plan §8.7)"));
    }

    private static EnvironmentDefinition environment(StepExecutionContext context) {
        String environment = context.scenarioContext().environment();
        return context.environmentRegistry().environment(environment)
                .orElseThrow(() -> new StandTestException("Environment '" + environment + "' is not whitelisted"));
    }

    private static TopicDefinition topic(EnvironmentDefinition environment, String topicAlias, StepExecutionContext context) {
        return environment.topic(topicAlias)
                .orElseThrow(() -> new StandTestException("Topic '" + topicAlias + "' is not whitelisted in environment '" + context.scenarioContext().environment() + "'"));
    }

    private static KafkaClusterDefinition cluster(EnvironmentDefinition environment, StepExecutionContext context) {
        KafkaClusterDefinition cluster = environment.kafkaCluster();
        if (cluster == null) {
            throw new StandTestException("Environment '" + context.scenarioContext().environment() + "' has no Kafka cluster configured");
        }
        return cluster;
    }

    private ResolvedKafkaCluster resolveClusterReferences(KafkaClusterDefinition cluster) {
        String bootstrapServers = this.referenceResolver.resolve(cluster.bootstrapServersRef());
        String securityProtocol = cluster.securityProtocolReference().map(this.referenceResolver::resolve).orElse(null);
        String saslJaasConfig = cluster.saslJaasConfigReference().map(this.referenceResolver::resolve).orElse(null);
        return new ResolvedKafkaCluster(bootstrapServers, securityProtocol, saslJaasConfig);
    }

    private static void injectCorrelationId(Map<String, Object> parameters, TopicDefinition topic, String topicAlias, Map<String, String> headers, StepExecutionContext context) {
        if (!KafkaStepParameters.flag(parameters, KafkaStepParameters.INJECT_CORRELATION_ID)) {
            return;
        }
        CorrelationConfig correlation = topic.correlation();
        if (correlation == null || correlation.source() != CorrelationSource.HEADER) {
            throw new StandTestException("Correlation id injection was requested for topic '" + topicAlias + "', but only the HEADER carrier is implemented (KEY/PAYLOAD_FIELD are a later sub-iteration)");
        }
        headers.put(correlation.name(), context.scenarioContext().correlationId().value());
    }

    private static String expectedCorrelationId(Map<String, Object> parameters, TopicDefinition topic, String topicAlias, StepExecutionContext context) {
        if (!KafkaStepParameters.flag(parameters, KafkaStepParameters.CORRELATION_FROM_CONTEXT)) {
            return null;
        }
        CorrelationConfig correlation = topic.correlation();
        if (correlation == null || correlation.source() != CorrelationSource.HEADER) {
            throw new StandTestException("kafka.expect '" + topicAlias + "' requested correlationIdFromContext, but only the HEADER carrier is implemented (KEY/PAYLOAD_FIELD are a later sub-iteration)");
        }
        return context.scenarioContext().correlationId().value();
    }

    private static boolean matches(ConsumerRecord<String, String> record, String correlationHeaderName, String expectedCorrelationId, String keyDiscriminator) {
        if (expectedCorrelationId != null && !expectedCorrelationId.equals(headerValue(record, correlationHeaderName))) {
            return false;
        }
        return keyDiscriminator == null || keyDiscriminator.equals(record.key());
    }

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return (header == null || header.value() == null) ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private RecordMetadata send(ResolvedKafkaCluster cluster, String topic, String key, String value, Map<String, String> headers) {
        List<Header> recordHeaders = new ArrayList<>();
        headers.forEach((name, headerValue) -> recordHeaders.add(new RecordHeader(name, headerValue.getBytes(StandardCharsets.UTF_8))));
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, key, value, recordHeaders);
        try (Producer<String, String> producer = this.clientFactory.createProducer(cluster)) {
            Future<RecordMetadata> future = producer.send(record);
            producer.flush();
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StandTestException("Interrupted while sending to Kafka topic '" + topic + "'", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = (failed.getCause() != null) ? failed.getCause() : failed;
            throw new StandTestException("Failed to send to Kafka topic '" + topic + "'", cause);
        }
    }

    private static Map<String, String> resolveValues(Map<String, String> source, VariableResolver resolver) {
        Map<String, String> resolved = new LinkedHashMap<>();
        source.forEach((name, value) -> resolved.put(name, resolver.resolve(value)));
        return resolved;
    }

    private static String resolveBody(Map<String, Object> parameters, VariableResolver resolver) {
        Optional<String> resource = KafkaStepParameters.optionalString(parameters, KafkaStepParameters.BODY_RESOURCE);
        Optional<String> inline = KafkaStepParameters.optionalString(parameters, KafkaStepParameters.BODY);
        if (resource.isPresent() && inline.isPresent()) {
            throw new StandTestException("A Kafka step must set either '" + KafkaStepParameters.BODY + "' or '" + KafkaStepParameters.BODY_RESOURCE + "', not both");
        }
        if (resource.isPresent()) {
            return resolver.resolve(readResource(resource.get()));
        }
        return inline.map(resolver::resolve).orElse(null);
    }

    private static String readResource(String resourcePath) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = KafkaStepExecutor.class.getClassLoader();
        }
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new StandTestException("Message body resource not found on classpath: '" + resourcePath + "'");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StandTestException("Failed to read message body resource '" + resourcePath + "'", failure);
        }
    }

    private static Map<String, Object> parameters(ScenarioStep step) {
        if (step instanceof GenericStep generic) {
            return generic.parameters();
        }
        throw new StandTestException("KafkaStepExecutor requires a GenericStep produced by KafkaStep, but got: " + step.getClass().getName());
    }

    private static StandTestAssertionError timeout(TimeoutDiagnostics diagnostics, String topicAlias, ArmedConsumer armed, String correlationHeaderName, String correlationId, String key) {
        return new StandTestAssertionError("kafka.expect '" + topicAlias + "' did not receive a matching message: " + diagnostics.summary()
                + " (realTopic=" + armed.realTopic() + ", partitions=" + armed.partitions() + ", messagesSeen=" + armed.messagesSeen()
                + ", selection=[correlationId=" + correlationId + ", key=" + key + "]"
                + ", lastMessages=" + sample(armed, correlationHeaderName) + ")");
    }

    private static List<String> sample(ArmedConsumer armed, String correlationHeaderName) {
        List<String> lines = new ArrayList<>();
        for (ConsumerRecord<String, String> record : armed.recentlySeen(TIMEOUT_SAMPLE_LIMIT)) {
            StringBuilder line = new StringBuilder()
                    .append(record.partition()).append('@').append(record.offset())
                    .append(" key=").append(record.key());
            if (correlationHeaderName != null) {
                line.append(' ').append(correlationHeaderName).append('=').append(headerValue(record, correlationHeaderName));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static StepResult sendSuccess(ScenarioStep step, Instant startedAt, String topicAlias, String realTopic, String key, RecordMetadata metadata) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("kafka.operation", "send");
        diagnostics.put("kafka.topic", topicAlias);
        diagnostics.put("kafka.realTopic", realTopic);
        diagnostics.put("kafka.partition", metadata.partition());
        diagnostics.put("kafka.offset", metadata.offset());
        if (key != null) {
            diagnostics.put("kafka.key", key);
        }
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, Instant.now(), null, diagnostics);
    }

    private static StepResult expectSuccess(ScenarioStep step, Instant startedAt, String topicAlias, ArmedConsumer armed, ConsumerRecord<String, String> record) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("kafka.operation", "expect");
        diagnostics.put("kafka.topic", topicAlias);
        diagnostics.put("kafka.realTopic", armed.realTopic());
        diagnostics.put("kafka.partition", record.partition());
        diagnostics.put("kafka.offset", record.offset());
        diagnostics.put("kafka.messagesSeen", armed.messagesSeen());
        if (record.key() != null) {
            diagnostics.put("kafka.key", record.key());
        }
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, Instant.now(), null, diagnostics);
    }
}
