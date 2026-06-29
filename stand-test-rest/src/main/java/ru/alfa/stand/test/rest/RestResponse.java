package ru.alfa.stand.test.rest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A captured HTTP response.
 *
 * @param statusCode the HTTP status code
 * @param headers the response headers, each name mapped to its values
 * @param body the response body (empty string when absent, never null)
 */
public record RestResponse(int statusCode, Map<String, List<String>> headers, String body) {

    public RestResponse {
        headers = (headers == null) ? Map.of() : Map.copyOf(headers);
        body = (body == null) ? "" : body;
    }

    /**
     * Returns the first value of the named header, matched case-insensitively (HTTP header names are
     * case-insensitive, RFC 7230).
     *
     * @param name the header name
     * @return the first header value, if present
     */
    public Optional<String> header(String name) {
        Objects.requireNonNull(name, "name must not be null");
        for (Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return Optional.ofNullable(entry.getValue().get(0));
            }
        }
        return Optional.empty();
    }
}
