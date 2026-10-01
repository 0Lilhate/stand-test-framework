package ru.alfa.stand.test.http;

import java.time.Duration;

/**
 * Abstraction over the real HTTP transport.
 *
 * <p>This seam keeps the HTTP library isolated behind one interface and lets adapter executors
 * be unit-tested without a live server. Implementations perform IO only — they apply no SDK logic
 * (variable resolution, correlation injection and assertions all happen in the executor).
 */
public interface HttpCaller {

    /**
     * Executes the given request and returns the captured response.
     *
     * @param request the fully-resolved request
     * @return the captured response
     */
    RestResponse execute(RestRequest request);

    /** Executes one request with an upper bound when the transport supports per-call timeouts. */
    default RestResponse execute(RestRequest request, Duration timeout) {
        return execute(request);
    }
}
