package ru.alfa.stand.test.allure.mapping;

import ru.alfa.stand.test.allure.lifecycle.AllureStatus;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.result.StepStatus;

/**
 * Maps a core step outcome to its Allure representation.
 *
 * <p>The status mapping is the deterministic table from plan §8.3, with no "else → passed" fallback:
 * {@code SUCCESS→PASSED}, {@code FAILED→FAILED}, {@code BROKEN→BROKEN},
 * {@code TIMEOUT→FAILED} (an expired await is an unmet expectation, not an infrastructure fault) and
 * {@code SKIPPED→SKIPPED}. A null status (which only occurs on a STARTED event, never on a finished
 * step) defensively maps to {@code BROKEN} so an anomaly is visible rather than silently green.
 */
public final class AllureStepMapper {

    /**
     * Maps a core {@link StepStatus} to the Allure {@link AllureStatus}.
     *
     * @param status the core step status (may be null)
     * @return the corresponding Allure status
     */
    public AllureStatus toStatus(StepStatus status) {
        if (status == null) {
            return AllureStatus.BROKEN;
        }
        return switch (status) {
            case SUCCESS -> AllureStatus.PASSED;
            case FAILED -> AllureStatus.FAILED;
            case BROKEN -> AllureStatus.BROKEN;
            case TIMEOUT -> AllureStatus.FAILED;
            case SKIPPED -> AllureStatus.SKIPPED;
        };
    }

    /**
     * Builds the Allure step name from the step type and id (for example {@code rest.post s1}).
     *
     * <p>The core event model carries no per-step description, so the name is type plus id; a richer
     * description would require extending {@link StepEvent} (see the module README's limitations).
     *
     * @param event the step event
     * @return the step name shown in the report
     */
    public String stepName(StepEvent event) {
        return event.stepType() + " " + event.stepId();
    }
}
