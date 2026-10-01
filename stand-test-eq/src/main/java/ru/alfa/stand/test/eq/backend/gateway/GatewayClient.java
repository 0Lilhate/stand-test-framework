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

/**
 * One gateway call: {@code POST {base-url}} with the {@code {unit, option, params}} envelope.
 *
 * <p>The client owns no retry: a write operation must never be repeated automatically (BR-25), so a
 * transport failure surfaces as a distinct "state unknown" category and the caller decides. Connect and
 * response timeouts come from the registry (NFR-04, Г-7, Г-8); a timeout is {@code TIMEOUT_UNKNOWN}.
 *
 * <p>Auth, when the registry declares it, is injected here from its references (SEC-03); the credential is
 * never logged or attached. Correlation injection is deliberately absent: OQ-16 — whether the gateway
 * accepts and traces a correlation header — is unresolved, so the SDK does not claim it. This exception is
 * recorded in the module README, exactly as BR-22 requires for a gateway that does not accept the header.
 */
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

    /**
     * Sends one operation and returns the raw response.
     *
     * @param unit the resolved unit
     * @param option the operation name
     * @param params the operation parameters
     * @return the raw response
     * @throws EqSeedException with category {@code TIMEOUT_UNKNOWN} on a transport failure or timeout
     */
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