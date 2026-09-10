package ru.alfa.stand.test.scenario;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Translates a {@code rest.<method>} surface node into the {@code GenericStep} parameters the REST executor
 * reads. Mirrors {@code RestStep.build()}: {@code method}/{@code service}/{@code path}/{@code query}/
 * {@code headers}/{@code injectCorrelationId}/{@code assertions}/{@code captures} are always written (empty
 * when absent); {@code expectedStatus} and {@code body}/{@code bodyResource} are written only when present.
 */
final class RestStepTranslator {

    private static final Set<String> KNOWN = Set.of("id", "service", "path", "query", "headers",
            "body", "bodyResource", "injectCorrelationId", "expectStatus", "assert", "capture");
    private static final Set<String> EXPECT_KNOWN = Set.of("id", "service", "path", "query", "headers",
            "injectCorrelationId", "expectStatus", "assert", "capture", "timeout", "pollInterval");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    private RestStepTranslator() {
    }

    static Map<String, Object> params(String type, Map<String, Object> fields, String location) {
        if ("rest.expectEventually".equals(type)) {
            return expectEventually(fields, location);
        }
        SurfaceValues.checkKnownKeys(fields, KNOWN, location);
        String method = type.substring("rest.".length()).toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new StandTestException("Unsupported REST method in '" + type + "' at " + location
                    + " (use rest.get/post/put/delete or rest.expectEventually)");
        }
        Map<String, Object> params = common(fields, method, location);
        SurfaceValues.putInlineOrResource(params, fields, "body", "bodyResource",
                YamlStepKeys.BODY, YamlStepKeys.BODY_RESOURCE, false, location);
        return params;
    }

    /**
     * The GET-only polling step: {@code timeout}/{@code pollInterval} map to the bounded await keys,
     * a body is structurally impossible (not in the known-key set) and at least one expectation
     * ({@code expectStatus} or {@code assert}) is required — mirroring {@code RestStep.build()}.
     */
    private static Map<String, Object> expectEventually(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, EXPECT_KNOWN, location);
        Map<String, Object> params = common(fields, "GET", location);
        SurfaceValues.putOptionalDuration(params, fields, "timeout", YamlStepKeys.TIMEOUT_MILLIS, location);
        SurfaceValues.putOptionalDuration(params, fields, "pollInterval", YamlStepKeys.POLL_INTERVAL_MILLIS, location);
        if (!fields.containsKey("expectStatus") && !fields.containsKey("assert")) {
            throw new StandTestException("rest.expectEventually at " + location
                    + " requires at least one expectation: 'expectStatus' or 'assert'");
        }
        return params;
    }

    private static Map<String, Object> common(Map<String, Object> fields, String method, String location) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(YamlStepKeys.METHOD, method);
        params.put(YamlStepKeys.SERVICE, SurfaceValues.requireString(fields, "service", location));
        params.put(YamlStepKeys.PATH, SurfaceValues.requireString(fields, "path", location));
        params.put(YamlStepKeys.QUERY, SurfaceValues.stringMap(fields.get("query"), location + ".query"));
        params.put(YamlStepKeys.HEADERS, SurfaceValues.stringMap(fields.get("headers"), location + ".headers"));
        SurfaceValues.putOptionalFlag(params, fields, "injectCorrelationId", YamlStepKeys.INJECT_CORRELATION_ID, location);
        SurfaceValues.putAssertions(params, fields, true, location);
        SurfaceValues.putCaptures(params, fields, YamlStepKeys.JSON_PATH, location);
        if (fields.containsKey("expectStatus")) {
            params.put(YamlStepKeys.EXPECTED_STATUS, SurfaceValues.requireInteger(fields, "expectStatus", location));
        }
        return params;
    }
}
