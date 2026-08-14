package ru.alfa.stand.test.core.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.event.FailureAttachments;

class DiagnosticAssertionErrorTest {

    private static Map<String, Object> awaitDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("await", "rest.expectEventually orders /api/status");
        diagnostics.put("attempts", 30);
        diagnostics.put("elapsed", "PT30S");
        diagnostics.put("lastValue", "PENDING");
        return diagnostics;
    }

    @Test
    @DisplayName("it is an assertion error the runner classifies as FAILED, and it carries its diagnostics")
    void carriesDiagnosticsAsAnAssertionError() {
        DiagnosticAssertionError failure = new DiagnosticAssertionError("did not observe the expected response", awaitDiagnostics());

        assertThat(failure).isInstanceOf(StandTestAssertionError.class).isInstanceOf(AssertionError.class);
        assertThat(failure).isInstanceOf(FailureAttachments.class);
        assertThat(failure.failureDiagnostics()).containsEntry("attempts", 30).containsEntry("lastValue", "PENDING");
        assertThat(failure.failureAttachments()).isEmpty();
    }

    @Test
    @DisplayName("iteration order survives the copy — a report renders the diagnostics in the order they were assembled")
    void preservesIterationOrder() {
        DiagnosticAssertionError failure = new DiagnosticAssertionError("timed out", awaitDiagnostics());

        assertThat(failure.failureDiagnostics().keySet())
                .containsExactly("await", "attempts", "elapsed", "lastValue");
    }

    @Test
    @DisplayName("the carried map is an unmodifiable copy: a later change to the caller's map cannot rewrite the report")
    void isADefensiveUnmodifiableCopy() {
        Map<String, Object> supplied = awaitDiagnostics();
        DiagnosticAssertionError failure = new DiagnosticAssertionError("timed out", supplied);

        supplied.put("attempts", 999);

        assertThat(failure.failureDiagnostics()).containsEntry("attempts", 30);
        assertThatThrownBy(() -> failure.failureDiagnostics().put("injected", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null and empty diagnostics are the same thing, and neither throws on the failure path")
    void nullDiagnosticsAreEmpty() {
        assertThat(new DiagnosticAssertionError("timed out", (Map<String, Object>) null).failureDiagnostics()).isEmpty();
        assertThat(new DiagnosticAssertionError("timed out", Map.of()).failureDiagnostics()).isEmpty();
    }

    @Test
    @DisplayName("a null diagnostic value is carried, not thrown on: reporting must never replace the failure being reported")
    void toleratesANullValue() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("lastValue", null);

        DiagnosticAssertionError failure = new DiagnosticAssertionError("timed out", withNull);

        assertThat(failure.failureDiagnostics()).containsEntry("lastValue", null);
    }

    @Test
    @DisplayName("the causeless form leaves initCause available — AwaitResult attaches the probe's last error that way")
    void causelessFormStillAcceptsInitCause() {
        DiagnosticAssertionError failure = new DiagnosticAssertionError("timed out", awaitDiagnostics());
        RuntimeException probeError = new IllegalStateException("probe");

        assertThatCode(() -> failure.initCause(probeError)).doesNotThrowAnyException();
        assertThat(failure).hasCause(probeError);
    }

    @Test
    @DisplayName("the two-argument form keeps its cause and its diagnostics")
    void withCause() {
        RuntimeException cause = new IllegalStateException("underlying");

        DiagnosticAssertionError failure = new DiagnosticAssertionError("timed out", cause, awaitDiagnostics());

        assertThat(failure).hasCause(cause);
        assertThat(failure.failureDiagnostics()).containsEntry("attempts", 30);
    }
}
