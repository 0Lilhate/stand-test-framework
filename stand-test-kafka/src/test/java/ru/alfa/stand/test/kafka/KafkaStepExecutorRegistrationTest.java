package ru.alfa.stand.test.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.execution.StepExecutor;

class KafkaStepExecutorRegistrationTest {

    @Test
    @DisplayName("KafkaStepExecutor is discoverable via ServiceLoader")
    void discoverableViaServiceLoader() {
        boolean found = ServiceLoader.load(StepExecutor.class)
                .stream()
                .anyMatch(provider -> provider.type().equals(KafkaStepExecutor.class));
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("the no-arg constructor produces an executor that supports kafka.* types")
    void noArgConstructorSupportsKafkaTypes() {
        KafkaStepExecutor executor = new KafkaStepExecutor();
        assertThat(executor.supports("kafka.send")).isTrue();
        assertThat(executor.supports("kafka.expect")).isTrue();
        assertThat(executor.supports("rest.get")).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }
}
