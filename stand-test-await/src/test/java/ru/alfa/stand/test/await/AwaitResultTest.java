package ru.alfa.stand.test.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;

class AwaitResultTest {

    private static TimeoutDiagnostics diagnostics() {
        return new TimeoutDiagnostics(
                "wait", Duration.ofSeconds(1), Duration.ofMillis(100), 3, Duration.ofSeconds(1), "PENDING", null, Map.of());
    }

    @Test
    @DisplayName("a satisfied result returns its value and never throws")
    void satisfied_returnsValue() {
        AwaitResult<String> result = AwaitResult.satisfied("ok", 1, Duration.ZERO);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.value()).isEqualTo("ok");
        assertThat(result.timeoutDiagnostics()).isNull();
        assertThat(result.orElseThrow()).isEqualTo("ok");
        assertThat(result.orElseThrow(diag -> new StandTestException(diag.summary()))).isEqualTo("ok");
    }

    @Test
    @DisplayName("a timed-out result exposes the last observed value and its diagnostics")
    void timedOut_exposesLastValue() {
        AwaitResult<String> result = AwaitResult.timedOut("PENDING", 3, Duration.ofSeconds(1), null, diagnostics());

        assertThat(result.satisfied()).isFalse();
        assertThat(result.value()).isEqualTo("PENDING");
        assertThat(result.timeoutDiagnostics().attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("orElseThrow with a mapper throws the mapped exception and attaches the last error as cause")
    void timedOut_orElseThrowMapped() {
        RuntimeException probeError = new IllegalStateException("boom");
        AwaitResult<String> result = AwaitResult.timedOut("PENDING", 3, Duration.ofSeconds(1), probeError, diagnostics());

        assertThatThrownBy(() -> result.orElseThrow(diag -> new StandTestAssertionError(diag.summary())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("wait")
                .hasCause(probeError);
    }

    @Test
    @DisplayName("the no-arg orElseThrow raises a StandTestAssertionError (unmet expectation) carrying the summary")
    void timedOut_orElseThrowDefault() {
        AwaitResult<String> result = AwaitResult.timedOut("PENDING", 3, Duration.ofSeconds(1), null, diagnostics());

        assertThatThrownBy(result::orElseThrow)
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("not satisfied within");
    }

    @Test
    @DisplayName("orElseThrow keeps the mapper exception's own cause and does not overwrite it")
    void orElseThrow_keepsExistingCause() {
        RuntimeException probeError = new IllegalStateException("probe");
        RuntimeException preexisting = new RuntimeException("preexisting");
        AwaitResult<String> result = AwaitResult.timedOut("PENDING", 3, Duration.ofSeconds(1), probeError, diagnostics());

        assertThatThrownBy(() -> result.orElseThrow(diag -> new StandTestException(diag.summary(), preexisting)))
                .isInstanceOf(StandTestException.class)
                .hasCause(preexisting);
    }

    @Test
    @DisplayName("a mapper that built its exception with an explicit null cause still raises that exception, not the JDK's refusal to re-initialise it")
    void orElseThrow_mapperPassedAnExplicitNullCause() {
        RuntimeException probeError = new IllegalStateException("probe");
        AwaitResult<String> result = AwaitResult.timedOut("PENDING", 3, Duration.ofSeconds(1), probeError, diagnostics());

        assertThatThrownBy(() -> result.orElseThrow(diag -> new StandTestException(diag.summary(), null)))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not satisfied within")
                .hasNoCause();
    }

    @Test
    @DisplayName("a satisfied result may not carry diagnostics or a last error")
    void satisfiedInvariant_isEnforced() {
        TimeoutDiagnostics diagnostics = diagnostics();
        assertThatThrownBy(() -> new AwaitResult<String>(true, "v", 1, Duration.ZERO, null, diagnostics))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AwaitResult<String>(true, "v", 1, Duration.ZERO, new RuntimeException("x"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("invalid construction is rejected")
    void invalidArguments_areRejected() {
        assertThatThrownBy(() -> AwaitResult.satisfied("v", 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AwaitResult.satisfied("v", 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AwaitResult.timedOut("v", 1, Duration.ZERO, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
