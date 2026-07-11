package ru.alfa.stand.test.core.compensation;

import java.util.List;
import java.util.Objects;

/**
 * Immutable aggregate of the {@link CompensationOutcome}s produced by draining an {@link UndoLog}.
 *
 * @param outcomes the per-action outcomes, in the order they were applied (reverse registration order)
 */
public record CompensationReport(List<CompensationOutcome> outcomes) {

    public CompensationReport {
        outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes must not be null"));
    }

    /**
     * @return an empty report (nothing was compensated)
     */
    public static CompensationReport empty() {
        return new CompensationReport(List.of());
    }

    /**
     * @return {@code true} if any outcome is a {@link CompensationOutcome#isFailure() failure}
     *     (CONFLICT or FAILED)
     */
    public boolean hasFailures() {
        return outcomes.stream().anyMatch(CompensationOutcome::isFailure);
    }

    /**
     * @return the failing outcomes (CONFLICT or FAILED)
     */
    public List<CompensationOutcome> failures() {
        return outcomes.stream().filter(CompensationOutcome::isFailure).toList();
    }

    /**
     * @return {@code true} if nothing was compensated
     */
    public boolean isEmpty() {
        return outcomes.isEmpty();
    }
}
