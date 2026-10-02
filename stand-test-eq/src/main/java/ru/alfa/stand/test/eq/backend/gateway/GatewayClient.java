package ru.alfa.stand.test.eq.backend.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.eq.EqSeedException;
import ru.alfa.stand.test.http.AuthHeaderResolver;
import ru.alfa.stand.test.http.HttpCaller;
import ru.alfa.stand.test.http.RestRequest;
import ru.alfa.stand.test.http.RestResponse;

public final class GatewayClient {

    private final HttpCaller caller;
    private final String baseUrl;
    private final AuthHeaderResolver authHeaderResolver;
    private final AuthConfig auth;
    private final Duration responseTimeout;

    public GatewayClient(HttpCaller caller, String baseUrl, AuthHeaderResolver authHeaderResolver, AuthConfig auth,
                         Duration responseTimeout) {
        this.caller = Objects.requireNonNull(caller, "caller must not be null");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        this.baseUrl = baseUrl;
        this.authHeaderResolver = Objects.requireNonNull(authHeaderResolver, "authHeaderResolver must not be null");
        this.auth = auth;
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout must not be null");
    }

    /** The resolved base URL. */
    public String baseUrl() {
        return baseUrl;
    }

    public RestResponse call(String unit, String option, Map<String, Object> params) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (auth != null) {
            headers.put("Authorization", authHeaderResolver.resolve(auth));
        }
        RestRequest request = new RestRequest("POST", baseUrl, "", Map.of(), headers,
                GatewayEnvelope.toJson(unit, option, params));
        try {
            return caller.execute(request, responseTimeout);
        } catch (RuntimeException failure) {
            throw new EqSeedException("TIMEOUT_UNKNOWN", option,
                    "EQ gateway " + option + " gave no response within " + responseTimeout.toMillis()
                            + " ms; the state is unknown and the operation is NOT retried", failure);
        }
    }
}