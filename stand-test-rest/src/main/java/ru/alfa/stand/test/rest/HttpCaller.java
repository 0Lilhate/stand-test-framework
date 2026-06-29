package ru.alfa.stand.test.rest;

/**
 * Abstraction over the real HTTP transport.
 *
 * <p>This seam keeps the HTTP library isolated behind one interface and lets {@link RestStepExecutor}
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
}
