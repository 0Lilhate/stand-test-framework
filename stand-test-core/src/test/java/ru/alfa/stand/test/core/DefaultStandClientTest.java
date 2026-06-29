package ru.alfa.stand.test.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.execution.ScenarioRunner;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;

class DefaultStandClientTest {

    @Test
    @DisplayName("run delegates to the runner and returns its result")
    void run_delegatesToRunner() {
        Scenario scenario = Scenario.builder("flow").environment("ift").step(GenericStep.of("s1", "fake.ok")).build();
        ScenarioResult canned = new ScenarioResult(
                ScenarioId.of("flow"), TestRunId.of("run-1"), StepStatus.SUCCESS, List.of(), Instant.now(), Instant.now());
        AtomicReference<Scenario> received = new AtomicReference<>();
        ScenarioRunner runner = passed -> {
            received.set(passed);
            return canned;
        };

        ScenarioResult result = new DefaultStandClient(runner).run(scenario);

        assertThat(result).isSameAs(canned);
        assertThat(received.get()).isSameAs(scenario);
    }

    @Test
    @DisplayName("a null runner is rejected")
    void constructor_nullRunner_throws() {
        assertThatThrownBy(() -> new DefaultStandClient(null)).isInstanceOf(NullPointerException.class);
    }
}
