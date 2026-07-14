package ru.alfa.stand.test.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates a {@code grpc.unary} surface node into the {@code GenericStep} parameters the gRPC executor
 * reads. Mirrors {@code GrpcStep.build()}: {@code target}/{@code method} are required; {@code timeout}
 * becomes the mandatory {@code deadlineMillis}; the request is an inline value or classpath resource
 * ({@code request}/{@code requestResource}); {@code injectCorrelationId}, {@code assertions} and
 * {@code captures} follow the shared JSONPath model. Only executable constructs reach here — the AI
 * normalizer already fails closed on {@code request.json}, {@code expect.status} and non-{@code equals}
 * matchers.
 */
final class GrpcStepTranslator {

    private static final Set<String> UNARY_KNOWN =
            Set.of("id", "target", "method", "timeout", "injectCorrelationId", "request", "requestResource", "assert", "capture");

    private GrpcStepTranslator() {
    }

    static Map<String, Object> params(String type, Map<String, Object> fields, String location) {
        SurfaceValues.checkKnownKeys(fields, UNARY_KNOWN, location);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(YamlStepKeys.TARGET, SurfaceValues.requireString(fields, "target", location));
        params.put(YamlStepKeys.METHOD_FULL_NAME, SurfaceValues.requireString(fields, "method", location));
        params.put(YamlStepKeys.DEADLINE_MILLIS, SurfaceValues.durationMillis(fields.get("timeout"), location + ".timeout"));
        // Emit the flag only when the surface set it explicitly, so its absence means "default" (inject when
        // the target declares a METADATA correlation carrier) rather than an explicit opt-out.
        if (fields.containsKey("injectCorrelationId")) {
            params.put(YamlStepKeys.INJECT_CORRELATION_ID, SurfaceValues.boolFlag(fields, "injectCorrelationId", location));
        }
        SurfaceValues.putInlineOrResource(params, fields, "request", "requestResource",
                YamlStepKeys.REQUEST, YamlStepKeys.REQUEST_RESOURCE, false, location);
        params.put(YamlStepKeys.ASSERTIONS, fields.containsKey("assert")
                ? SurfaceValues.assertionsWithMatchers(fields.get("assert"), location + ".assert") : List.of());
        params.put(YamlStepKeys.CAPTURES, fields.containsKey("capture")
                ? SurfaceValues.captures(fields.get("capture"), YamlStepKeys.JSON_PATH, location + ".capture") : List.of());
        return params;
    }
}
