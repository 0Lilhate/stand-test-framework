package ru.alfa.stand.test.core.event;

import java.time.Instant;
import java.util.Objects;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;

/**
 * Reporting event emitted at a scenario lifecycle phase.
 *
 * @param scenarioId the scenario id
 * @param testRunId the run id
 * @param correlationId the SDK-owned correlation id
 * @param phase the scenario phase
 * @param timestamp when the event occurred
 */
public record ScenarioEvent(
        ScenarioId scenarioId,
        TestRunId testRunId,
        CorrelationId correlationId,
        ScenarioPhase phase,
        Instant timestamp) implements ReportingEvent {

    public ScenarioEvent {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(testRunId, "testRunId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}
