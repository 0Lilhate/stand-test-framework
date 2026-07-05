package ru.alfa.stand.test.rest;

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
 * Lazy builder for a REST step.
 *
 * <p>It assembles configuration only and performs no IO — {@link #build()} materialises an immutable
 * core {@link GenericStep} whose parameter map follows the {@link RestStepParameters} schema. The
 * actual HTTP call happens later, inside {@link RestStepExecutor}, so the builder can never bypass the
 * validator or the run pipeline (plan §8.1).
 *
 * <p>Typical use (the {@code .build()} result is passed to {@code Scenario.Builder.step(...)}):
 * <pre>{@code
 * RestStep.post("client-service", "/api/request")
 *         .body("{\"amount\": 100}")
 *         .header("Content-Type", "application/json")
 *         .injectCorrelationId()
 *         .expectStatus(200)
 *         .capture("requestId", "$.requestId")
 *         .build()
 * }</pre>
 */
public final class RestStep {

    private final RestMethod method;
    private final String service;
    private final String path;
    private final boolean expectEventually;
    private final Map<String, String> query = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final List<RestAssertion> assertions = new ArrayList<>();
    private final List<RestCapture> captures = new ArrayList<>();
    private String id;
    private String body;
    private String bodyResource;
    private boolean injectCorrelationId;
    private Integer expectedStatus;
    private Long timeoutMillis;
    private Long pollIntervalMillis;

    private RestStep(RestMethod method, String service, String path, boolean expectEventually) {
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.service = requireNonBlank(service, "service");
        this.path = requireNonBlank(path, "path");
        this.expectEventually = expectEventually;
    }

    /**
     * Starts a GET step against the given service alias and path.
     *
     * @param service the logical service alias
     * @param path the request path
     * @return a new builder
     */
    public static RestStep get(String service, String path) {
        return new RestStep(RestMethod.GET, service, path, false);
    }

    /**
     * Starts a polling step: the given path is GET-polled until the declared expectations (status
     * and/or JSONPath assertions) hold, bounded by {@link #within(Duration)} (default 30 seconds,
     * poll interval 200 milliseconds). Captures are applied to the final, satisfied response only.
     *
     * @param service the logical service alias
     * @param path the request path
     * @return a new builder in expect-eventually mode
     */
    public static RestStep expectEventually(String service, String path) {
        return new RestStep(RestMethod.GET, service, path, true);
    }

    /**
     * Starts a POST step against the given service alias and path.
     *
     * @param service the logical service alias
     * @param path the request path
     * @return a new builder
     */
    public static RestStep post(String service, String path) {
        return new RestStep(RestMethod.POST, service, path, false);
    }

    /**
     * Starts a PUT step against the given service alias and path.
     *
     * @param service the logical service alias
     * @param path the request path
     * @return a new builder
     */
    public static RestStep put(String service, String path) {
        return new RestStep(RestMethod.PUT, service, path, false);
    }

    /**
     * Starts a DELETE step against the given service alias and path.
     *
     * @param service the logical service alias
     * @param path the request path
     * @return a new builder
     */
    public static RestStep delete(String service, String path) {
        return new RestStep(RestMethod.DELETE, service, path, false);
    }

    /**
     * Sets an explicit step id (otherwise a readable {@code "<METHOD> <path>"} id is derived).
     *
     * @param id the unique step id
     * @return this builder
     */
    public RestStep id(String id) {
        this.id = requireNonBlank(id, "id");
        return this;
    }

    /**
     * Adds a query parameter; the value may contain {@code ${...}} placeholders.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return this builder
     */
    public RestStep query(String name, String value) {
        this.query.put(requireNonBlank(name, "query name"), Objects.requireNonNull(value, "query value must not be null"));
        return this;
    }

    /**
     * Adds a request header; the value may contain {@code ${...}} placeholders.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    public RestStep header(String name, String value) {
        this.headers.put(requireNonBlank(name, "header name"), Objects.requireNonNull(value, "header value must not be null"));
        return this;
    }

    /**
     * Sets an inline request body; it may contain {@code ${...}} placeholders.
     *
     * @param inlineBody the request body
     * @return this builder
     */
    public RestStep body(String inlineBody) {
        this.body = Objects.requireNonNull(inlineBody, "body must not be null");
        return this;
    }

    /**
     * Sets the request body from a classpath resource; its content may contain {@code ${...}}
     * placeholders.
     *
     * @param classpathResource the classpath resource path
     * @return this builder
     */
    public RestStep bodyFromResource(String classpathResource) {
        this.bodyResource = requireNonBlank(classpathResource, "classpathResource");
        return this;
    }

    /**
     * Requests injection of the SDK-owned correlation id into the outbound request, using the header
     * configured for the target service.
     *
     * @return this builder
     */
    public RestStep injectCorrelationId() {
        this.injectCorrelationId = true;
        return this;
    }

    /**
     * Asserts the response HTTP status equals the given value.
     *
     * @param status the expected status code
     * @return this builder
     */
    public RestStep expectStatus(int status) {
        this.expectedStatus = status;
        return this;
    }

    /**
     * Asserts that the value at the given JSONPath equals the expected value.
     *
     * @param jsonPath the JSONPath expression
     * @param expectedValue the expected value (never null)
     * @return this builder
     */
    public RestStep assertPath(String jsonPath, Object expectedValue) {
        this.assertions.add(new RestAssertion(jsonPath, expectedValue));
        return this;
    }

    /**
     * Asserts that the String value at the path contains the expected substring, or that the List
     * value at the path contains an element equal to the expected value.
     *
     * @param jsonPath the JSONPath expression
     * @param expectedValue the substring / element to look for (never null)
     * @return this builder
     */
    public RestStep assertPathContains(String jsonPath, Object expectedValue) {
        this.assertions.add(new RestAssertion(jsonPath, expectedValue, AssertionMatcher.CONTAINS));
        return this;
    }

    /**
     * Asserts that the String value at the path fully matches the regular expression.
     *
     * @param jsonPath the JSONPath expression
     * @param regex the regular expression (validated before any IO)
     * @return this builder
     */
    public RestStep assertPathMatches(String jsonPath, String regex) {
        this.assertions.add(new RestAssertion(jsonPath, Objects.requireNonNull(regex, "regex must not be null"), AssertionMatcher.MATCHES));
        return this;
    }

    /**
     * Asserts that the path is present in the response body (a JSON null counts as present).
     *
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public RestStep assertPathExists(String jsonPath) {
        this.assertions.add(new RestAssertion(jsonPath, Boolean.TRUE, AssertionMatcher.EXISTS));
        return this;
    }

    /**
     * Asserts that the path is absent from the response body.
     *
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public RestStep assertPathAbsent(String jsonPath) {
        this.assertions.add(new RestAssertion(jsonPath, Boolean.FALSE, AssertionMatcher.EXISTS));
        return this;
    }

    /**
     * Asserts that the path is present and its value is not JSON null.
     *
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public RestStep assertPathNotNull(String jsonPath) {
        this.assertions.add(new RestAssertion(jsonPath, Boolean.TRUE, AssertionMatcher.NOT_NULL));
        return this;
    }

    /**
     * Asserts that the path is present and its value is JSON null (for absence use
     * {@link #assertPathAbsent}).
     *
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public RestStep assertPathIsNull(String jsonPath) {
        this.assertions.add(new RestAssertion(jsonPath, Boolean.FALSE, AssertionMatcher.NOT_NULL));
        return this;
    }

    /**
     * Bounds the expect-eventually poll (only valid on a {@link #expectEventually} step).
     *
     * @param timeout the maximum time to wait (strictly positive)
     * @return this builder
     */
    public RestStep within(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be strictly positive");
        }
        this.timeoutMillis = timeout.toMillis();
        return this;
    }

    /**
     * Bounds the expect-eventually poll in whole seconds (only valid on a {@link #expectEventually} step).
     *
     * @param seconds the maximum time to wait, in seconds (strictly positive)
     * @return this builder
     */
    public RestStep withinSeconds(long seconds) {
        return within(Duration.ofSeconds(seconds));
    }

    /**
     * Sets the poll interval between probes (only valid on a {@link #expectEventually} step;
     * default 200 milliseconds).
     *
     * @param pollInterval the interval between probes (strictly positive)
     * @return this builder
     */
    public RestStep pollInterval(Duration pollInterval) {
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be strictly positive");
        }
        this.pollIntervalMillis = pollInterval.toMillis();
        return this;
    }

    /**
     * Captures the value at the given JSONPath into a run variable for later steps.
     *
     * @param variableName the variable name
     * @param jsonPath the JSONPath expression
     * @return this builder
     */
    public RestStep capture(String variableName, String jsonPath) {
        this.captures.add(new RestCapture(variableName, jsonPath));
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
        if (this.expectEventually) {
            if (this.body != null || this.bodyResource != null) {
                throw new IllegalStateException("expectEventually polls with GET and carries no request body");
            }
            if (this.expectedStatus == null && this.assertions.isEmpty()) {
                throw new IllegalStateException("expectEventually requires at least one expectation: expectStatus(...) or an assertPath*(...)");
            }
        } else if (this.timeoutMillis != null || this.pollIntervalMillis != null) {
            throw new IllegalStateException("within(...)/withinSeconds(...)/pollInterval(...) are only valid on an expectEventually step");
        }
        String type = this.expectEventually ? RestStepParameters.EXPECT_EVENTUALLY_TYPE : this.method.stepType();
        return new GenericStep(resolveId(), type, description(), toParameterMap());
    }

    private String resolveId() {
        if (this.id != null) {
            return this.id;
        }
        return (this.expectEventually ? "EXPECT " : "") + this.method.name() + " " + this.path;
    }

    private String description() {
        return (this.expectEventually ? "EXPECT " : "") + this.method.name() + " " + this.service + " " + this.path;
    }

    private Map<String, Object> toParameterMap() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(RestStepParameters.METHOD, this.method.name());
        parameters.put(RestStepParameters.SERVICE, this.service);
        parameters.put(RestStepParameters.PATH, this.path);
        parameters.put(RestStepParameters.QUERY, Map.copyOf(this.query));
        parameters.put(RestStepParameters.HEADERS, Map.copyOf(this.headers));
        parameters.put(RestStepParameters.INJECT_CORRELATION_ID, this.injectCorrelationId);
        parameters.put(RestStepParameters.ASSERTIONS, assertionMaps());
        parameters.put(RestStepParameters.CAPTURES, captureMaps());
        if (this.expectedStatus != null) {
            parameters.put(RestStepParameters.EXPECTED_STATUS, this.expectedStatus);
        }
        if (this.body != null) {
            parameters.put(RestStepParameters.BODY, this.body);
        }
        if (this.bodyResource != null) {
            parameters.put(RestStepParameters.BODY_RESOURCE, this.bodyResource);
        }
        if (this.timeoutMillis != null) {
            parameters.put(RestStepParameters.TIMEOUT_MILLIS, this.timeoutMillis);
        }
        if (this.pollIntervalMillis != null) {
            parameters.put(RestStepParameters.POLL_INTERVAL_MILLIS, this.pollIntervalMillis);
        }
        return parameters;
    }

    private List<Map<String, Object>> assertionMaps() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RestAssertion assertion : this.assertions) {
            if (assertion.matcher() == AssertionMatcher.EQUALS) {
                list.add(Map.of(RestStepParameters.JSON_PATH, assertion.jsonPath(), RestStepParameters.EXPECTED_VALUE, assertion.expectedValue()));
            } else {
                list.add(Map.of(RestStepParameters.JSON_PATH, assertion.jsonPath(), RestStepParameters.EXPECTED_VALUE, assertion.expectedValue(), RestStepParameters.MATCHER, assertion.matcher().name()));
            }
        }
        return List.copyOf(list);
    }

    private List<Map<String, Object>> captureMaps() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RestCapture capture : this.captures) {
            list.add(Map.of(RestStepParameters.VARIABLE_NAME, capture.variableName(), RestStepParameters.JSON_PATH, capture.jsonPath()));
        }
        return List.copyOf(list);
    }

    private static String requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }
}
