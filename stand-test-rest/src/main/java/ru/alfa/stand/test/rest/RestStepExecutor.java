package ru.alfa.stand.test.rest;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.AwaitResult;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.assertion.AssertionMatchers;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.exception.DiagnosticAssertionError;
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

    private static final Logger LOG = LoggerFactory.getLogger(RestStepExecutor.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final HttpCaller httpCaller;

    private final BaseUrlResolver baseUrlResolver;

    private final AuthHeaderResolver authHeaderResolver;

    private final Awaiter awaiter;

    /**
     * Creates an executor with the default WebClient-based caller and environment-backed resolvers.
     */
    public RestStepExecutor() {
        this(new WebClientHttpCaller(), new EnvironmentBaseUrlResolver(), new EnvironmentAuthHeaderResolver(), Awaiter.create());
    }

    /**
     * Creates an executor with explicit transport collaborators and the default environment-backed
     * auth resolver (for tests that need no auth).
     *
     * @param httpCaller the HTTP transport
     * @param baseUrlResolver the base-URL reference resolver
     */
    public RestStepExecutor(HttpCaller httpCaller, BaseUrlResolver baseUrlResolver) {
        this(httpCaller, baseUrlResolver, new EnvironmentAuthHeaderResolver(), Awaiter.create());
    }

    /**
     * Creates an executor with explicit collaborators (for tests).
     *
     * @param httpCaller the HTTP transport
     * @param baseUrlResolver the base-URL reference resolver
     * @param authHeaderResolver the service-auth reference resolver
     */
    public RestStepExecutor(HttpCaller httpCaller, BaseUrlResolver baseUrlResolver, AuthHeaderResolver authHeaderResolver) {
        this(httpCaller, baseUrlResolver, authHeaderResolver, Awaiter.create());
    }

    RestStepExecutor(HttpCaller httpCaller, BaseUrlResolver baseUrlResolver, AuthHeaderResolver authHeaderResolver, Awaiter awaiter) {
        this.httpCaller = Objects.requireNonNull(httpCaller, "httpCaller must not be null");
        this.baseUrlResolver = Objects.requireNonNull(baseUrlResolver, "baseUrlResolver must not be null");
        this.authHeaderResolver = Objects.requireNonNull(authHeaderResolver, "authHeaderResolver must not be null");
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter must not be null");
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
        if (RestStepParameters.EXPECT_EVENTUALLY_TYPE.equals(step.type())) {
            return executeExpectEventually(step, startedAt, parameters, request, expectedStatus, assertions, captures, context);
        }
        LOG.debug("REST {} {}", request.method(), request.path());
        final Instant sentAt = Instant.now();
        RestResponse response = this.httpCaller.execute(request);
        LOG.debug("REST {} {} -> {} in {} ms", request.method(), request.path(), response.statusCode(),
                Duration.between(sentAt, Instant.now()).toMillis());
        String mismatch = firstMismatch(expectedStatus, assertions, response, request);
        if (mismatch != null) {
            throw new StandTestAssertionError(mismatch);
        }
        if (!captures.isEmpty()) {
            applyCaptures(captures, parse(response.body()), context.variableStore());
        }
        return success(step, startedAt, request, response);
    }

    private StepResult executeExpectEventually(ScenarioStep step, Instant startedAt, Map<String, Object> parameters, RestRequest request,
            OptionalInt expectedStatus, List<RestAssertion> assertions, List<RestCapture> captures, StepExecutionContext context) {
        String service = RestStepParameters.requireString(parameters, RestStepParameters.SERVICE);
        Duration timeout = Duration.ofMillis(RestStepParameters.positiveMillis(parameters, RestStepParameters.TIMEOUT_MILLIS,
                RestStepParameters.DEFAULT_TIMEOUT_MILLIS));
        Duration pollInterval = Duration.ofMillis(RestStepParameters.positiveMillis(parameters, RestStepParameters.POLL_INTERVAL_MILLIS,
                RestStepParameters.DEFAULT_POLL_INTERVAL_MILLIS));
        AwaitPolicy policy = AwaitPolicy.builder("rest.expectEventually " + service + " " + request.path())
                .timeout(timeout)
                .pollInterval(pollInterval)
                .ignoreExceptions(false)
                .build();
        AtomicInteger attempt = new AtomicInteger();
        AwaitResult<PollProbe> result = this.awaiter.await(
                policy,
                () -> {
                    PollProbe polled = probe(request, expectedStatus, assertions);
                    LOG.debug("REST poll #{} {} {} -> {}", attempt.incrementAndGet(), request.method(), request.path(),
                            polled.response().statusCode());
                    return polled;
                },
                observed -> observed.mismatch() == null);
        PollProbe last = result.orElseThrow(diagnostics -> {
            LOG.debug("REST poll {} {} timed out: {}", request.method(), request.path(), diagnostics.summary());
            return new DiagnosticAssertionError(
                    "rest.expectEventually '" + service + " " + request.path() + "' did not observe the expected response: "
                            + diagnostics.summary()
                            + " (service=" + service + ", path=" + request.path() + ")",
                    diagnostics.withAttribute("rest.service", service).withAttribute("rest.path", request.path()).toMap());
        });
        if (!captures.isEmpty()) {
            applyCaptures(captures, parse(last.response().body()), context.variableStore());
        }
        return success(step, startedAt, request, last.response());
    }

    private PollProbe probe(RestRequest request, OptionalInt expectedStatus, List<RestAssertion> assertions) {
        RestResponse response = this.httpCaller.execute(request);
        return new PollProbe(response, firstMismatch(expectedStatus, assertions, response, request));
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
                .orElseThrow(() -> new StandTestException("Service '" + service + "' is not whitelisted in environment '" + environment
                        + "'"));
    }

    private RestRequest buildRequest(Map<String, Object> parameters, ServiceEndpointDefinition endpoint, StepExecutionContext context) {
        VariableResolver resolver = context.resolver();
        String path = resolver.resolve(RestStepParameters.requireString(parameters, RestStepParameters.PATH));
        String method = RestStepParameters.method(parameters).name();
        String baseUrl = this.baseUrlResolver.resolve(endpoint.baseUrlRef());
        Map<String, String> query = resolveValues(RestStepParameters.stringMap(parameters, RestStepParameters.QUERY), resolver);
        Map<String, String> headers = resolveValues(RestStepParameters.stringMap(parameters, RestStepParameters.HEADERS), resolver);
        injectCorrelationId(parameters, endpoint, headers, context);
        injectAuth(endpoint, headers);
        String body = resolveBody(parameters, resolver);
        return new RestRequest(method, baseUrl, path, query, headers, body);
    }

    private void injectAuth(ServiceEndpointDefinition endpoint, Map<String, String> headers) {
        if (endpoint.auth() == null) {
            return;
        }
        headers.put(AUTHORIZATION_HEADER, this.authHeaderResolver.resolve(endpoint.auth()));
    }

    private static Map<String, String> resolveValues(Map<String, String> source, VariableResolver resolver) {
        Map<String, String> resolved = new LinkedHashMap<>();
        source.forEach((name, value) -> resolved.put(name, resolver.resolve(value)));
        return resolved;
    }

    private static void injectCorrelationId(Map<String, Object> parameters, ServiceEndpointDefinition endpoint,
            Map<String, String> headers, StepExecutionContext context) {
        CorrelationConfig correlation = endpoint.correlation();
        boolean hasHeaderCarrier = correlation != null && correlation.source() == CorrelationSource.HEADER;
        boolean shouldInject = RestStepParameters.injectCorrelationIdFlag(parameters).orElse(hasHeaderCarrier);
        if (!shouldInject) {
            return;
        }
        if (!hasHeaderCarrier) {
            throw new StandTestException("Correlation id injection was requested for service '" + endpoint.name()
                    + "', but it has no HEADER correlation config");
        }
        headers.put(correlation.name(), context.scenarioContext().correlationId().value());
    }

    private static String resolveBody(Map<String, Object> parameters, VariableResolver resolver) {
        Optional<String> resource = RestStepParameters.optionalString(parameters, RestStepParameters.BODY_RESOURCE);
        Optional<String> inline = RestStepParameters.optionalString(parameters, RestStepParameters.BODY);
        if (resource.isPresent() && inline.isPresent()) {
            throw new StandTestException("A REST step must set either '" + RestStepParameters.BODY + "' or '"
                    + RestStepParameters.BODY_RESOURCE + "', not both");
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

    /**
     * Evaluates the step's expectations against a response without throwing: null when everything
     * holds, otherwise the first mismatch rendered exactly as the single-shot failure message. This is
     * both the poll condition of {@code rest.expectEventually} and the source of the thrown
     * {@link StandTestAssertionError} of a regular step — one implementation, no drift. Leaf values at
     * asserted paths are echoed (that diagnostic is the point of the assertion); the response body
     * never is.
     */
    private static String firstMismatch(OptionalInt expectedStatus, List<RestAssertion> assertions, RestResponse response,
            RestRequest request) {
        if (expectedStatus.isPresent() && expectedStatus.getAsInt() != response.statusCode()) {
            return "Expected HTTP status " + expectedStatus.getAsInt() + " but got " + response.statusCode() + " for " + request.method()
                    + " " + request.path();
        }
        if (assertions.isEmpty()) {
            return null;
        }
        DocumentContext document;
        try {
            document = parse(response.body());
        } catch (StandTestAssertionError unparseable) {
            return unparseable.getMessage();
        }
        for (RestAssertion assertion : assertions) {
            String mismatch = assertionMismatch(assertion, document);
            if (mismatch != null) {
                return mismatch;
            }
        }
        return null;
    }

    private static String assertionMismatch(RestAssertion assertion, DocumentContext document) {
        boolean pathPresent = true;
        Object actual = null;
        try {
            actual = document.read(assertion.jsonPath());
        } catch (PathNotFoundException notFound) {
            pathPresent = false;
        }
        if (AssertionMatchers.matches(assertion.matcher(), assertion.expectedValue(), pathPresent, actual)) {
            return null;
        }
        if (assertion.matcher() == AssertionMatcher.EQUALS) {
            if (!pathPresent) {
                return "JSONPath '" + assertion.jsonPath() + "' not found in response body";
            }
            return "JSONPath assertion failed at '" + assertion.jsonPath() + "': expected <" + assertion.expectedValue() + "> but got <"
                    + actual + ">";
        }
        String observed = pathPresent ? "<" + actual + ">" : "no value (path not found)";
        return "JSONPath assertion failed at '" + assertion.jsonPath() + "': matcher " + assertion.matcher() + " expected <"
                + assertion.expectedValue() + "> but got " + observed;
    }

    private static void applyCaptures(List<RestCapture> captures, DocumentContext document, VariableStore store) {
        for (RestCapture capture : captures) {
            Object value = read(document, capture.jsonPath());
            if (value == null) {
                throw new StandTestAssertionError("Captured value at '" + capture.jsonPath() + "' is null; cannot store variable '"
                        + capture.variableName() + "'");
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

    private static StepResult success(ScenarioStep step, Instant startedAt, RestRequest request, RestResponse response) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("http.method", request.method());
        diagnostics.put("http.path", request.path());
        diagnostics.put("http.status", response.statusCode());
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, Instant.now(), null, diagnostics);
    }
}
