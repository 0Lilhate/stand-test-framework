package ru.alfa.stand.test.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Normalizes one AI-format step — a flat object with a {@code type} field and ergonomic nested fields — into
 * the yaml-surface field map the existing {@code RestStepTranslator}/{@code KafkaStepTranslator}/
 * {@code GrpcStepTranslator}/{@code DbStepTranslator} already consume. The JSON Schema that used to describe
 * this surface shipped in {@code stand-test-ai-schema}, a module removed deliberately; the rules below are
 * now the description.
 *
 * <p>Fail-closed: unknown AI fields and constructs that no adapter can execute yet are rejected here with
 * a clear {@link StandTestException} naming the supported alternative. The returned map is intermediate —
 * the per-family translator validates it and emits the final wire keys. This normalizer checks structure
 * only; the value-level guardrails (secret headers, SQL sleep functions, timeout bounds) are enforced at
 * run time by the core {@code DefaultScenarioValidator} inside the runner.
 */
final class AiStepNormalizer {

    private static final Set<String> REST_KNOWN =
            Set.of("id", "type", "description", "service", "path", "query", "headers", "correlation", "body", "expect", "assert", "capture");
    private static final Set<String> REST_EXPECT_KNOWN =
            Set.of("id", "type", "description", "service", "path", "query", "headers", "correlation", "timeout", "expect", "assert", "capture");
    private static final Set<String> KAFKA_SEND_KNOWN =
            Set.of("id", "type", "description", "topic", "key", "correlation", "payload");
    private static final Set<String> KAFKA_EXPECT_KNOWN =
            Set.of("id", "type", "description", "topic", "correlation", "timeout", "assert", "capture");
    private static final Set<String> DB_EXPECT_KNOWN =
            Set.of("id", "type", "description", "datasource", "timeout", "query", "params", "expect");
    private static final Set<String> GRPC_UNARY_KNOWN =
            Set.of("id", "type", "description", "target", "method", "correlation", "request", "timeout", "expect", "capture");

    private AiStepNormalizer() {
    }

    static Map<String, Object> normalize(String type, Map<String, Object> fields, String location) {
        return switch (type) {
            case "rest.expectEventually" -> restExpectEventually(fields, location);
            case "kafka.send" -> kafkaSend(fields, location);
            case "kafka.expect" -> kafkaExpect(fields, location);
            case "db.expectEventually" -> dbExpectEventually(fields, location);
            case "grpc.unary" -> grpcUnary(fields, location);
            default -> {
                if (type.startsWith("rest.")) {
                    yield rest(fields, location);
                }
                throw new StandTestException("Unsupported AI step type '" + type + "' at " + location
                        + " (supported: rest.get/post, rest.expectEventually, kafka.send, kafka.expect, db.expectEventually, grpc.unary)");
            }
        };
    }

    private static Map<String, Object> rest(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, REST_KNOWN, location);
        Map<String, Object> out = restCommon(fields, location);
        applyPayload(fields, out, "body", YamlStepKeys.BODY_RESOURCE, location);
        return out;
    }

    private static Map<String, Object> restExpectEventually(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, REST_EXPECT_KNOWN, location);
        Map<String, Object> out = restCommon(fields, location);
        copyIfPresent(fields, out, "timeout");
        return out;
    }

    /**
     * The fields both REST shapes carry. Assertions keep their full matcher form as the surface list —
     * {@code RestStepTranslator} routes them through {@code SurfaceValues.assertionsWithMatchers}, so all
     * five matchers are executable for the REST family, as they are for {@code grpc.unary}. Only
     * {@code kafka.expect} stays equals-only (see {@link #equalsAssertions}).
     */
    private static Map<String, Object> restCommon(Map<String, Object> fields, String location) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fields, out, "service", "path", "query", "headers", "capture");
        applyCorrelation(fields, out, "inject", YamlStepKeys.INJECT_CORRELATION_ID, location);
        applyExpectStatus(fields, out, location);
        if (fields.containsKey("assert")) {
            out.put("assert", checkedAssertItems(fields.get("assert"), location + ".assert"));
        }
        return out;
    }

    private static Map<String, Object> kafkaSend(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, KAFKA_SEND_KNOWN, location);
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fields, out, "topic", "key");
        applyCorrelation(fields, out, "inject", YamlStepKeys.INJECT_CORRELATION_ID, location);
        applyPayload(fields, out, "payload", YamlStepKeys.BODY_RESOURCE, location);
        return out;
    }

    private static Map<String, Object> kafkaExpect(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, KAFKA_EXPECT_KNOWN, location);
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fields, out, "topic", "timeout", "capture");
        applyCorrelation(fields, out, "fromContext", YamlStepKeys.CORRELATION_FROM_CONTEXT, location);
        if (fields.containsKey("assert")) {
            out.put("assert", equalsAssertions(fields.get("assert"), location + ".assert"));
        }
        return out;
    }

    private static Map<String, Object> grpcUnary(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, GRPC_UNARY_KNOWN, location);
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fields, out, "target", "method", "timeout", "capture");
        applyCorrelation(fields, out, "inject", YamlStepKeys.INJECT_CORRELATION_ID, location);
        applyPayload(fields, out, "request", YamlStepKeys.REQUEST_RESOURCE, location);
        applyGrpcExpect(fields, out, location);
        return out;
    }

    private static Map<String, Object> dbExpectEventually(Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, DB_EXPECT_KNOWN, location);
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(fields, out, "datasource", "timeout", "params");
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

    private static void applyPayload(Map<String, Object> fields, Map<String, Object> out, String aiField, String resourceKey, String location) {
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
        out.put(resourceKey, SurfaceValues.requireString(payload, "fixture", payloadLoc));
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

    private static void applyGrpcExpect(Map<String, Object> fields, Map<String, Object> out, String location) {
        if (!fields.containsKey("expect")) {
            return;
        }
        String expectLoc = location + ".expect";
        Map<String, Object> expect = SurfaceValues.asMap(fields.get("expect"), expectLoc);
        SurfaceValues.checkKnownKeys(expect, Set.of("status", "assert"), expectLoc);
        if (expect.containsKey("status")) {
            throw new StandTestException("'expect.status' at " + expectLoc + " is not executable yet: the gRPC status is surfaced as an exception, not a declarative assertion");
        }
        if (expect.containsKey("assert")) {
            out.put("assert", checkedAssertItems(expect.get("assert"), expectLoc + ".assert"));
        }
    }

    /**
     * Validates every item of a matcher-form {@code assert} list and returns it unchanged, for the two
     * surfaces that execute the full matcher set (REST and {@code grpc.unary}). The translator does the
     * surface→wire mapping later, through {@code SurfaceValues.assertionsWithMatchers}.
     */
    private static List<Object> checkedAssertItems(Object value, String assertLoc) {
        List<Object> items = SurfaceValues.asList(value, assertLoc);
        return IntStream.range(0, items.size())
                .mapToObj(index -> {
                    String itemLoc = assertLoc + "[" + index + "]";
                    SurfaceValues.checkKnownKeys(SurfaceValues.asMap(items.get(index), itemLoc), SurfaceValues.ASSERT_ITEM_KEYS, itemLoc);
                    return items.get(index);
                })
                .toList();
    }

    /**
     * Folds a matcher-form {@code assert} list into the equals-only map shorthand {@code kafka.expect}
     * executes, refusing any matcher its executor cannot run.
     */
    private static Map<String, Object> equalsAssertions(Object value, String assertLoc) {
        List<Object> items = SurfaceValues.asList(value, assertLoc);
        return IntStream.range(0, items.size())
                .mapToObj(index -> equalsAssertion(items.get(index), assertLoc + "[" + index + "]"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> second, LinkedHashMap::new));
    }

    private static Map.Entry<String, Object> equalsAssertion(Object node, String itemLoc) {
        Map<String, Object> item = SurfaceValues.asMap(node, itemLoc);
        SurfaceValues.checkKnownKeys(item, SurfaceValues.ASSERT_ITEM_KEYS, itemLoc);
        String path = SurfaceValues.requireString(item, "path", itemLoc);
        if (SurfaceValues.declaresNonEqualsMatcher(item)) {
            throw new StandTestException("Assertion at " + itemLoc + " uses a matcher that kafka.expect cannot execute: kafka.expect runs 'equals' only (REST and grpc.unary support the full matcher set)");
        }
        Object expected = item.get("equals");
        if (expected == null) {
            throw new StandTestException("Assertion at " + itemLoc + " must declare a non-null 'equals' value");
        }
        return Map.entry(path, expected);
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

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String... keys) {
        Stream.of(keys)
                .filter(from::containsKey)
                .forEach(key -> to.put(key, from.get(key)));
    }
}
