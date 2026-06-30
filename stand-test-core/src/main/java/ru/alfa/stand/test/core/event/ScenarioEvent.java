package ru.alfa.stand.test.core.event;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;

/**
 * Reporting event emitted at a scenario lifecycle phase.
 *
 * <p>Carries the run metadata a reporting consumer needs to label the test: the
 * scenarioId/testRunId/correlationId together with the logical {@code environment} and the free-form
 * {@code tags}. These are sourced from the run's
 * {@link ru.alfa.stand.test.core.context.ScenarioContext}, which the step events do not expose, so the
 * scenario event is the single place an Allure-style consumer reads environment and tags. The
 * {@code tags} set is defensively copied and exposed as immutable.
 *
 * @param scenarioId the scenario id
 * @param testRunId the run id
 * @param correlationId the SDK-owned correlation id
 * @param environment the logical environment name (never blank)
 * @param tags an immutable set of free-form labels
 * @param phase the scenario phase
 * @param timestamp when the event occurred
 */
public record ScenarioEvent(
        ScenarioId scenarioId,
        TestRunId testRunId,
        CorrelationId correlationId,
        String environment,
        Set<String> tags,
        ScenarioPhase phase,
        Instant timestamp) implements ReportingEvent {

    public ScenarioEvent {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(testRunId, "testRunId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("environment must not be blank");
        }
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        tags = (tags == null) ? Set.of() : Set.copyOf(tags);
    }
}
