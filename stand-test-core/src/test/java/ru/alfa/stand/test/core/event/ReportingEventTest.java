package ru.alfa.stand.test.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;
import ru.alfa.stand.test.core.result.StepStatus;

class ReportingEventTest {

    private static final ScenarioId SCENARIO_ID = ScenarioId.of("flow");
    private static final TestRunId TEST_RUN_ID = TestRunId.generate();
    private static final CorrelationId CORRELATION_ID = CorrelationId.generate();
    private static final Instant NOW = Instant.parse("2026-06-26T10:00:00Z");

    @Test
    @DisplayName("the no-op publisher accepts both event types without failing")
    void noOpPublisher_acceptsEvents() {
        ReportingEventPublisher publisher = NoOpReportingEventPublisher.INSTANCE;
        ScenarioEvent scenarioEvent = new ScenarioEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, ScenarioPhase.STARTED, NOW);
        StepEvent stepEvent = startedStep();

        assertThat(publisher).isNotNull();
        assertThatCode(() -> {
            publisher.publish(scenarioEvent);
            publisher.publish(stepEvent);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("scenario and step events are both ReportingEvent instances")
    void events_shareReportingEventMarker() {
        ReportingEvent scenarioEvent = new ScenarioEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, ScenarioPhase.FINISHED, NOW);
        ReportingEvent stepEvent = startedStep();

        assertThat(scenarioEvent).isInstanceOf(ScenarioEvent.class);
        assertThat(stepEvent).isInstanceOf(StepEvent.class);
    }

    @Test
    @DisplayName("a step event status is null while started and set when finished")
    void stepEvent_statusNullability() {
        StepEvent started = startedStep();
        StepEvent finished = new StepEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, "s1", "rest.post", StepPhase.FINISHED,
                StepStatus.SUCCESS, NOW, "ok", Map.of());

        assertThat(started.status()).isNull();
        assertThat(finished.status()).isEqualTo(StepStatus.SUCCESS);
    }

    @Test
    @DisplayName("step event diagnostics are defensively copied and immutable")
    void stepEvent_diagnosticsAreImmutable() {
        Map<String, Object> diagnostics = new HashMap<>();
        diagnostics.put("attempts", 3);
        StepEvent event = new StepEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, "s1", "kafka.expect", StepPhase.FINISHED,
                StepStatus.TIMEOUT, NOW, "late", diagnostics);

        diagnostics.put("leak", 1);

        assertThat(event.diagnostics()).containsOnlyKeys("attempts");
        assertThatThrownBy(() -> event.diagnostics().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a step event rejects a blank step id and a null required field")
    void stepEvent_validation() {
        assertThatThrownBy(() -> new StepEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, " ", "rest.post", StepPhase.STARTED,
                null, NOW, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioEvent(SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    private StepEvent startedStep() {
        return new StepEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, "s1", "rest.post", StepPhase.STARTED,
                null, NOW, null, Map.of());
    }
}
