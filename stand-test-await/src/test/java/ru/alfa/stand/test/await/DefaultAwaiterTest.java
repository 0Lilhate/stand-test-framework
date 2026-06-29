package ru.alfa.stand.test.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class DefaultAwaiterTest {

    private final FakeTimeSource time = new FakeTimeSource();
    private final DefaultAwaiter awaiter = new DefaultAwaiter(time);

    private static AwaitPolicy policy() {
        return AwaitPolicy.builder("wait-for-x")
                .timeout(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(200))
                .build();
    }

    @Test
    @DisplayName("a condition true on the first probe satisfies immediately without sleeping")
    void satisfiedOnFirstAttempt_noSleep() {
        AwaitResult<Boolean> result = awaiter.await(policy(), () -> true, value -> value);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.elapsed()).isEqualTo(Duration.ZERO);
        assertThat(time.sleepCalls()).isZero();
    }

    @Test
    @DisplayName("polling continues until a later probe satisfies the predicate")
    void satisfiedAfterSeveralAttempts() {
        AtomicInteger counter = new AtomicInteger();

        AwaitResult<Integer> result = awaiter.await(policy(), counter::incrementAndGet, value -> value >= 3);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.value()).isEqualTo(3);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.elapsed()).isEqualTo(Duration.ofMillis(400));
        assertThat(time.sleepCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unmet condition times out with diagnostics and the last observed value")
    void timesOut_whenNeverSatisfied() {
        AwaitResult<Boolean> result = awaiter.await(policy(), () -> false, value -> value);

        assertThat(result.satisfied()).isFalse();
        assertThat(result.attempts()).isEqualTo(6);
        assertThat(result.elapsed()).isEqualTo(Duration.ofSeconds(1));
        assertThat(result.value()).isFalse();

        TimeoutDiagnostics diagnostics = result.timeoutDiagnostics();
        assertThat(diagnostics.description()).isEqualTo("wait-for-x");
        assertThat(diagnostics.timeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(diagnostics.pollInterval()).isEqualTo(Duration.ofMillis(200));
        assertThat(diagnostics.attempts()).isEqualTo(6);
        assertThat(diagnostics.lastError()).isNull();
    }

    @Test
    @DisplayName("await returns the value that satisfied the predicate, not just a boolean")
    void awaitValue_returnsSatisfyingValue() {
        AtomicInteger counter = new AtomicInteger();

        AwaitResult<Integer> result = awaiter.await(policy(), counter::incrementAndGet, value -> value == 4);

        assertThat(result.value()).isEqualTo(4);
        assertThat(result.attempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("a throwing probe is ignored by default and its last error is captured in diagnostics")
    void ignoresProbeExceptions_capturesLastError() {
        Supplier<String> probe = () -> {
            throw new IllegalStateException("probe-failed");
        };

        AwaitResult<String> result = awaiter.await(policy(), probe, value -> true);

        assertThat(result.satisfied()).isFalse();
        assertThat(result.lastError()).isInstanceOf(IllegalStateException.class);
        assertThat(result.timeoutDiagnostics().lastError()).isEqualTo("java.lang.IllegalStateException: probe-failed");
    }

    @Test
    @DisplayName("diagnostics render a throwing probe without a message as the bare class name")
    void capturesError_withoutMessage() {
        Supplier<String> probe = () -> {
            throw new IllegalStateException();
        };

        AwaitResult<String> result = awaiter.await(policy(), probe, value -> true);

        assertThat(result.timeoutDiagnostics().lastError()).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    @DisplayName("when exceptions are not ignored, a throwing probe aborts the await")
    void propagatesProbeException_whenIgnoreDisabled() {
        AwaitPolicy strict = AwaitPolicy.builder("strict").timeout(Duration.ofSeconds(1)).ignoreExceptions(false).build();
        Supplier<String> probe = () -> {
            throw new IllegalStateException("probe-failed");
        };

        assertThatThrownBy(() -> awaiter.await(strict, probe, value -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("probe-failed");
        assertThat(time.sleepCalls()).isZero();
    }

    @Test
    @DisplayName("the poll delay is applied before the first probe")
    void pollDelay_isAppliedBeforeFirstProbe() {
        AwaitPolicy delayed = AwaitPolicy.builder("delayed")
                .timeout(Duration.ofSeconds(5))
                .pollDelay(Duration.ofMillis(500))
                .build();

        AwaitResult<Boolean> result = awaiter.await(delayed, () -> true, value -> value);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.elapsed()).isEqualTo(Duration.ofMillis(500));
        assertThat(time.totalSleptNanos()).isEqualTo(Duration.ofMillis(500).toNanos());
    }

    @Test
    @DisplayName("awaitCondition waits until the boolean supplier becomes true")
    void awaitCondition_default() {
        AtomicInteger counter = new AtomicInteger();

        AwaitResult<Boolean> result = awaiter.awaitCondition(policy(), () -> counter.incrementAndGet() >= 2);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("an interrupted wait throws StandTestException and restores the interrupt flag")
    void interruptedSleep_throwsAndRestoresFlag() {
        DefaultAwaiter interrupting = new DefaultAwaiter(new FakeTimeSource(true));
        AwaitPolicy delayed = AwaitPolicy.builder("interrupt-me")
                .timeout(Duration.ofSeconds(1))
                .pollDelay(Duration.ofMillis(10))
                .build();

        assertThatThrownBy(() -> interrupting.await(delayed, () -> false, value -> value))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("interrupt-me");
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    @DisplayName("null arguments are rejected")
    void nullArguments_areRejected() {
        assertThatThrownBy(() -> awaiter.await(null, () -> true, value -> true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> awaiter.await(policy(), null, value -> true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> awaiter.await(policy(), () -> true, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the final sleep is clamped so the await never overshoots its timeout")
    void finalSleep_isClampedToTimeout() {
        AwaitPolicy clamping = AwaitPolicy.builder("clamp")
                .timeout(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(300))
                .build();

        AwaitResult<Boolean> result = awaiter.await(clamping, () -> false, value -> value);

        assertThat(result.satisfied()).isFalse();
        assertThat(result.attempts()).isEqualTo(5);
        assertThat(result.elapsed()).isEqualTo(Duration.ofSeconds(1));
        assertThat(time.totalSleptNanos()).isEqualTo(Duration.ofSeconds(1).toNanos());
    }

    @Test
    @DisplayName("a throwing predicate is ignored by default and captured like a probe error")
    void ignoresPredicateExceptions_capturesLastError() {
        AwaitResult<String> result = awaiter.await(policy(), () -> "OBSERVED", value -> {
            throw new IllegalStateException("predicate-failed");
        });

        assertThat(result.satisfied()).isFalse();
        assertThat(result.value()).isEqualTo("OBSERVED");
        assertThat(result.lastError()).isInstanceOf(IllegalStateException.class);
        assertThat(result.timeoutDiagnostics().lastError()).isEqualTo("java.lang.IllegalStateException: predicate-failed");
    }

    @Test
    @DisplayName("when exceptions are not ignored, a throwing predicate aborts the await")
    void propagatesPredicateException_whenIgnoreDisabled() {
        AwaitPolicy strict = AwaitPolicy.builder("strict").timeout(Duration.ofSeconds(1)).ignoreExceptions(false).build();

        assertThatThrownBy(() -> awaiter.await(strict, () -> "OBSERVED", value -> {
            throw new IllegalStateException("predicate-failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("predicate-failed");
        assertThat(time.sleepCalls()).isZero();
    }

    @Test
    @DisplayName("a transient probe error that later recovers yields a satisfied result with no last error")
    void recoversAfterTransientProbeError() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> probe = () -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("transient");
            }
            return "ready";
        };

        AwaitResult<String> result = awaiter.await(policy(), probe, "ready"::equals);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.value()).isEqualTo("ready");
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.lastError()).isNull();
        assertThat(time.sleepCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a poll delay larger than the timeout still probes exactly once before timing out")
    void pollDelayExceedingTimeout_probesOnce() {
        AwaitPolicy slowStart = AwaitPolicy.builder("slow-start")
                .timeout(Duration.ofSeconds(1))
                .pollDelay(Duration.ofSeconds(2))
                .build();

        AwaitResult<Boolean> result = awaiter.await(slowStart, () -> false, value -> value);

        assertThat(result.satisfied()).isFalse();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.elapsed()).isEqualTo(Duration.ofSeconds(2));
        assertThat(result.timeoutDiagnostics()).isNotNull();
    }
}
