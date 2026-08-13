package ru.alfa.stand.test.rest;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * {@link HttpCaller} backed by Spring {@link WebClient} on the JDK HttpClient connector.
 *
 * <p>The JDK connector ({@link JdkClientHttpConnector}) is used deliberately so that reactor-netty is
 * not required on the classpath. The call is made synchronously — the reactive pipeline is blocked on
 * the calling thread — preserving thread-confinement for the test. A transport-level failure (connect
 * error, timeout, IO) is surfaced as a {@link StandTestException} (an infrastructure error).
 */
public final class WebClientHttpCaller implements HttpCaller {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient webClient;
    private final Duration responseTimeout;

    /**
     * Creates a caller with a default WebClient (JDK connector, 10s connect / 30s response timeouts).
     */
    public WebClientHttpCaller() {
        this(defaultWebClient(), RESPONSE_TIMEOUT);
    }

    /**
     * Creates a caller around a provided WebClient and response timeout (for tests). Package-private so
     * the WebFlux type stays off this module's public API surface.
     *
     * @param webClient the web client to use
     * @param responseTimeout the maximum time to wait for a response
     */
    WebClientHttpCaller(WebClient webClient, Duration responseTimeout) {
        this.webClient = Objects.requireNonNull(webClient, "webClient must not be null");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout must not be null");
    }

    private static WebClient defaultWebClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        return WebClient.builder().clientConnector(new JdkClientHttpConnector(httpClient)).build();
    }

    @Override
    public RestResponse execute(RestRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            URI uri = buildUri(request);
            WebClient.RequestBodySpec spec = this.webClient
                    .method(HttpMethod.valueOf(request.method()))
                    .uri(uri)
                    .headers(headers -> request.headers().forEach(headers::add));
            WebClient.RequestHeadersSpec<?> requestSpec = spec;
            if (request.body() != null) {
                requestSpec = spec.bodyValue(request.body());
            }
            ResponseEntity<String> entity = requestSpec
                    .exchangeToMono(response -> response.toEntity(String.class))
                    .block(this.responseTimeout);
            return toResponse(request, entity);
        } catch (StandTestException standTestFailure) {
            throw standTestFailure;
        } catch (RuntimeException transportFailure) {
            throw new StandTestException("HTTP " + request.method() + " " + redactUserInfo(request.baseUrl()) + request.path() + " failed: " + transportFailure.getMessage(), transportFailure);
        }
    }

    /**
     * Redacts the userinfo of a base URL before it is echoed into a failure message: an ops-provided
     * base URL may carry {@code user:password@host} credentials that must never reach a report. The
     * wire URI built by {@link #buildUri} keeps the credentials — only the diagnostic text is redacted.
     */
    private static String redactUserInfo(String baseUrl) {
        int schemeEnd = baseUrl.indexOf("://");
        if (schemeEnd < 0) {
            return baseUrl;
        }
        int authorityEnd = baseUrl.indexOf('/', schemeEnd + 3);
        String authority = (authorityEnd < 0) ? baseUrl.substring(schemeEnd + 3) : baseUrl.substring(schemeEnd + 3, authorityEnd);
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            return baseUrl;
        }
        String redactedAuthority = "***@" + authority.substring(at + 1);
        String tail = (authorityEnd < 0) ? "" : baseUrl.substring(authorityEnd);
        return baseUrl.substring(0, schemeEnd + 3) + redactedAuthority + tail;
    }

    private static URI buildUri(RestRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(request.baseUrl())
                .path(UriUtils.encodePath(request.path(), StandardCharsets.UTF_8));
        request.query().forEach((name, value) -> builder.queryParam(encodeQueryComponent(name), encodeQueryComponent(value)));
        return builder.build(true).toUri();
    }

    private static String encodeQueryComponent(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8).replace("+", "%2B");
    }

    private static RestResponse toResponse(RestRequest request, ResponseEntity<String> entity) {
        if (entity == null) {
            throw new StandTestException("HTTP response for " + request.method() + " " + request.path() + " was empty");
        }
        Map<String, List<String>> headers = Map.copyOf(entity.getHeaders());
        String body = (entity.getBody() == null) ? "" : entity.getBody();
        return new RestResponse(entity.getStatusCode().value(), headers, body);
    }
}
