package ru.alfa.stand.test.eq.backend.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the gateway request envelope {@code {unit, option, params}} as a JSON string.
 *
 * <p>Jackson 3 serialises the body rather than a string template: names and values reach a real EQ, so
 * escaping must not depend on luck (NFR-01). The envelope carries no SDK identifier beyond what each
 * operation supplies; {@code correlationId} rides in a header, not the body.
 */
public final class GatewayEnvelope {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GatewayEnvelope() {
    }

    /** Serialises one operation request. */
    public static String toJson(String unit, String option, Map<String, Object> params) {
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit must not be blank");
        }
        if (option == null || option.isBlank()) {
            throw new IllegalArgumentException("option must not be blank");
        }
        Objects.requireNonNull(params, "params must not be null");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("unit", unit);
        envelope.put("option", option);
        envelope.put("params", params);
        return JSON.writeValueAsString(envelope);
    }
}