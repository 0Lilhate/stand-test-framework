package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.execution.StepExecutor;

class RestStepExecutorRegistrationTest {

    @Test
    @DisplayName("RestStepExecutor is discoverable via ServiceLoader")
    void discoverableViaServiceLoader() {
        boolean found = ServiceLoader.load(StepExecutor.class)
                .stream()
                .anyMatch(provider -> provider.type().equals(RestStepExecutor.class));
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("the no-arg constructor produces a usable executor")
    void noArgConstructorIsUsable() {
        assertThat(new RestStepExecutor().supports("rest.get")).isTrue();
    }
}
