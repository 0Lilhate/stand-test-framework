package ru.alfa.stand.test.eq.config;

import java.time.Duration;
import java.util.Map;

/** An optional read-only REST probe run after a seed operation. */
public record VisibilityConfig(
        String service,
        String path,
        Map<String, String> query,
        int expectStatus,
        String bodyPath,
        String bodyEquals,
        Duration timeout,
        Duration pollInterval) {

    public VisibilityConfig {
        query = Map.copyOf(query);
    }
}
