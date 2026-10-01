package ru.alfa.stand.test.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class WebClientHttpCallerTest {

    @Test
    @DisplayName("NFR-04: the HTTP caller accepts explicit positive connect and response timeouts")
    void configurableTimeouts() {
        assertThat(WebClientHttpCaller.create(Duration.ofSeconds(4), Duration.ofSeconds(60))).isNotNull();
        assertThatThrownBy(() -> WebClientHttpCaller.create(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeout");
        assertThatThrownBy(() -> WebClientHttpCaller.create(Duration.ofSeconds(1), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("responseTimeout");
    }

    @Test
    void perCallTimeoutBoundsAProbeWhoseResponseNeverArrives() {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.never()).build();
        WebClientHttpCaller caller = new WebClientHttpCaller(client, Duration.ofSeconds(2));
        RestRequest request = new RestRequest("GET", "http://example.invalid", "/visible", Map.of(), Map.of(), null);

        Instant started = Instant.now();
        assertThatThrownBy(() -> caller.execute(request, Duration.ofMillis(40)))
                .isInstanceOf(ru.alfa.stand.test.core.exception.StandTestException.class);
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(1));
    }
}
