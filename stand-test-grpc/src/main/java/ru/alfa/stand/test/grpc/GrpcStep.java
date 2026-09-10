package ru.alfa.stand.test.grpc;

import io.grpc.Metadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * Lazy builder for a gRPC unary step ({@code grpc.unary}).
 *
 * <p>It assembles configuration only and performs no IO — {@link #build()} materialises an immutable core
 * {@link GenericStep} whose parameter map follows the {@link GrpcStepParameters} schema. The real call
 * happens later, inside {@link GrpcStepExecutor}, so the builder can never bypass the validator or the run
 * pipeline (plan §8.1).
 *
 * <p>The target is a logical alias resolved via the environment registry (never a {@code host:port}); the
 * deadline is mandatory (no unbounded calls, plan §16); secrets are never written inline (secret-bearing
 * metadata keys are rejected here).
 *
 * <p>Typical use (each {@code .build()} result is passed to {@code Scenario.Builder.step(...)}):
 * <pre>{@code
 * GrpcStep.unary("billing-grpc")
 *         .method("billing.BillingService/Charge")
 *         .requestFromResource("fixtures/charge-request.json")
 *         .injectCorrelationId()                 // SDK-owned correlationId -> gRPC metadata
 *         .withinSeconds(5)
 *         .assertPath("$.status", "OK")
 *         .capture("chargeId", "$.chargeId")
 *         .build()
 * }</pre>
 */
public final class GrpcStep {

    private final GrpcOperation operation;
    private final String target;
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private final List<GrpcAssertion> assertions = new ArrayList<>();
    private final List<GrpcCapture> captures = new ArrayList<>();
    private String id;
    private String method;
    private String request;
    private String requestResource;
    // Tri-state: null = unset (inject when the resolved target declares a METADATA correlation carrier —
    // the safe default), TRUE = force-inject, FALSE = explicit opt-out.
    private Boolean injectCorrelationId;
    private Long deadlineMillis;

    private GrpcStep(GrpcOperation operation, String target) {
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.target = requireNonBlank(target, "target");
    }

    /**
     * Starts a {@code grpc.unary} step against the given logical target alias.
     *
     * @param target the logical gRPC target alias
     * @return a new builder
     */
    public static GrpcStep unary(String target) {
        return new GrpcStep(GrpcOperation.UNARY, target);
    }

    /**
     * Sets an explicit step id (otherwise a readable {@code "<OPERATION> <target>"} id is derived).
     *
     * @param id the unique step id
     * @return this builder
     */
    public GrpcStep id(String id) {
        this.id = requireNonBlank(id, "id");
        return this;
    }

    /**
     * Sets the fully-qualified method name, {@code package.Service/Method}.
     *
     * @param methodFullName the fully-qualified method name
     * @return this builder
     */
    public GrpcStep method(String methodFullName) {
        this.method = requireNonBlank(methodFullName, "method");
        return this;
    }

    /**
     * Sets an inline request payload (JSON as a string); it may contain {@code ${...}} placeholders.
     *
     * @param inlineRequestJson the request payload as JSON
     * @return this builder
     */
    public GrpcStep request(String inlineRequestJson) {
        this.request = Objects.requireNonNull(inlineRequestJson, "request must not be null");
        return this;
    }

    /**
     * Sets the request payload from a classpath resource; its content may contain {@code ${...}}
     * placeholders.
     *
     * @param classpathResource the classpath resource path
     * @return this builder
     */
    public GrpcStep requestFromResource(String classpathResource) {
        this.requestResource = requireNonBlank(classpathResource, "classpathResource");
        return this;
    }

    /**
     * Adds a request metadata entry; the value may contain {@code ${...}} placeholders. Secret-bearing
     * metadata names (authorization/token/password/secret/cookie/api-key) are rejected — secrets are
     * never written inline (plan §16).
     *
     * @param name the metadata key
     * @param value the metadata value
     * @return this builder
     */
    public GrpcStep metadata(String name, String value) {
        String key = requireNonBlank(name, "metadata name");
        if (SecretMetadata.isSecret(key)) {
            throw new IllegalArgumentException("metadata key '" + key + "' is secret-bearing and must not be set inline (plan §16)");
        }
        try {
            // Validate the name against gRPC's own metadata-key rules (fail fast at build time), reusing
            // the authoritative validator rather than duplicating its character set.
            Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("metadata key '" + key + "' is not a valid gRPC metadata name: " + invalid.getMessage(),
                    invalid);
        }
        this.metadata.put(key, Objects.requireNonNull(value, "metadata value must not be null"));
        return this;
    }

    /**
     * Forces injection of the SDK-owned correlation id into the outbound metadata, using the carrier
     * configured for the target.
     *
     * <p>Injection is <strong>on by default</strong> whenever the resolved target declares a METADATA
     * correlation carrier, so this call is only needed to be explicit; use {@link #injectCorrelationId(boolean)
     * injectCorrelationId(false)} to opt out.
     *
     * @return this builder
     */
    public GrpcStep injectCorrelationId() {
        return injectCorrelationId(true);
    }

    /**
     * Explicitly enables ({@code true}) or opts out of ({@code false}) correlation-id injection, overriding
     * the default (inject when the target declares a METADATA correlation carrier).
     *
     * @param inject whether to inject the correlation id
     * @return this builder
     */
    public GrpcStep injectCorrelationId(boolean inject) {
        this.injectCorrelationId = inject;
        return this;
    }

    /**
     * Sets the unary call deadline, in seconds (mandatory).
     *
     * @param seconds the deadline in seconds
     * @return this builder
     */
    public GrpcStep withinSeconds(long seconds) {
        return deadline(Duration.ofSeconds(seconds));
    }

    /**
     * Sets the unary call deadline (mandatory).
     *
     * @param deadline the deadline (strictly positive)
     * @return this builder
     */
    public GrpcStep deadline(Duration deadline) {
        Objects.requireNonNull(deadline, "deadline must not be null");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be strictly positive");
        }
        this.deadlineMillis = deadline.toMillis();
        return this;
    }

    /**
     * Asserts that the value at the given JSONPath in the response equals the expected value.
     *
     * @param jsonPath the JSONPath expression
     * @param expectedValue the expected value (never null)
     * @return this builder
     */
    public GrpcStep assertPath(String jsonPath, Object expectedValue) {
        this.assertions.add(new GrpcAssertion(jsonPath, expectedValue));
        return this;
    }

    /**
     * Asserts that the String value at the path contains the expected substring, or that the List value at
     * the path contains an element equal to the expected value.
     *
     * @param jsonPath the JSONPath expression
     * @param expectedValue the substring / element to look for (never null)
     * @return this builder
     */
    public GrpcStep assertPathContains(String jsonPath, Object expectedValue) {
        this.assertions.add(new GrpcAssertion(jsonPath, expectedValue, AssertionMatcher.CONTAINS));
        return this;
    }

    /**
     * Asserts that the String value at the path fully matches the regular expression.
     *
     * @param jsonPath the JSONPath expression
     * @param regex the regular expression (validated before any IO)
     * @return this builder
     */
    public GrpcStep assertPathMatches(String jsonPath, String regex) {
        this.assertions.add(new GrpcAssertion(jsonPath, Objects.requireNonNull(regex, "regex must not be null"), AssertionMatcher.MATCHES));
        return this;
    }

    /**
     * Asserts that the path is present in the response (a JSON null counts as present).
     *
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public GrpcStep assertPathExists(String jsonPath) {
        this.assertions.add(new GrpcAssertion(jsonPath, Boolean.TRUE, AssertionMatcher.EXISTS));
        return this;
    }

    /**
     * Asserts that the path is absent from the response.
     *
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public GrpcStep assertPathAbsent(String jsonPath) {
        this.assertions.add(new GrpcAssertion(jsonPath, Boolean.FALSE, AssertionMatcher.EXISTS));
        return this;
    }

    /**
     * Asserts that the path is present and its value is not JSON null.
     *
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public GrpcStep assertPathNotNull(String jsonPath) {
        this.assertions.add(new GrpcAssertion(jsonPath, Boolean.TRUE, AssertionMatcher.NOT_NULL));
        return this;
    }

    /**
     * Asserts that the path is present and its value is JSON null (for absence use
     * {@link #assertPathAbsent}).
     *
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public GrpcStep assertPathIsNull(String jsonPath) {
        this.assertions.add(new GrpcAssertion(jsonPath, Boolean.FALSE, AssertionMatcher.NOT_NULL));
        return this;
    }

    /**
     * Captures the value at the given JSONPath in the response into a run variable.
     *
     * @param variableName the variable name
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public GrpcStep capture(String variableName, String jsonPath) {
        this.captures.add(new GrpcCapture(variableName, jsonPath));
        return this;
    }

    /**
     * Materialises the immutable core step. Performs no IO.
     *
     * @return the assembled scenario step
     */
    public ScenarioStep build() {
        if (this.method == null) {
            throw new IllegalStateException("A grpc.unary step requires method(...)");
        }
        if (this.deadlineMillis == null) {
            throw new IllegalStateException("A grpc.unary step requires a deadline: deadline(...) or withinSeconds(...)");
        }
        if (this.request != null && this.requestResource != null) {
            throw new IllegalStateException("Set either request(...) or requestFromResource(...), not both");
        }
        return new GenericStep(resolveId(), this.operation.stepType(), description(), toParameterMap());
    }

    private String resolveId() {
        return (this.id != null) ? this.id : this.operation.name() + " " + this.target;
    }

    private String description() {
        return this.operation.name() + " " + this.target + " " + this.method;
    }

    private Map<String, Object> toParameterMap() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(GrpcStepParameters.TARGET, this.target);
        parameters.put(GrpcStepParameters.METHOD_FULL_NAME, this.method);
        parameters.put(GrpcStepParameters.DEADLINE_MILLIS, this.deadlineMillis);
        parameters.put(GrpcStepParameters.METADATA, Map.copyOf(this.metadata));
        // Only emit the flag when explicitly set; its absence means "default" (inject when the target
        // declares a METADATA correlation carrier), decided by the executor.
        if (this.injectCorrelationId != null) {
            parameters.put(GrpcStepParameters.INJECT_CORRELATION_ID, this.injectCorrelationId);
        }
        parameters.put(GrpcStepParameters.ASSERTIONS, assertionMaps());
        parameters.put(GrpcStepParameters.CAPTURES, captureMaps());
        if (this.request != null) {
            parameters.put(GrpcStepParameters.REQUEST, this.request);
        }
        if (this.requestResource != null) {
            parameters.put(GrpcStepParameters.REQUEST_RESOURCE, this.requestResource);
        }
        return parameters;
    }

    /** An EQUALS assertion omits the matcher key entirely — an absent wire matcher IS equals (core contract). */
    private List<Map<String, Object>> assertionMaps() {
        return this.assertions.stream()
                .map(assertion -> assertion.matcher() == AssertionMatcher.EQUALS
                        ? Map.of(GrpcStepParameters.JSON_PATH, assertion.jsonPath(), GrpcStepParameters.EXPECTED_VALUE,
                                assertion.expectedValue())
                        : Map.of(GrpcStepParameters.JSON_PATH, assertion.jsonPath(), GrpcStepParameters.EXPECTED_VALUE,
                                assertion.expectedValue(), GrpcStepParameters.MATCHER, assertion.matcher().name()))
                .toList();
    }

    private List<Map<String, Object>> captureMaps() {
        return this.captures.stream()
                .map(capture -> Map.<String,
                        Object>of(GrpcStepParameters.VARIABLE_NAME, capture.variableName(), GrpcStepParameters.JSON_PATH,
                        capture.jsonPath()))
                .toList();
    }

    private static String requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }
}
