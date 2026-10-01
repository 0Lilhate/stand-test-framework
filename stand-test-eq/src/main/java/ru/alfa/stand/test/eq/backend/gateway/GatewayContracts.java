package ru.alfa.stand.test.eq.backend.gateway;

import ru.alfa.stand.test.eq.EqSeedException;
import ru.alfa.stand.test.http.RestResponse;

/**
 * Validates a gateway operation response before the chain treats it as confirmed (BR-22).
 *
 * <p>It is a seam so the default fail-closed policy — which refuses {@code YFT2}/{@code KP1} while their
 * contracts are open (OQ-5) — can be replaced once a redacted sample of one controlled chain is captured,
 * without touching the chain itself. The offline tests use a permissive implementation to exercise ordering
 * and failure handling, and a separate test pins the default policy's refusal.
 */
@FunctionalInterface
public interface GatewayContracts {

    /**
     * Confirms one operation's response, returning a safe identifier (PIN/account) when the contract is
     * confirmed.
     *
     * @param operation the operation name ({@code ONU}, {@code OKC}, {@code YFT2}, …)
     * @param response the raw response
     * @return the confirmed identifier, or the operation name for operations that issue none
     * @throws EqSeedException when the response is an error, an unexpected status, or an unverifiable shape
     */
    String confirm(String operation, RestResponse response);
}