package ru.alfa.stand.test.allure.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.allure.lifecycle.AllureLabel;
import ru.alfa.stand.test.allure.masking.SecretMasker;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.ScenarioPhase;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.event.StepPhase;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;
import ru.alfa.stand.test.core.result.StepStatus;

class AllureMetadataMapperTest {

    private static final TestRunId TEST_RUN_ID = TestRunId.of("run-1");
    private static final CorrelationId CORRELATION_ID = CorrelationId.of("corr-1");
    private static final Instant NOW = Instant.parse("2026-06-26T10:00:00Z");

    private final AllureMetadataMapper mapper = new AllureMetadataMapper(new SecretMasker());

    @Test
    @DisplayName("scenario tags become tag labels")
    void scenarioLabels_oneTagLabelPerTag() {
        ScenarioEvent event = new ScenarioEvent(
                ScenarioId.of("flow"), TEST_RUN_ID, CORRELATION_ID, "ift", Set.of("smoke"),
                ScenarioPhase.STARTED, NOW);

        assertThat(mapper.scenarioLabels(event)).containsExactly(new AllureLabel("tag", "smoke"));
    }

    @Test
    @DisplayName("every scenario tag becomes its own tag label")
    void scenarioLabels_multipleTags() {
        ScenarioEvent event = new ScenarioEvent(
                ScenarioId.of("flow"), TEST_RUN_ID, CORRELATION_ID, "ift", Set.of("smoke", "integration", "slow"),
                ScenarioPhase.STARTED, NOW);

        assertThat(mapper.scenarioLabels(event))
                .allMatch(label -> label.name().equals("tag"))
                .extracting(AllureLabel::value)
                .containsExactlyInAnyOrder("smoke", "integration", "slow");
    }

    @Test
    @DisplayName("a scenario with no tags produces no labels")
    void scenarioLabels_noTags() {
        ScenarioEvent event = new ScenarioEvent(
                ScenarioId.of("flow"), TEST_RUN_ID, CORRELATION_ID, "ift", Set.of(), ScenarioPhase.STARTED, NOW);

        assertThat(mapper.scenarioLabels(event)).isEmpty();
    }

    @Test
    @DisplayName("scenario parameters carry the ids and environment")
    void scenarioParameters_carryIdsAndEnvironment() {
        ScenarioEvent event = new ScenarioEvent(
                ScenarioId.of("flow"), TEST_RUN_ID, CORRELATION_ID, "ift", Set.of(),
                ScenarioPhase.STARTED, NOW);

        assertThat(mapper.scenarioParameters(event)).containsExactly(
                Map.entry("scenarioId", "flow"),
                Map.entry("testRunId", "run-1"),
                Map.entry("correlationId", "corr-1"),
                Map.entry("environment", "ift"));
    }

    @Test
    @DisplayName("step parameters carry the ids plus step id and type")
    void stepParameters_carryIdsAndStep() {
        StepEvent event = new StepEvent(
                ScenarioId.of("flow"), TEST_RUN_ID, CORRELATION_ID, "s1", "rest.post",
                StepPhase.FINISHED, StepStatus.SUCCESS, NOW, null, Map.of());

        assertThat(mapper.stepParameters(event)).containsExactly(
                Map.entry("scenarioId", "flow"),
                Map.entry("testRunId", "run-1"),
                Map.entry("correlationId", "corr-1"),
                Map.entry("stepId", "s1"),
                Map.entry("stepType", "rest.post"));
    }
}
