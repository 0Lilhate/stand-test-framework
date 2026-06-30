package ru.alfa.stand.test.allure.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.allure.lifecycle.AllureStatus;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.event.StepPhase;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;
import ru.alfa.stand.test.core.result.StepStatus;

class AllureStepMapperTest {

    private final AllureStepMapper mapper = new AllureStepMapper();

    @Test
    @DisplayName("the status table maps each core status to its Allure status with no green fallback")
    void toStatus_mapsDeterministically() {
        assertThat(mapper.toStatus(StepStatus.SUCCESS)).isEqualTo(AllureStatus.PASSED);
        assertThat(mapper.toStatus(StepStatus.FAILED)).isEqualTo(AllureStatus.FAILED);
        assertThat(mapper.toStatus(StepStatus.BROKEN)).isEqualTo(AllureStatus.BROKEN);
        assertThat(mapper.toStatus(StepStatus.TIMEOUT)).isEqualTo(AllureStatus.FAILED);
        assertThat(mapper.toStatus(StepStatus.SKIPPED)).isEqualTo(AllureStatus.SKIPPED);
    }

    @Test
    @DisplayName("a null status defensively maps to BROKEN rather than passing")
    void toStatus_nullMapsToBroken() {
        assertThat(mapper.toStatus(null)).isEqualTo(AllureStatus.BROKEN);
    }

    @Test
    @DisplayName("the step name combines step type and id")
    void stepName_combinesTypeAndId() {
        StepEvent event = new StepEvent(
                ScenarioId.of("flow"), TestRunId.generate(), CorrelationId.generate(),
                "s1", "rest.post", StepPhase.FINISHED, StepStatus.SUCCESS, Instant.now(), null, Map.of());

        assertThat(mapper.stepName(event)).isEqualTo("rest.post s1");
    }
}
