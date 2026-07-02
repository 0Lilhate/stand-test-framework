package ru.alfa.stand.test.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Normalizes one AI-format step — a flat object with a {@code type} field and ergonomic nested fields
 * (as described by the JSON Schema in {@code stand-test-ai-schema}) — into the yaml-surface field map the
 * existing {@code RestStepTranslator}/{@code KafkaStepTranslator}/{@code DbStepTranslator} already consume.
 *
 * <p>Fail-closed: unknown AI fields (defence in depth even if the schema was not run first) and constructs
 * that no adapter can execute yet are rejected here with a clear {@link StandTestException} naming the
 * supported alternative. The returned map is intermediate — the per-family translator validates it and
 * emits the final wire keys.
 */
final class AiStepNormalizer {

    private static final Set<String> REST_KNOWN =
            Set.of("id", "type", "description", "service", "path", "query", "headers", "correlation", "body", "expect", "capture");
    private static final Set<String> KAFKA_SEND_KNOWN =
            Set.of("id", "type", "description", "topic", "key", "correlation", "payload");
    private static final Set<String> KAFKA_EXPECT_KNOWN =
            Set.of("id", "type", "description", "topic", "correlation", "timeout", "assert", "capture");
    private static final Set<String> DB_EXPECT_KNOWN =
            Set.of("id", "type", "description", "datasource", "timeout", "query", "params", "expect");
    private static final Set<String> ASSERT_KNOWN = Set.of("path", "equals", "exists", "notNull", "contains", "matches");

    private AiStepNormalizer() {
    }

    static Map<String, Object> normalize(String type, Map<String, Object> fields, String location) {
        if (type.startsWith("rest.")) {
            return rest(fields, location);
        }
        if ("kafka.send".equals(type)) {
            return kafkaSend(fields, location);
        }
        if ("kafka.expect".equals(type)) {
            return kafkaExpect(fields, location);
        }
        if ("db.expectEventually".equals(type)) {
            return dbExpectEventually(fields, location);
        }
        if ("grpc.unary".equals(type)) {
            throw new StandTestException("Step type 'grpc.unary' at " + location + " is not executable yet: the stand-test-grpc adapter is not implemented");
        }
        throw new StandTestException("Unsupported AI step type '" + type + "' at " + location + " (supported: rest.get/post, kafka.send, kafka.expect, db.expectEventually)");
    }

    private static Map<String, Object> rest(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, REST_KNOWN, location);
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fields, out, "service");
        copyIfPresent(fields, out, "path");
        copyIfPresent(fields, out, "query");
        copyIfPresent(fields, out, "headers");
        copyIfPresent(fields, out, "capture");
        applyCorrelation(fields, out, "inject", YamlStepKeys.INJECT_CORRELATION_ID, location);
        applyPayload(fields, out, "body", location);
        applyExpectStatus(fields, out, location);
        return out;
    }

    private static Map<String, Object> kafkaSend(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, KAFKA_SEND_KNOWN, location);
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fields, out, "topic");
        copyIfPresent(fields, out, "key");
        applyCorrelation(fields, out, "inject", YamlStepKeys.INJECT_CORRELATION_ID, location);
        applyPayload(fields, out, "payload", location);
        return out;
    }

    private static Map<String, Object> kafkaExpect(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, KAFKA_EXPECT_KNOWN, location);
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fields, out, "topic");
        copyIfPresent(fields, out, "timeout");
        copyIfPresent(fields, out, "capture");
        applyCorrelation(fields, out, "fromContext", YamlStepKeys.CORRELATION_FROM_CONTEXT, location);
        applyAssert(fields, out, location);
        return out;
    }

    private static Map<String, Object> dbExpectEventually(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, DB_EXPECT_KNOWN, location);
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fields, out, "datasource");
        copyIfPresent(fields, out, "timeout");
        copyIfPresent(fields, out, "params");
        if (fields.containsKey("query")) {
            out.put("sql", fields.get("query"));
        }
        applyDbExpect(fields, out, location);
        return out;
    }

    private static void applyCorrelation(Map<String, Object> fields, Map<String, Object> out,
            String innerKey, String surfaceKey, String location) {
        if (!fields.containsKey("correlation")) {
            return;
        }
        String correlationLoc = location + ".correlation";
        Map<String, Object> correlation = SurfaceValues.asMap(fields.get("correlation"), correlationLoc);
        SurfaceValues.checkKnownKeys(correlation, Set.of(innerKey), correlationLoc);
        out.put(surfaceKey, SurfaceValues.boolFlag(correlation, innerKey, correlationLoc));
    }

    private static void applyPayload(Map<String, Object> fields, Map<String, Object> out, String aiField, String location) {
        if (!fields.containsKey(aiField)) {
            return;
        }
        String payloadLoc = location + "." + aiField;
        Map<String, Object> payload = SurfaceValues.asMap(fields.get(aiField), payloadLoc);
        SurfaceValues.checkKnownKeys(payload, Set.of("fixture", "json"), payloadLoc);
        if (payload.containsKey("json")) {
            throw new StandTestException("Inline '" + aiField + ".json' at " + payloadLoc
                    + " is not executable yet: use '" + aiField + ".fixture' (a classpath resource)");
        }
        out.put(YamlStepKeys.BODY_RESOURCE, SurfaceValues.requireString(payload, "fixture", payloadLoc));
    }

    private static void applyExpectStatus(Map<String, Object> fields, Map<String, Object> out, String location) {
        if (!fields.containsKey("expect")) {
            return;
        }
        String expectLoc = location + ".expect";
        Map<String, Object> expect = SurfaceValues.asMap(fields.get("expect"), expectLoc);
        SurfaceValues.checkKnownKeys(expect, Set.of("status"), expectLoc);
        out.put("expectStatus", SurfaceValues.requireInteger(expect, "status", expectLoc));
    }

    private static void applyAssert(Map<String, Object> fields, Map<String, Object> out, String location) {
        if (!fields.containsKey("assert")) {
            return;
        }
        String assertLoc = location + ".assert";
        List<Object> items = SurfaceValues.asList(fields.get("assert"), assertLoc);
        Map<String, Object> equalsMap = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String itemLoc = assertLoc + "[" + i + "]";
            Map<String, Object> item = SurfaceValues.asMap(items.get(i), itemLoc);
            SurfaceValues.checkKnownKeys(item, ASSERT_KNOWN, itemLoc);
            String path = SurfaceValues.requireString(item, "path", itemLoc);
            if (item.containsKey("exists") || item.containsKey("notNull")
                    || item.containsKey("contains") || item.containsKey("matches")) {
                throw new StandTestException("Assertion at " + itemLoc + " uses a matcher that is not executable yet: only 'equals' is supported by the runtime");
            }
            Object expected = item.get("equals");
            if (expected == null) {
                throw new StandTestException("Assertion at " + itemLoc + " must declare a non-null 'equals' value");
            }
            equalsMap.put(path, expected);
        }
        out.put("assert", equalsMap);
    }

    private static void applyDbExpect(Map<String, Object> fields, Map<String, Object> out, String location) {
        if (!fields.containsKey("expect")) {
            throw new StandTestException("db.expectEventually at " + location + " requires 'expect.singleValue'");
        }
        String expectLoc = location + ".expect";
        Map<String, Object> expect = SurfaceValues.asMap(fields.get("expect"), expectLoc);
        SurfaceValues.checkKnownKeys(expect, Set.of("singleValue", "rowExists"), expectLoc);
        if (expect.containsKey("rowExists")) {
            throw new StandTestException("'expect.rowExists' at " + expectLoc + " is not executable yet: use 'expect.singleValue' (equals the first column)");
        }
        Object single = expect.get("singleValue");
        if (single == null) {
            throw new StandTestException("'expect.singleValue' at " + expectLoc + " must be present and non-null");
        }
        out.put("equals", single);
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }
}
