package ru.alfa.stand.test.junit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class NoProviderEnvironmentRegistryTest {

    @Test
    @DisplayName("any lookup fails with a distinct, actionable no-provider diagnostic instead of a generic 'not whitelisted'")
    void lookupRaisesNoProviderDiagnostic() {
        NoProviderEnvironmentRegistry registry = new NoProviderEnvironmentRegistry();

        assertThatThrownBy(() -> registry.environment("ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("No EnvironmentRegistry provider found on the test classpath")
                .hasMessageContaining("ift")
                .hasMessageContaining("META-INF/services/ru.alfa.stand.test.core.environment.EnvironmentRegistry")
                .hasMessageContaining("stand-test-config");
    }

    @Test
    @DisplayName("a null environment name is rejected as a programming error, not a config diagnostic")
    void nullNameRejected() {
        NoProviderEnvironmentRegistry registry = new NoProviderEnvironmentRegistry();

        assertThatThrownBy(() -> registry.environment(null)).isInstanceOf(NullPointerException.class);
    }
}
