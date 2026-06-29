package ru.alfa.stand.test.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AwaitPolicyTest {

    @Test
    @DisplayName("of applies the default poll interval, no delay and exception-ignoring polling")
    void of_appliesDefaults() {
        AwaitPolicy policy = AwaitPolicy.of("wait-for-x", Duration.ofSeconds(5));

        assertThat(policy.description()).isEqualTo("wait-for-x");
        assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.pollInterval()).isEqualTo(AwaitPolicy.DEFAULT_POLL_INTERVAL);
        assertThat(policy.pollDelay()).isEqualTo(Duration.ZERO);
        assertThat(policy.ignoreExceptions()).isTrue();
    }

    @Test
    @DisplayName("ofSeconds sets the timeout from a seconds value")
    void ofSeconds_setsTimeout() {
        assertThat(AwaitPolicy.ofSeconds("wait", 30).timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("builder overrides every field")
    void builder_overridesAll() {
        AwaitPolicy policy = AwaitPolicy.builder("custom")
                .timeoutSeconds(10)
                .pollInterval(Duration.ofMillis(50))
                .pollDelay(Duration.ofMillis(500))
                .ignoreExceptions(false)
                .build();

        assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.pollInterval()).isEqualTo(Duration.ofMillis(50));
        assertThat(policy.pollDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.ignoreExceptions()).isFalse();
    }

    @Test
    @DisplayName("blank description, null and non-positive durations are rejected")
    void invalidArguments_areRejected() {
        assertThatThrownBy(() -> AwaitPolicy.of(" ", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AwaitPolicy.of("d", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AwaitPolicy.of("d", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AwaitPolicy.of("d", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AwaitPolicy.builder("d").timeout(Duration.ofSeconds(1)).pollInterval(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AwaitPolicy.builder("d").timeout(Duration.ofSeconds(1)).pollDelay(Duration.ofMillis(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AwaitPolicy.builder("d").timeout(Duration.ofSeconds(1)).pollDelay(null).build())
                .isInstanceOf(NullPointerException.class);
    }
}
