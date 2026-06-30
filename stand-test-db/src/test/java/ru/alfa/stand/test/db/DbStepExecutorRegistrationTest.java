package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.execution.StepExecutor;

class DbStepExecutorRegistrationTest {

    @Test
    @DisplayName("DbStepExecutor is discoverable via ServiceLoader")
    void discoverableViaServiceLoader() {
        boolean found = ServiceLoader.load(StepExecutor.class)
                .stream()
                .anyMatch(provider -> provider.type().equals(DbStepExecutor.class));
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("the no-arg constructor produces an executor that supports db.* types")
    void noArgConstructorSupportsDbTypes() {
        DbStepExecutor executor = new DbStepExecutor();
        assertThat(executor.supports("db.query")).isTrue();
        assertThat(executor.supports("db.expectEventually")).isTrue();
        assertThat(executor.supports("db.seed")).isTrue();
        assertThat(executor.supports("db.cleanup")).isTrue();
        assertThat(executor.supports("rest.get")).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }
}
