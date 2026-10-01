package ru.alfa.stand.test.rest;

import ru.alfa.stand.test.http.RestResponse;

import java.util.Objects;

/**
 * One {@code rest.expectEventually} probe observation: the response plus the first unmet expectation
 * (null when all expectations hold).
 *
 * <p>The await engine records the last probe value into its timeout diagnostics and renders it with
 * {@code String.valueOf}, so this record's string form is deliberately curated: it carries the HTTP
 * status and the mismatch description only — never headers or the response body (which a raw
 * {@link RestResponse} record toString would leak into failure messages and reports).
 *
 * @param response the observed response (kept for the final capture pass)
 * @param mismatch the first unmet expectation, or null when the probe satisfied all of them
 */
record PollProbe(RestResponse response, String mismatch) {

    PollProbe {
        Objects.requireNonNull(response, "response must not be null");
    }

    @Override
    public String toString() {
        return "HTTP " + this.response.statusCode() + (this.mismatch == null ? "" : ": " + this.mismatch);
    }
}
