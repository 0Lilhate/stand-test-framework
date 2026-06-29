package ru.alfa.stand.test.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SystemTimeSourceTest {

    private static AwaitPolicy policy() {
        return AwaitPolicy.ofSeconds("system", 1);
    }

    @Test
    @DisplayName("system() returns the shared singleton")
    void system_isSingleton() {
        assertThat(TimeSource.system()).isSameAs(TimeSource.system());
    }

    @Test
    @DisplayName("nanoTime is monotonic non-decreasing")
    void nanoTime_isNonDecreasing() {
        TimeSource source = TimeSource.system();

        long first = source.nanoTime();
        long second = source.nanoTime();

        assertThat(second - first).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("non-positive sleep returns immediately and a small positive sleep does not throw")
    void sleep_handlesNonPositiveAndPositive() {
        TimeSource source = TimeSource.system();

        assertThatCode(() -> {
            source.sleep(0);
            source.sleep(-1);
            source.sleep(Duration.ofMillis(1).toNanos());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the system-wired awaiter satisfies a condition that is already true")
    void systemAwaiter_satisfiesImmediately() {
        AwaitResult<Boolean> created = Awaiter.create().await(policy(), () -> true, value -> value);
        AwaitResult<Boolean> constructed = new DefaultAwaiter().await(policy(), () -> true, value -> value);

        assertThat(created.satisfied()).isTrue();
        assertThat(created.attempts()).isEqualTo(1);
        assertThat(constructed.satisfied()).isTrue();
    }
}
