package ru.alfa.stand.test.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Unit test of the SPI single-provider rule ({@code StandTestExtension.uniqueProvider}) — the multi-
 * provider branch cannot be produced through an in-process {@code ServiceLoader} (this module's test
 * classpath deliberately registers exactly one provider per SPI), so the helper is exercised directly.
 */
class UniqueProviderTest {

    @Test
    @DisplayName("zero providers resolve to empty, one provider is returned")
    void zeroOrOneProvider() {
        EnvironmentRegistry only = new IftEnvironmentRegistry();

        assertThat(StandTestExtension.uniqueProvider(List.of(), EnvironmentRegistry.class)).isEmpty();
        assertThat(StandTestExtension.uniqueProvider(List.of(only), EnvironmentRegistry.class)).containsSame(only);
    }

    @Test
    @DisplayName("more than one provider fails loudly, naming every provider class")
    void multipleProvidersRejected() {
        List<EnvironmentRegistry> providers = List.of(new IftEnvironmentRegistry(), new IftEnvironmentRegistry());

        assertThatThrownBy(() -> StandTestExtension.uniqueProvider(providers, EnvironmentRegistry.class))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Multiple EnvironmentRegistry providers")
                .hasMessageContaining(IftEnvironmentRegistry.class.getName())
                .hasMessageContaining("keep exactly one");
    }
}
