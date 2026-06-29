package ru.alfa.stand.test.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TimeoutDiagnosticsTest {

    private static TimeoutDiagnostics sample() {
        return new TimeoutDiagnostics(
                "wait-for-status",
                Duration.ofSeconds(30),
                Duration.ofMillis(200),
                5,
                Duration.ofSeconds(30),
                "PENDING",
                "java.lang.IllegalStateException: nope",
                Map.of());
    }

    @Test
    @DisplayName("toMap exposes the intrinsic fields plus attributes, omitting null value/error")
    void toMap_containsIntrinsicAndAttributes() {
        Map<String, Object> map = sample().withAttribute("scenarioId", "sc-1").toMap();

        assertThat(map)
                .containsEntry("await", "wait-for-status")
                .containsEntry("timeout", "PT30S")
                .containsEntry("pollInterval", "PT0.2S")
                .containsEntry("attempts", 5)
                .containsEntry("elapsed", "PT30S")
                .containsEntry("lastValue", "PENDING")
                .containsEntry("lastError", "java.lang.IllegalStateException: nope")
                .containsEntry("scenarioId", "sc-1");
        assertThatThrownBy(() -> map.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("toMap omits last value and error when they are null")
    void toMap_omitsNullValueAndError() {
        TimeoutDiagnostics diagnostics = new TimeoutDiagnostics(
                "wait", Duration.ofSeconds(1), Duration.ofMillis(100), 2, Duration.ofSeconds(1), null, null, null);

        assertThat(diagnostics.toMap()).doesNotContainKeys("lastValue", "lastError");
        assertThat(diagnostics.attributes()).isEmpty();
    }

    @Test
    @DisplayName("toMap never lets an attribute overwrite an intrinsic key")
    void toMap_attributesDoNotClobberIntrinsics() {
        Map<String, Object> map = sample().withAttribute("attempts", 999).withAttribute("custom", "x").toMap();

        assertThat(map).containsEntry("attempts", 5);
        assertThat(map).containsEntry("custom", "x");
    }

    @Test
    @DisplayName("withAttribute is copy-on-write and leaves the original untouched")
    void withAttribute_isCopyOnWrite() {
        TimeoutDiagnostics original = sample();
        TimeoutDiagnostics enriched = original.withAttribute("testRunId", "run-9");

        assertThat(original.attributes()).isEmpty();
        assertThat(enriched.attributes()).containsEntry("testRunId", "run-9");
    }

    @Test
    @DisplayName("attributes are defensively copied and exposed as immutable")
    void attributes_areImmutableAndCopied() {
        Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("a", 1);
        TimeoutDiagnostics diagnostics = new TimeoutDiagnostics(
                "w", Duration.ofSeconds(1), Duration.ofMillis(100), 1, Duration.ofSeconds(1), null, null, attributes);

        attributes.put("leak", 2);

        assertThat(diagnostics.attributes()).containsOnlyKeys("a");
        assertThatThrownBy(() -> diagnostics.attributes().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("summary mentions the description, attempts, elapsed and timeout")
    void summary_containsKeyFacts() {
        assertThat(sample().summary())
                .contains("wait-for-status")
                .contains("attempts=5")
                .contains("elapsed=PT30S")
                .contains("PT30S")
                .contains("lastValue=PENDING")
                .contains("lastError=java.lang.IllegalStateException: nope");
    }

    @Test
    @DisplayName("invalid arguments and attribute keys are rejected")
    void invalidArguments_areRejected() {
        assertThatThrownBy(() -> new TimeoutDiagnostics(" ", Duration.ofSeconds(1), Duration.ofMillis(1), 1, Duration.ZERO, null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeoutDiagnostics("w", null, Duration.ofMillis(1), 1, Duration.ZERO, null, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TimeoutDiagnostics("w", Duration.ofSeconds(1), Duration.ofMillis(1), 0, Duration.ZERO, null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample().withAttribute(" ", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample().withAttribute("k", null)).isInstanceOf(NullPointerException.class);
    }
}
