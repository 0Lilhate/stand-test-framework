package ru.alfa.stand.test.core.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StepResultTest {

    private static final Instant START = Instant.parse("2026-06-26T10:00:00Z");
    private static final Instant END = START.plusMillis(5);

    @Test
    @DisplayName("factory methods set the expected status and timing")
    void factories_setStatus() {
        assertThat(StepResult.success("s", "rest.post", START, END).status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(StepResult.failed("s", "rest.post", START, END, "boom").status()).isEqualTo(StepStatus.FAILED);
        assertThat(StepResult.timeout("s", "rest.post", START, END, "late").status()).isEqualTo(StepStatus.TIMEOUT);
        assertThat(StepResult.skipped("s", "rest.post", START, END).status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(StepResult.success("s", "rest.post", START, END).duration()).isEqualTo(Duration.ofMillis(5));
        assertThat(StepResult.success("s", "rest.post", START, END).errorMessage()).isNull();
    }

    @Test
    @DisplayName("diagnostics are defensively copied and exposed as immutable")
    void diagnostics_areImmutableAndDefensivelyCopied() {
        Map<String, Object> diagnostics = new HashMap<>();
        diagnostics.put("attempts", 3);
        StepResult result = new StepResult("s", "rest.post", StepStatus.FAILED, START, END, "boom", diagnostics);

        diagnostics.put("leak", 1);

        assertThat(result.diagnostics()).containsOnlyKeys("attempts");
        assertThatThrownBy(() -> result.diagnostics().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("blank ids and null required fields are rejected")
    void invalidArguments_areRejected() {
        assertThatThrownBy(() -> StepResult.success(" ", "rest.post", START, END))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StepResult.success("s", " ", START, END))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StepResult("s", "rest.post", null, START, END, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
