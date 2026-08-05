package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.execution.StepExecutor;

/**
 * The module joins a scenario run through the core SPI alone — no module depends on
 * {@code stand-test-ui}, and the JUnit extension needs no knowledge of it.
 */
class UiStepExecutorRegistrationTest {

    @Test
    @DisplayName("the UI executor is discoverable through the core StepExecutor service loader")
    void executorIsRegisteredAsAServiceProvider() {
        assertThat(ServiceLoader.load(StepExecutor.class).stream().map(ServiceLoader.Provider::get))
                .anyMatch(executor -> executor instanceof UiStepExecutor && executor.supports("ui.open"));
    }
}
