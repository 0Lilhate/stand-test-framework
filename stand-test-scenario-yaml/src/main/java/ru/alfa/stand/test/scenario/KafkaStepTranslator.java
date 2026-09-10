package ru.alfa.stand.test.scenario;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Translates a {@code kafka.send}/{@code kafka.expect} surface node into the {@code GenericStep} parameters
 * the Kafka executor reads. Mirrors {@code KafkaStep.build()}: {@code topic}/{@code headers} (and an
 * optional {@code key}) are common; {@code send} adds {@code body}/{@code bodyResource} +
 * {@code injectCorrelationId}; {@code expect} adds {@code correlationIdFromContext}/{@code assertions}/
 * {@code captures} plus {@code timeoutMillis}/{@code pollTimeoutMillis} only when the surface sets them
 * (the executor defaults them otherwise).
 */
final class KafkaStepTranslator {

    private static final Set<String> SEND_KNOWN = Set.of("id", "topic", "body", "bodyResource", "key", "headers", "injectCorrelationId");
    private static final Set<String> EXPECT_KNOWN = Set.of("id", "topic", "key", "headers", "correlationIdFromContext", "timeout",
            "pollTimeout", "assert", "capture");

    private KafkaStepTranslator() {
    }

    static Map<String, Object> params(String type, Map<String, Object> fields, String location) {
        boolean send = "kafka.send".equals(type);
        SurfaceValues.checkKnownKeys(fields, send ? SEND_KNOWN : EXPECT_KNOWN, location);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(YamlStepKeys.TOPIC, SurfaceValues.requireString(fields, "topic", location));
        params.put(YamlStepKeys.HEADERS, SurfaceValues.stringMap(fields.get("headers"), location + ".headers"));
        String key = SurfaceValues.optionalString(fields, "key", location);
        if (key != null) {
            params.put(YamlStepKeys.KEY, key);
        }
        if (send) {
            SurfaceValues.putInlineOrResource(params, fields, "body", "bodyResource",
                    YamlStepKeys.BODY, YamlStepKeys.BODY_RESOURCE, true, location);
            SurfaceValues.putOptionalFlag(params, fields, "injectCorrelationId", YamlStepKeys.INJECT_CORRELATION_ID, location);
        } else {
            params.put(YamlStepKeys.CORRELATION_FROM_CONTEXT, SurfaceValues.boolFlag(fields, "correlationIdFromContext", location));
            SurfaceValues.putAssertions(params, fields, false, location);
            SurfaceValues.putCaptures(params, fields, YamlStepKeys.JSON_PATH, location);
            SurfaceValues.putOptionalDuration(params, fields, "timeout", YamlStepKeys.TIMEOUT_MILLIS, location);
            SurfaceValues.putOptionalDuration(params, fields, "pollTimeout", YamlStepKeys.POLL_TIMEOUT_MILLIS, location);
        }
        return params;
    }
}
