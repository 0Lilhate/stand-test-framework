package ru.alfa.stand.test.eq.backend.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public final class GatewayEnvelope {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GatewayEnvelope() {
    }

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