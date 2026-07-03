package ru.alfa.stand.test.rest;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableResolver;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * REST {@link StepExecutor}: the single point of real HTTP IO to a stand.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} (registered in {@code META-INF/services}); the
 * class is public with a public no-arg constructor for that reason. It is stateless and thread-safe —
 * all per-run state arrives through the {@link StepExecutionContext}, so one instance is safely shared
 * across concurrent runs.
 *
 * <p>Failure semantics (plan §8.3): a failed assertion (status or JSONPath) is raised as a
 * {@link StandTestAssertionError}; an infrastructure or configuration problem (unknown alias,
 * unresolved base URL, transport error, missing variable) is raised as a {@link StandTestException}.
 */
public final class RestStepExecutor implements StepExecutor {

    private final HttpCaller httpCaller;
    private final BaseUrlResolver baseUrlResolver;

    /**
     * Creates an executor with the default WebClient-based caller and environment base-URL resolver.
     */
    public RestStepExecutor() {
        this(new WebClientHttpCaller(), new EnvironmentBaseUrlResolver());
    }

    /**
     * Creates an executor with explicit collaborators (for tests).
     *
     * @param httpCaller the HTTP transport
     * @param baseUrlResolver the base-URL reference resolver
     */
    public RestStepExecutor(HttpCaller httpCaller, BaseUrlResolver baseUrlResolver) {
        this.httpCaller = Objects.requireNonNull(httpCaller, "httpCaller must not be null");
        this.baseUrlResolver = Objects.requireNonNull(baseUrlResolver, "baseUrlResolver must not be null");
    }

    @Override
    public boolean supports(String stepType) {
        return stepType != null && stepType.startsWith(RestStepParameters.TYPE_PREFIX);
    }

    @Override
    public StepResult execute(ScenarioStep step, StepExecutionContext context) {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(context, "context must not be null");
        final Instant startedAt = Instant.now();
        Map<String, Object> parameters = parameters(step);
        ServiceEndpointDefinition endpoint = resolveEndpoint(parameters, context);
        RestRequest request = buildRequest(parameters, endpoint, context);
        // All parameter-schema validation happens before any IO, so a structurally invalid step never
        // reaches the stand (plan §8.3: configuration errors are fail-fast).
        OptionalInt expectedStatus = RestStepParameters.expectedStatus(parameters);
        List<RestAssertion> assertions = RestStepParameters.assertions(parameters);
        List<RestCapture> captures = RestStepParameters.captures(parameters);
        RestResponse response = this.httpCaller.execute(request);
        assertStatus(expectedStatus, response, request);
        if (!assertions.isEmpty() || !captures.isEmpty()) {
            DocumentContext document = parse(response.body());
            verifyAssertions(assertions, document);
            applyCaptures(captures, document, context.variableStore());
        }
        return success(step, startedAt, request, response);
    }

    private static Map<String, Object> parameters(ScenarioStep step) {
        if (step instanceof GenericStep generic) {
            return generic.parameters();
        }
        throw new StandTestException("RestStepExecutor requires a GenericStep produced by RestStep, but got: " + step.getClass().getName());
    }

    private static ServiceEndpointDefinition resolveEndpoint(Map<String, Object> parameters, StepExecutionContext context) {
        String service = RestStepParameters.requireString(parameters, RestStepParameters.SERVICE);
        String environment = context.scenarioContext().environment();
        EnvironmentDefinition definition = context.environmentRegistry()
                .environment(environment)
                .orElseThrow(() -> new StandTestException("Environment '" + environment + "' is not whitelisted"));
        return definition.service(service)
                .orElseThrow(() -> new StandTestException("Service '" + service + "' is not whitelisted in environment '" + environment + "'"));
    }

    private RestRequest buildRequest(Map<String, Object> parameters, ServiceEndpointDefinition endpoint, StepExecutionContext context) {
        VariableResolver resolver = context.resolver();
        String path = resolver.resolve(RestStepParameters.requireString(parameters, RestStepParameters.PATH));
        String method = RestStepParameters.method(parameters).name();
        String baseUrl = this.baseUrlResolver.resolve(endpoint.baseUrlRef());
        Map<String, String> query = resolveValues(RestStepParameters.stringMap(parameters, RestStepParameters.QUERY), resolver);
        Map<String, String> headers = resolveValues(RestStepParameters.stringMap(parameters, RestStepParameters.HEADERS), resolver);
        injectCorrelationId(parameters, endpoint, headers, context);
        String body = resolveBody(parameters, resolver);
        return new RestRequest(method, baseUrl, path, query, headers, body);
    }

    private static Map<String, String> resolveValues(Map<String, String> source, VariableResolver resolver) {
        Map<String, String> resolved = new LinkedHashMap<>();
        source.forEach((name, value) -> resolved.put(name, resolver.resolve(value)));
        return resolved;
    }

    private static void injectCorrelationId(Map<String, Object> parameters, ServiceEndpointDefinition endpoint, Map<String, String> headers, StepExecutionContext context) {
        if (!RestStepParameters.injectCorrelationId(parameters)) {
            return;
        }
        CorrelationConfig correlation = endpoint.correlation();
        if (correlation == null || correlation.source() != CorrelationSource.HEADER) {
            throw new StandTestException("Correlation id injection was requested for service '" + endpoint.name() + "', but it has no HEADER correlation config");
        }
        headers.put(correlation.name(), context.scenarioContext().correlationId().value());
    }

    private static String resolveBody(Map<String, Object> parameters, VariableResolver resolver) {
        Optional<String> resource = RestStepParameters.optionalString(parameters, RestStepParameters.BODY_RESOURCE);
        Optional<String> inline = RestStepParameters.optionalString(parameters, RestStepParameters.BODY);
        if (resource.isPresent() && inline.isPresent()) {
            throw new StandTestException("A REST step must set either '" + RestStepParameters.BODY + "' or '" + RestStepParameters.BODY_RESOURCE + "', not both");
        }
        if (resource.isPresent()) {
            return resolver.resolve(readResource(resource.get()));
        }
        return inline.map(resolver::resolve).orElse(null);
    }

    private static String readResource(String resourcePath) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = RestStepExecutor.class.getClassLoader();
        }
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new StandTestException("Request body resource not found on classpath: '" + resourcePath + "'");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StandTestException("Failed to read request body resource '" + resourcePath + "'", failure);
        }
    }

    private static void assertStatus(OptionalInt expected, RestResponse response, RestRequest request) {
        if (expected.isPresent() && expected.getAsInt() != response.statusCode()) {
            throw new StandTestAssertionError("Expected HTTP status " + expected.getAsInt() + " but got " + response.statusCode() + " for " + request.method() + " " + request.path());
        }
    }

    private static void verifyAssertions(List<RestAssertion> assertions, DocumentContext document) {
        for (RestAssertion assertion : assertions) {
            Object actual = read(document, assertion.jsonPath());
            if (!valuesMatch(assertion.expectedValue(), actual)) {
                throw new StandTestAssertionError("JSONPath assertion failed at '" + assertion.jsonPath() + "': expected <" + assertion.expectedValue() + "> but got <" + actual + ">");
            }
        }
    }

    private static void applyCaptures(List<RestCapture> captures, DocumentContext document, VariableStore store) {
        for (RestCapture capture : captures) {
            Object value = read(document, capture.jsonPath());
            if (value == null) {
                throw new StandTestAssertionError("Captured value at '" + capture.jsonPath() + "' is null; cannot store variable '" + capture.variableName() + "'");
            }
            store.put(capture.variableName(), value);
        }
    }

    private static DocumentContext parse(String body) {
        if (body == null || body.isBlank()) {
            throw new StandTestAssertionError("Response body is empty; expected JSON to assert or capture against");
        }
        try {
            return JsonPath.parse(body);
        } catch (InvalidJsonException | IllegalArgumentException invalid) {
            // Deliberately does NOT echo the parser's message: json-smart quotes a fragment of the
            // offending body, which may carry sensitive response data into a report. The body length is
            // safe context; the raw body stays out of the failure text.
            throw new StandTestAssertionError("Response body is not valid JSON (" + body.length() + " characters, parse failed)");
        }
    }

    private static Object read(DocumentContext document, String jsonPath) {
        try {
            return document.read(jsonPath);
        } catch (PathNotFoundException notFound) {
            throw new StandTestAssertionError("JSONPath '" + jsonPath + "' not found in response body");
        }
    }

    private static boolean valuesMatch(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        // Numbers are compared by numeric value so e.g. an expected int 100 matches a JSON 100.0; all
        // other type mismatches (boolean vs string, string vs number, ...) are a genuine mismatch and
        // must fail rather than be string-coerced, so a field changing type is caught.
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            try {
                return new BigDecimal(expectedNumber.toString()).compareTo(new BigDecimal(actualNumber.toString())) == 0;
            } catch (NumberFormatException notComparable) {
                // A non-finite expected value (NaN / Infinity) is not numerically comparable: treat it
                // as a mismatch rather than letting a raw NumberFormatException escape (plan §8.3).
                return false;
            }
        }
        return false;
    }

    private static StepResult success(ScenarioStep step, Instant startedAt, RestRequest request, RestResponse response) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("http.method", request.method());
        diagnostics.put("http.path", request.path());
        diagnostics.put("http.status", response.statusCode());
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, Instant.now(), null, diagnostics);
    }
}
