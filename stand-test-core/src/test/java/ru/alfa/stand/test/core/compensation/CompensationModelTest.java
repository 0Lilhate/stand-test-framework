package ru.alfa.stand.test.core.compensation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("compensation model (UndoLog / CleanupPolicy / outcomes)")
class CompensationModelTest {

    private static Compensator fixed(String id, CompensationOutcome outcome) {
        return new Compensator() {
            @Override
            public String actionId() {
                return id;
            }

            @Override
            public String target() {
                return outcome.target();
            }

            @Override
            public CompensationOutcome compensate() {
                return outcome;
            }
        };
    }

    @Test
    @DisplayName("UndoLog drains in reverse registration order")
    void undoLog_reverseOrder() {
        UndoLog log = new UndoLog();
        Compensator a = fixed("a", CompensationOutcome.applied("a", "ds", 1, Map.of()));
        Compensator b = fixed("b", CompensationOutcome.applied("b", "ds", 1, Map.of()));
        Compensator c = fixed("c", CompensationOutcome.applied("c", "ds", 1, Map.of()));
        log.register(a);
        log.register(b);
        log.register(c);

        assertThat(log.size()).isEqualTo(3);
        assertThat(log.isEmpty()).isFalse();
        List<String> order = new ArrayList<>();
        log.inReverseOrder().forEach(compensator -> order.add(compensator.actionId()));
        assertThat(order).containsExactly("c", "b", "a");
    }

    @Test
    @DisplayName("UndoLog rejects a null compensator")
    void undoLog_rejectsNull() {
        UndoLog log = new UndoLog();
        assertThatThrownBy(() -> log.register(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("CleanupPolicy gates the drain by outcome")
    void cleanupPolicy_gating() {
        assertThat(CleanupPolicy.ON_FAILURE.shouldCompensate(true)).isTrue();
        assertThat(CleanupPolicy.ON_FAILURE.shouldCompensate(false)).isFalse();
        assertThat(CleanupPolicy.ALWAYS.shouldCompensate(true)).isTrue();
        assertThat(CleanupPolicy.ALWAYS.shouldCompensate(false)).isTrue();
        assertThat(CleanupPolicy.NEVER.shouldCompensate(true)).isFalse();
        assertThat(CleanupPolicy.NEVER.shouldCompensate(false)).isFalse();
    }

    @Test
    @DisplayName("CompensationStatus.isFailure covers CONFLICT and FAILED only")
    void status_isFailure() {
        assertThat(CompensationStatus.APPLIED.isFailure()).isFalse();
        assertThat(CompensationStatus.SKIPPED.isFailure()).isFalse();
        assertThat(CompensationStatus.CONFLICT.isFailure()).isTrue();
        assertThat(CompensationStatus.FAILED.isFailure()).isTrue();
    }

    @Test
    @DisplayName("CompensationReport aggregates failures")
    void report_failures() {
        CompensationOutcome ok = CompensationOutcome.applied("a", "ds", 1, Map.of());
        CompensationOutcome conflict = CompensationOutcome.conflict("b", "ds", "diverged", Map.of());
        CompensationOutcome failed = CompensationOutcome.failed("c", "ds", "boom", new IllegalStateException("boom"), Map.of());

        CompensationReport report = new CompensationReport(List.of(ok, conflict, failed));

        assertThat(report.hasFailures()).isTrue();
        assertThat(report.failures()).containsExactly(conflict, failed);
        assertThat(CompensationReport.empty().hasFailures()).isFalse();
        assertThat(CompensationReport.empty().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("CompensationOutcome copies diagnostics defensively")
    void outcome_defensiveDiagnostics() {
        Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("k", "v");
        CompensationOutcome outcome = CompensationOutcome.applied("a", "ds", 3, mutable);
        mutable.put("k2", "v2");

        assertThat(outcome.diagnostics()).containsExactlyEntriesOf(Map.of("k", "v"));
        assertThat(outcome.affectedRows()).isEqualTo(3);
        assertThat(outcome.isFailure()).isFalse();
    }
}
