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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.AwaitResult;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.await.TimeoutDiagnostics;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.KafkaClusterDefinition;
import ru.alfa.stand.test.core.environment.TopicDefinition;
import ru.alfa.stand.test.core.exception.DiagnosticAssertionError;
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

    private static final Logger LOG = LoggerFactory.getLogger(KafkaStepExecutor.class);

    // Mode (a) of plan §4: the blocking consumer.poll(pollTimeout) carries the pause, so the await
    // poll interval is kept near-zero (AwaitPolicy forbids exactly zero) rather than adding a second,
    // independent wait between probes.
    /**
     * Namespace prefix for this adapter's {@link ResourceScope} keys, so a topic alias can never collide
     * with another adapter's resource registered under the same logical alias in one run (mirrors the DB
     * adapter's {@code db.datasource:} convention).
     */
    private static final String CONSUMER_KEY_PREFIX = "kafka.consumer:";

    /**
     * Backstop for the synchronous send-acknowledgement wait, above the producer's own bounds
     * ({@code max.block.ms} + {@code delivery.timeout.ms} in {@link DefaultKafkaClientFactory}), so the
     * wait is bounded even with a custom {@link KafkaClientFactory} that leaves the client defaults.
     */
    private static final long SEND_BACKSTOP_MILLIS = 60_000L;

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
     * Creates an executor with a custom {@link KafkaClientFactory} (default environment reference resolver
     * and a system-backed awaiter) — a sanctioned test-double seam for driving {@code kafka.send}/
     * {@code kafka.expect} against an in-JVM broker double (an embedded broker or the Apache
     * {@code MockProducer}/{@code MockConsumer}) with NO real broker, exactly as the SDK's own tests and the
     * offline example do. The factory only creates clients; all SDK logic (alias resolution, correlation,
     * per-run selection, assertions) still runs in the executor, so this is not a raw-client bypass.
     *
     * @param clientFactory the Kafka client factory (e.g. an in-JVM / mock double)
     */
    public KafkaStepExecutor(KafkaClientFactory clientFactory) {
        this(clientFactory, new EnvironmentReferenceResolver(), Awaiter.create());
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
        Map<String, Object> parameters = parameters(step);
        String topicAlias = KafkaStepParameters.requireString(parameters, KafkaStepParameters.TOPIC);
        // Fail-closed BEFORE any broker IO (plan §15): reject an undiscriminated or constant-key expect here,
        // in prepare(), rather than after armConsumer() has opened a real consumer — mirroring the DB path,
        // which enforces the write-guard before opening a connection.
        requirePerRunDiscriminator(parameters, topicAlias);
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
        KafkaClusterDefinition clusterDefinition = cluster(environment, topic, context);
        VariableResolver resolver = context.resolver();
        String value = resolveBody(parameters, resolver);
        if (value == null) {
            throw new StandTestException("A kafka.send step requires '" + KafkaStepParameters.BODY + "' or '" + KafkaStepParameters.BODY_RESOURCE + "'");
        }
        String key = KafkaStepParameters.optionalString(parameters, KafkaStepParameters.KEY).map(resolver::resolve).orElse(null);
        Map<String, String> headers = resolveValues(KafkaStepParameters.stringMap(parameters, KafkaStepParameters.HEADERS), resolver);
        injectCorrelationId(parameters, topic, topicAlias, headers, context);
        ResolvedKafkaCluster cluster = resolveClusterReferences(clusterDefinition);
        RecordMetadata metadata = send(cluster, topicAlias, topic.name(), key, value, headers);
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
        // Defense-in-depth re-enforcement of the builder rule (plan §15) for surfaces that bypass KafkaStep
        // (YAML/AI documents, raw GenericStep params, or a direct execute() without prepare()). prepare()
        // already applied this before arming; repeating it keeps execute() safe in isolation.
        requirePerRunDiscriminator(parameters, topicAlias);
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
        LOG.debug("Kafka expect on topic '{}': messagesSeen={}", topicAlias, armed.messagesSeen());
        return expectSuccess(step, startedAt, topicAlias, armed, record);
    }

    private void armConsumer(String topicAlias, StepExecutionContext context) {
        ResourceScope scope = context.resourceScope();
        if (scope.contains(CONSUMER_KEY_PREFIX + topicAlias)) {
            return;
        }
        EnvironmentDefinition environment = environment(context);
        TopicDefinition topic = topic(environment, topicAlias, context);
        ResolvedKafkaCluster cluster = resolveClusterReferences(cluster(environment, topic, context));
        String groupId = "stand-test-" + context.scenarioContext().testRunId().value() + "-" + topicAlias;
        Consumer<String, String> consumer = this.clientFactory.createConsumer(cluster, groupId);
        // The topic's HEADER correlation carrier (if any) lets the ArmedConsumer evict other runs' records
        // from a busy shared topic; a topic with no HEADER carrier gets no eviction (null header name).
        CorrelationConfig topicCorrelation = topic.correlation();
        String correlationHeaderName = (topicCorrelation != null && topicCorrelation.source() == CorrelationSource.HEADER) ? topicCorrelation.name() : null;
        ArmedConsumer armed = new ArmedConsumer(consumer, topicAlias, topic.name(), correlationHeaderName, context.scenarioContext().correlationId().value());
        // Register before arming, so a failure during positioning still hands the consumer to the
        // runner's ResourceScope.closeAll() and never leaks it.
        scope.register(CONSUMER_KEY_PREFIX + topicAlias, armed);
        try {
            armed.arm();
        } catch (StandTestException alreadyDescribed) {
            throw alreadyDescribed;
        } catch (RuntimeException failure) {
            throw new StandTestException("Failed to arm Kafka consumer for topic '" + topicAlias + "'", failure);
        }
        LOG.debug("Kafka armed consumer for topic '{}' partition(s) {}", topicAlias, armed.partitions());
    }

    private ArmedConsumer armedConsumer(StepExecutionContext context, String topicAlias) {
        return context.resourceScope().get(CONSUMER_KEY_PREFIX + topicAlias)
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

    /**
     * Resolves the Kafka cluster the given topic lives on: a topic naming a {@code cluster} alias uses
     * that named cluster from the environment's whitelist; a topic without one uses the environment's
     * default {@code kafkaCluster}. The named lookup cannot fail for a registry built through the core
     * {@code EnvironmentDefinition} (it validates topic→cluster references at construction) — the throw
     * is a defensive net for hand-built registries.
     */
    private static KafkaClusterDefinition cluster(EnvironmentDefinition environment, TopicDefinition topic, StepExecutionContext context) {
        if (topic.cluster() != null) {
            return environment.kafkaCluster(topic.cluster())
                    .orElseThrow(() -> new StandTestException("Kafka cluster '" + topic.cluster() + "' (named by topic '" + topic.alias()
                            + "') is not whitelisted in environment '" + context.scenarioContext().environment() + "'"));
        }
        KafkaClusterDefinition cluster = environment.kafkaCluster();
        if (cluster == null) {
            throw new StandTestException("Environment '" + context.scenarioContext().environment() + "' has no default Kafka cluster configured"
                    + " (topic '" + topic.alias() + "' names no cluster alias)");
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
        CorrelationConfig correlation = topic.correlation();
        boolean hasHeaderCarrier = correlation != null && correlation.source() == CorrelationSource.HEADER;
        // Default-on: inject when the topic declares a HEADER carrier, unless the step opted in/out
        // explicitly. The SDK owns correlationId (plan §8), so end-to-end traceability is the safe default
        // rather than a builder call an AI author can silently forget.
        boolean shouldInject = KafkaStepParameters.injectCorrelationIdFlag(parameters).orElse(hasHeaderCarrier);
        if (!shouldInject) {
            return;
        }
        if (!hasHeaderCarrier) {
            // Only reachable when the step forced injection on a topic that declares no HEADER carrier.
            throw new StandTestException("Correlation id injection was requested for topic '" + topicAlias + "', but only the HEADER carrier is implemented (KEY/PAYLOAD_FIELD are a later sub-iteration)");
        }
        headers.put(correlation.name(), context.scenarioContext().correlationId().value());
    }

    private static void requirePerRunDiscriminator(Map<String, Object> parameters, String topicAlias) {
        if (KafkaStepParameters.flag(parameters, KafkaStepParameters.CORRELATION_FROM_CONTEXT)) {
            // The SDK-owned per-run correlationId is the discriminator; a key (if any) only narrows further.
            return;
        }
        String rawKey = KafkaStepParameters.optionalString(parameters, KafkaStepParameters.KEY).orElse(null);
        if (rawKey == null) {
            throw new StandTestException("A kafka.expect on topic '" + topicAlias
                    + "' has no per-run discriminator (correlationIdFromContext / key) and would match any message on a shared topic — refused as parallel-unsafe (plan §15)");
        }
        // The RAW (pre-resolution) key must carry a ${...} placeholder to be per-run-derived; a constant key
        // is not per-run-unique, so concurrent runs would match each other's messages.
        if (!rawKey.contains("${")) {
            throw new StandTestException("A kafka.expect on topic '" + topicAlias + "' uses a constant key '" + rawKey
                    + "' as its sole discriminator, which is not per-run-unique — concurrent runs would match each other's messages. Use a ${testRunId}-derived key or correlationIdFromContext() (plan §15)");
        }
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

    private RecordMetadata send(ResolvedKafkaCluster cluster, String topicAlias, String topic, String key, String value, Map<String, String> headers) {
        LOG.debug("Kafka produce to topic '{}'", topic);
        List<Header> recordHeaders = new ArrayList<>();
        headers.forEach((name, headerValue) -> recordHeaders.add(new RecordHeader(name, headerValue.getBytes(StandardCharsets.UTF_8))));
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, key, value, recordHeaders);
        try (Producer<String, String> producer = this.clientFactory.createProducer(cluster)) {
            Future<RecordMetadata> future = producer.send(record);
            producer.flush();
            // The producer's own bounds (max.block/delivery.timeout, DefaultKafkaClientFactory) fail a
            // send against a down broker first, with the richer broker-side cause; this get() timeout is
            // a backstop so a custom, unbounded KafkaClientFactory can still never hang the run.
            return future.get(SEND_BACKSTOP_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StandTestException("Interrupted while sending to Kafka topic '" + topic + "' (alias '" + topicAlias + "')", interrupted);
        } catch (TimeoutException timedOut) {
            throw new StandTestException("Sending to Kafka topic '" + topic + "' (alias '" + topicAlias + "') did not complete within "
                    + SEND_BACKSTOP_MILLIS + " ms — the broker behind the cluster reference is unreachable or not acknowledging", timedOut);
        } catch (ExecutionException failed) {
            Throwable cause = (failed.getCause() != null) ? failed.getCause() : failed;
            throw new StandTestException("Failed to send to Kafka topic '" + topic + "' (alias '" + topicAlias + "')", cause);
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
        LOG.debug("Kafka expect on topic '{}': no message matched, messagesSeen={}", topicAlias, armed.messagesSeen());
        // messagesSeen is the number a reader reaches for first — zero means nothing arrived at all, non-zero
        // means the selection did not match — so it becomes a row of its own rather than a fragment of the
        // sentence. lastMessages stays out of the map: it is a sample of bodies, and the map is rendered
        // verbatim into the report.
        Map<String, Object> reportable = diagnostics
                .withAttribute("kafka.topic", topicAlias)
                .withAttribute("kafka.realTopic", armed.realTopic())
                .withAttribute("kafka.messagesSeen", armed.messagesSeen())
                .toMap();
        return new DiagnosticAssertionError("kafka.expect '" + topicAlias + "' did not receive a matching message: " + diagnostics.summary()
                + " (realTopic=" + armed.realTopic() + ", partitions=" + armed.partitions() + ", messagesSeen=" + armed.messagesSeen()
                + ", selection=[correlationId=" + correlationId + ", key=" + key + "]"
                + ", lastMessages=" + sample(armed, correlationHeaderName) + ")", reportable);
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
