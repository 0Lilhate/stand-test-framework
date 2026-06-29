package ru.alfa.stand.test.core.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.identifier.ScenarioId;

class ScenarioResultTest {

    private static final Instant START = Instant.parse("2026-06-26T10:00:00Z");
    private static final Instant END = START.plusSeconds(1);

    private final ScenarioContext context = ScenarioContext.start(ScenarioId.of("flow"), "ift");

    @Test
    @DisplayName("from derives SUCCESS when all steps succeed")
    void from_allSuccess_isSuccess() {
        ScenarioResult result = ScenarioResult.from(
                context,
                List.of(StepResult.success("s1", "rest.post", START, END)),
                START,
                END);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.scenarioId()).isEqualTo(context.scenarioId());
        assertThat(result.testRunId()).isEqualTo(context.testRunId());
    }

    @Test
    @DisplayName("from derives FAILED when any step fails or times out")
    void from_anyFailure_isFailed() {
        ScenarioResult failed = ScenarioResult.from(
                context,
                List.of(
                        StepResult.success("s1", "rest.post", START, END),
                        StepResult.failed("s2", "kafka.expect", START, END, "boom")),
                START,
                END);
        ScenarioResult timedOut = ScenarioResult.from(
                context,
                List.of(StepResult.timeout("s1", "kafka.expect", START, END, "late")),
                START,
                END);

        assertThat(failed.status()).isEqualTo(StepStatus.FAILED);
        assertThat(timedOut.status()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    @DisplayName("an empty or fully-skipped run derives SUCCESS (the runner relies on the validator to reject empty scenarios)")
    void from_emptyOrSkipped_isSuccess() {
        ScenarioResult empty = ScenarioResult.from(context, List.of(), START, END);
        ScenarioResult skipped = ScenarioResult.from(
                context,
                List.of(StepResult.skipped("s1", "rest.post", START, END)),
                START,
                END);

        assertThat(empty.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(empty.stepResults()).isEmpty();
        assertThat(skipped.status()).isEqualTo(StepStatus.SUCCESS);
    }

    @Test
    @DisplayName("step results are immutable")
    void stepResults_areImmutable() {
        ScenarioResult result = ScenarioResult.from(
                context,
                List.of(StepResult.success("s1", "rest.post", START, END)),
                START,
                END);

        assertThatThrownBy(() -> result.stepResults().add(StepResult.skipped("x", "y", START, END)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
