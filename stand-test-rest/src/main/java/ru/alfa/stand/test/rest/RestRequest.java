package ru.alfa.stand.test.rest;

import java.util.Map;
import java.util.Objects;

/**
 * A fully-resolved HTTP request handed to an {@link HttpCaller}.
 *
 * <p>All {@code ${...}} placeholders are already substituted and the correlation header (if any) is
 * already present in {@code headers}, so the caller performs no SDK logic. The maps are defensively
 * copied and exposed as immutable.
 *
 * @param method the HTTP method name (for example {@code GET})
 * @param baseUrl the absolute base URL (scheme + host + optional port)
 * @param path the request path appended to the base URL
 * @param query the query parameters
 * @param headers the request headers
 * @param body the request body, or null when there is none
 */
public record RestRequest(
        String method,
        String baseUrl,
        String path,
        Map<String, String> query,
        Map<String, String> headers,
        String body) {

    public RestRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        Objects.requireNonNull(path, "path must not be null");
        query = (query == null) ? Map.of() : Map.copyOf(query);
        headers = (headers == null) ? Map.of() : Map.copyOf(headers);
    }

    /**
     * Redacted string form: header and query VALUES never appear (the headers map can carry a
     * resolved {@code Authorization} credential), only their names — so an accidentally logged or
     * re-thrown request cannot leak a secret.
     */
    @Override
    public String toString() {
        return "RestRequest[method=" + this.method
                + ", path=" + this.path
                + ", query=" + this.query.keySet()
                + ", headers=" + this.headers.keySet()
                + ", body=" + (this.body == null ? "null" : this.body.length() + " chars") + "]";
    }
}
