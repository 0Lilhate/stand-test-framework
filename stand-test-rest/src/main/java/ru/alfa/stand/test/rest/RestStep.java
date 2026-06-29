package ru.alfa.stand.test.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final Map<String, String> query = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final List<RestAssertion> assertions = new ArrayList<>();
    private final List<RestCapture> captures = new ArrayList<>();
    private String id;
    private String body;
    private String bodyResource;
    private boolean injectCorrelationId;
    private Integer expectedStatus;

    private RestStep(RestMethod method, String service, String path) {
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.service = requireNonBlank(service, "service");
        this.path = requireNonBlank(path, "path");
    }

    /**
     * Starts a GET step against the given service alias and path.
     *
     * @param service the logical service alias
     * @param path the request path
     * @return a new builder
     */
    public static RestStep get(String service, String path) {
        return new RestStep(RestMethod.GET, service, path);
    }

    /**
     * Starts a POST step against the given service alias and path.
     *
     * @param service the logical service alias
     * @param path the request path
     * @return a new builder
     */
    public static RestStep post(String service, String path) {
        return new RestStep(RestMethod.POST, service, path);
    }

    /**
     * Starts a PUT step against the given service alias and path.
     *
     * @param service the logical service alias
     * @param path the request path
     * @return a new builder
     */
    public static RestStep put(String service, String path) {
        return new RestStep(RestMethod.PUT, service, path);
    }

    /**
     * Starts a DELETE step against the given service alias and path.
     *
     * @param service the logical service alias
     * @param path the request path
     * @return a new builder
     */
    public static RestStep delete(String service, String path) {
        return new RestStep(RestMethod.DELETE, service, path);
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
        return new GenericStep(resolveId(), this.method.stepType(), description(), toParameterMap());
    }

    private String resolveId() {
        return (this.id != null) ? this.id : this.method.name() + " " + this.path;
    }

    private String description() {
        return this.method.name() + " " + this.service + " " + this.path;
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
        return parameters;
    }

    private List<Map<String, Object>> assertionMaps() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RestAssertion assertion : this.assertions) {
            list.add(Map.of(RestStepParameters.JSON_PATH, assertion.jsonPath(), RestStepParameters.EXPECTED_VALUE, assertion.expectedValue()));
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
