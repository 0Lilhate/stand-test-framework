package ru.alfa.stand.test.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * Lazy builder for a Kafka step ({@code kafka.send} or {@code kafka.expect}).
 *
 * <p>It assembles configuration only and performs no IO — {@link #build()} materialises an immutable
 * core {@link GenericStep} whose parameter map follows the {@link KafkaStepParameters} schema. The real
 * produce/consume happens later, inside {@link KafkaStepExecutor}, so the builder can never bypass the
 * validator or the run pipeline (plan §8.1).
 *
 * <p>Typical use (each {@code .build()} result is passed to {@code Scenario.Builder.step(...)}):
 * <pre>{@code
 * KafkaStep.send("request-topic")
 *         .bodyFromResource("fixtures/event.json")
 *         .key("${requestId}")
 *         .injectCorrelationId()                  // SDK-owned correlationId -> carrier from topic config
 *         .build()
 *
 * KafkaStep.expect("response-topic")
 *         .correlationIdFromContext()             // select by SDK-owned correlationId
 *         .withinSeconds(30)
 *         .assertPath("$.status", "SUCCESS")
 *         .capture("entityId", "$.entityId")
 *         .build()
 * }</pre>
 */
public final class KafkaStep {

    private final KafkaOperation operation;
    private final String topic;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final List<KafkaAssertion> assertions = new ArrayList<>();
    private final List<KafkaCapture> captures = new ArrayList<>();
    private String id;
    private String body;
    private String bodyResource;
    private String key;
    private Boolean injectCorrelationId;
    private boolean correlationIdFromContext;
    private Long timeoutMillis;
    private Long pollTimeoutMillis;

    private KafkaStep(KafkaOperation operation, String topic) {
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.topic = requireNonBlank(topic, "topic");
    }

    /**
     * Starts a {@code kafka.send} step publishing to the given topic alias.
     *
     * @param topic the logical topic alias
     * @return a new builder
     */
    public static KafkaStep send(String topic) {
        return new KafkaStep(KafkaOperation.SEND, topic);
    }

    /**
     * Starts a {@code kafka.expect} step waiting for a message on the given topic alias.
     *
     * @param topic the logical topic alias
     * @return a new builder
     */
    public static KafkaStep expect(String topic) {
        return new KafkaStep(KafkaOperation.EXPECT, topic);
    }

    /**
     * Sets an explicit step id (otherwise a readable {@code "<OPERATION> <topic>"} id is derived).
     *
     * @param id the unique step id
     * @return this builder
     */
    public KafkaStep id(String id) {
        this.id = requireNonBlank(id, "id");
        return this;
    }

    /**
     * Sets an inline message value (JSON as a string); it may contain {@code ${...}} placeholders.
     *
     * @param inlineBody the message value
     * @return this builder
     */
    public KafkaStep body(String inlineBody) {
        this.body = Objects.requireNonNull(inlineBody, "body must not be null");
        return this;
    }

    /**
     * Sets the message value from a classpath resource; its content may contain {@code ${...}}
     * placeholders.
     *
     * @param classpathResource the classpath resource path
     * @return this builder
     */
    public KafkaStep bodyFromResource(String classpathResource) {
        this.bodyResource = requireNonBlank(classpathResource, "classpathResource");
        return this;
    }

    /**
     * Sets the message key; on {@code send} it is the partitioning key, on {@code expect} a selection
     * discriminator. The value may contain {@code ${...}} placeholders.
     *
     * @param messageKey the message key
     * @return this builder
     */
    public KafkaStep key(String messageKey) {
        this.key = Objects.requireNonNull(messageKey, "key must not be null");
        return this;
    }

    /**
     * Adds a message header; the value may contain {@code ${...}} placeholders.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    public KafkaStep header(String name, String value) {
        this.headers.put(requireNonBlank(name, "header name"), Objects.requireNonNull(value, "header value must not be null"));
        return this;
    }

    /**
     * Forces injection of the SDK-owned correlation id into the outbound message, using the carrier
     * configured for the target topic ({@code kafka.send} only).
     *
     * <p>Injection is <strong>on by default</strong> whenever the resolved topic declares a HEADER
     * correlation carrier, so this call is only needed to be explicit; use {@link #injectCorrelationId(boolean)
     * injectCorrelationId(false)} to opt out.
     *
     * @return this builder
     */
    public KafkaStep injectCorrelationId() {
        return injectCorrelationId(true);
    }

    /**
     * Explicitly enables ({@code true}) or opts out of ({@code false}) correlation-id injection
     * ({@code kafka.send} only), overriding the default (inject when the topic declares a HEADER carrier).
     *
     * @param inject whether to inject the correlation id
     * @return this builder
     */
    public KafkaStep injectCorrelationId(boolean inject) {
        this.injectCorrelationId = inject;
        return this;
    }

    /**
     * Selects the awaited message by the SDK-owned correlation id ({@code kafka.expect} only).
     *
     * @return this builder
     */
    public KafkaStep correlationIdFromContext() {
        this.correlationIdFromContext = true;
        return this;
    }

    /**
     * Sets the maximum time to wait for a matching message, in seconds ({@code kafka.expect} only).
     *
     * @param seconds the timeout in seconds
     * @return this builder
     */
    public KafkaStep withinSeconds(long seconds) {
        return within(Duration.ofSeconds(seconds));
    }

    /**
     * Sets the maximum time to wait for a matching message ({@code kafka.expect} only).
     *
     * @param timeout the timeout (strictly positive)
     * @return this builder
     */
    public KafkaStep within(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be strictly positive");
        }
        this.timeoutMillis = timeout.toMillis();
        return this;
    }

    /**
     * Sets the per-probe consumer poll timeout ({@code kafka.expect} only); defaults to
     * {@link KafkaStepParameters#DEFAULT_POLL_TIMEOUT_MILLIS} when not set.
     *
     * @param pollTimeout the poll timeout (strictly positive)
     * @return this builder
     */
    public KafkaStep pollTimeout(Duration pollTimeout) {
        Objects.requireNonNull(pollTimeout, "pollTimeout must not be null");
        if (pollTimeout.isZero() || pollTimeout.isNegative()) {
            throw new IllegalArgumentException("pollTimeout must be strictly positive");
        }
        this.pollTimeoutMillis = pollTimeout.toMillis();
        return this;
    }

    /**
     * Asserts that the value at the given JSONPath in the matched message equals the expected value
     * ({@code kafka.expect} only).
     *
     * @param jsonPath the JSONPath expression
     * @param expectedValue the expected value (never null)
     * @return this builder
     */
    public KafkaStep assertPath(String jsonPath, Object expectedValue) {
        this.assertions.add(new KafkaAssertion(jsonPath, expectedValue));
        return this;
    }

    /**
     * Captures the value at the given JSONPath in the matched message into a run variable
     * ({@code kafka.expect} only).
     *
     * @param variableName the variable name
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public KafkaStep capture(String variableName, String jsonPath) {
        this.captures.add(new KafkaCapture(variableName, jsonPath));
        return this;
    }

    /**
     * Materialises the immutable core step. Performs no IO.
     *
     * @return the assembled scenario step
     */
    public ScenarioStep build() {
        if (this.body != null && this.bodyResource != null) {
            throw new IllegalStateException("Set either body(...) or bodyFromResource(...), not both");
        }
        if (this.operation == KafkaOperation.SEND) {
            validateSend();
        } else {
            validateExpect();
        }
        return new GenericStep(resolveId(), this.operation.stepType(), description(), toParameterMap());
    }

    private void validateSend() {
        if (this.body == null && this.bodyResource == null) {
            throw new IllegalStateException("A kafka.send step requires body(...) or bodyFromResource(...)");
        }
        if (this.correlationIdFromContext || !this.assertions.isEmpty() || !this.captures.isEmpty() || this.timeoutMillis != null) {
            throw new IllegalStateException("correlationIdFromContext / assertPath / capture / withinSeconds apply to kafka.expect, not kafka.send");
        }
    }

    private void validateExpect() {
        if (this.body != null || this.bodyResource != null || this.injectCorrelationId != null) {
            throw new IllegalStateException("body / bodyFromResource / injectCorrelationId apply to kafka.send, not kafka.expect");
        }
        if (!this.correlationIdFromContext) {
            if (this.key == null) {
                throw new IllegalStateException("A kafka.expect step must select messages by a per-run discriminator to stay parallel-safe (plan §15): "
                        + "call correlationIdFromContext() (the sanctioned SDK-owned selector) or set a per-run-derived key(\"${testRunId}\")");
            }
            if (!this.key.contains("${")) {
                throw new IllegalStateException("A kafka.expect key used as the sole discriminator must be per-run-derived — it must contain a ${...} placeholder (for example key(\"${testRunId}\")): "
                        + "a constant key is not parallel-safe because two concurrent runs would match each other's messages. Prefer correlationIdFromContext() for the SDK-owned unique id.");
            }
        }
    }

    private String resolveId() {
        return (this.id != null) ? this.id : this.operation.name() + " " + this.topic;
    }

    private String description() {
        return this.operation.name() + " " + this.topic;
    }

    private Map<String, Object> toParameterMap() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(KafkaStepParameters.TOPIC, this.topic);
        parameters.put(KafkaStepParameters.HEADERS, Map.copyOf(this.headers));
        if (this.key != null) {
            parameters.put(KafkaStepParameters.KEY, this.key);
        }
        if (this.body != null) {
            parameters.put(KafkaStepParameters.BODY, this.body);
        }
        if (this.bodyResource != null) {
            parameters.put(KafkaStepParameters.BODY_RESOURCE, this.bodyResource);
        }
        if (this.operation == KafkaOperation.SEND) {
            // Only emit the flag when explicitly set; its absence means "default" (inject when the topic
            // declares a HEADER correlation carrier), decided by the executor.
            if (this.injectCorrelationId != null) {
                parameters.put(KafkaStepParameters.INJECT_CORRELATION_ID, this.injectCorrelationId);
            }
        } else {
            parameters.put(KafkaStepParameters.CORRELATION_FROM_CONTEXT, this.correlationIdFromContext);
            parameters.put(KafkaStepParameters.ASSERTIONS, assertionMaps());
            parameters.put(KafkaStepParameters.CAPTURES, captureMaps());
            if (this.timeoutMillis != null) {
                parameters.put(KafkaStepParameters.TIMEOUT_MILLIS, this.timeoutMillis);
            }
            if (this.pollTimeoutMillis != null) {
                parameters.put(KafkaStepParameters.POLL_TIMEOUT_MILLIS, this.pollTimeoutMillis);
            }
        }
        return parameters;
    }

    private List<Map<String, Object>> assertionMaps() {
        return this.assertions.stream()
                .map(assertion -> Map.<String, Object>of(KafkaStepParameters.JSON_PATH, assertion.jsonPath(), KafkaStepParameters.EXPECTED_VALUE, assertion.expectedValue()))
                .toList();
    }

    private List<Map<String, Object>> captureMaps() {
        return this.captures.stream()
                .map(capture -> Map.<String, Object>of(KafkaStepParameters.VARIABLE_NAME, capture.variableName(), KafkaStepParameters.JSON_PATH, capture.jsonPath()))
                .toList();
    }

    private static String requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }
}
