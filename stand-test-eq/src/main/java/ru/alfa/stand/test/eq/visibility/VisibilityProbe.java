package ru.alfa.stand.test.eq.visibility;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.AwaitResult;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.assertion.AssertionMatchers;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.eq.EqSeedException;
import ru.alfa.stand.test.eq.backend.SeedResult;
import ru.alfa.stand.test.eq.config.VisibilityConfig;
import ru.alfa.stand.test.http.AuthHeaderResolver;
import ru.alfa.stand.test.http.BaseUrlResolver;
import ru.alfa.stand.test.http.CorrelationHeader;
import ru.alfa.stand.test.http.HttpCaller;
import ru.alfa.stand.test.http.RestRequest;
import ru.alfa.stand.test.http.RestResponse;

/** Read-only, bounded polling for the visibility of a newly seeded client. */
public final class VisibilityProbe {

    private final HttpCaller caller;
    private final BaseUrlResolver baseUrlResolver;
    private final AuthHeaderResolver authHeaderResolver;
    private final Awaiter awaiter;

    public VisibilityProbe(HttpCaller caller, BaseUrlResolver baseUrlResolver,
                           AuthHeaderResolver authHeaderResolver, Awaiter awaiter) {
        this.caller = Objects.requireNonNull(caller, "caller must not be null");
        this.baseUrlResolver = Objects.requireNonNull(baseUrlResolver, "baseUrlResolver must not be null");
        this.authHeaderResolver = Objects.requireNonNull(authHeaderResolver, "authHeaderResolver must not be null");
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter must not be null");
    }

    /** Resolves the whitelisted service and checks templates before any seed write. */
    public Prepared prepare(VisibilityConfig config, EnvironmentDefinition environment, StepExecutionContext context) {
        Objects.requireNonNull(config, "config must not be null");
        ServiceEndpointDefinition endpoint = environment.service(config.service())
                .orElseThrow(() -> new StandTestException("EQ visibility service alias '" + config.service()
                        + "' is not whitelisted"));
        checkTemplate(config.path());
        config.query().forEach((key, value) -> {
            checkTemplate(key);
            checkTemplate(value);
        });
        checkTemplate(config.bodyEquals());
        try {
            JsonPath.compile(config.bodyPath());
        } catch (IllegalArgumentException invalid) {
            throw new StandTestException("Invalid EQ visibility JSONPath '" + config.bodyPath() + "'");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (endpoint.auth() != null) {
            headers.put("Authorization", authHeaderResolver.resolve(endpoint.auth()));
        }
        CorrelationHeader.inject(endpoint.correlation() != null, endpoint, headers,
                context.scenarioContext().correlationId().value());
        return new Prepared(config, baseUrlResolver.resolve(endpoint.baseUrlRef()), headers);
    }

    /** Polls after all writes have been confirmed; publishes no variables. */
    public void await(Prepared prepared, SeedResult seed) {
        VisibilityConfig config = prepared.config();
        String account = seed.accounts().get(0);
        Map<String, String> query = new LinkedHashMap<>();
        config.query().forEach((key, value) -> query.put(expand(key, seed.pin(), account),
                expand(value, seed.pin(), account)));
        String path = expand(config.path(), seed.pin(), account);
        String expected = expand(config.bodyEquals(), seed.pin(), account);
        RestRequest request = new RestRequest("GET", prepared.baseUrl(), path, query, prepared.headers(), null);
        AwaitPolicy policy = AwaitPolicy.builder("eq.visibility " + config.service() + " " + config.path())
                .timeout(config.timeout()).pollInterval(config.pollInterval()).ignoreExceptions(false).build();
        long started = System.nanoTime();
        AwaitResult<Observation> result = awaiter.await(policy,
                () -> observe(request, config, expected, started), Observation::visible);
        if (!result.satisfied()) {
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("eq.visibility.service", config.service());
            diagnostics.put("eq.visibility.attempts", result.attempts());
            diagnostics.put("eq.visibility.elapsed.ms", result.elapsed().toMillis());
            diagnostics.put("eq.visibility.timeout.ms", config.timeout().toMillis());
            if (result.value() != null) {
                diagnostics.put("eq.visibility.last.status", result.value().status());
            }
            throw new EqSeedException("VISIBILITY_TIMEOUT", "visibility-probe",
                    "EQ visibility probe timed out for service '" + config.service() + "' after "
                            + result.attempts() + " attempts").withDiagnostics(diagnostics);
        }
    }

    private Observation observe(RestRequest request, VisibilityConfig config, String expected, long started) {
        long remaining = config.timeout().toNanos() - (System.nanoTime() - started);
        Duration callTimeout = Duration.ofNanos(Math.max(1L, remaining));
        RestResponse response;
        try {
            response = caller.execute(request, callTimeout);
        } catch (RuntimeException transportFailure) {
            if (System.nanoTime() - started >= config.timeout().toNanos()) {
                return new Observation(false, 0);
            }
            throw new EqSeedException("TRANSPORT", "visibility-probe",
                    "EQ visibility transport failed for service '" + config.service() + "'");
        }
        if (response.statusCode() != config.expectStatus()) {
            return new Observation(false, response.statusCode());
        }
        try {
            Object actual = JsonPath.parse(response.body()).read(config.bodyPath());
            return new Observation(AssertionMatchers.matches(AssertionMatcher.EQUALS, expected, true, actual),
                    response.statusCode());
        } catch (RuntimeException absentOrInvalidBody) {
            return new Observation(false, response.statusCode());
        }
    }

    private static void checkTemplate(String value) {
        String withoutKnown = value.replace("{seed.pin}", "").replace("{seed.account}", "");
        if (withoutKnown.contains("{") || withoutKnown.contains("}") || value.contains("${")) {
            throw new StandTestException("EQ visibility template supports only {seed.pin} and {seed.account}");
        }
    }

    private static String expand(String value, String pin, String account) {
        return value.replace("{seed.pin}", pin).replace("{seed.account}", account);
    }

    /** Validated request components for one step; secret headers are never exposed in diagnostics. */
    public record Prepared(VisibilityConfig config, String baseUrl, Map<String, String> headers) {
        public Prepared {
            headers = Map.copyOf(headers);
        }
    }

    private record Observation(boolean visible, int status) {
    }
}
