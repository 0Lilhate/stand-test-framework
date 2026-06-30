package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Contract tests for the production secret resolver {@link EnvironmentReferenceResolver} (plan §9: the
 * registry stores references, not values; URLs/credentials are resolved indirectly via the process
 * environment). The executor tests inject a passthrough resolver, so this is the only place the real
 * resolve logic — unset = config error, empty = allowed — is pinned down.
 */
class EnvironmentReferenceResolverTest {

    @Test
    @DisplayName("a reference is resolved to its looked-up value")
    void resolvesAReferenceToItsValue() {
        EnvironmentReferenceResolver resolver = new EnvironmentReferenceResolver(reference -> "jdbc:postgresql://stand/db");

        assertThat(resolver.resolve("MAIN_DB_URL")).isEqualTo("jdbc:postgresql://stand/db");
    }

    @Test
    @DisplayName("an empty resolved value is allowed (some stands use an empty password)")
    void anEmptyValueIsAllowed() {
        EnvironmentReferenceResolver resolver = new EnvironmentReferenceResolver(reference -> "");

        assertThat(resolver.resolve("MAIN_DB_PASSWORD")).isEmpty();
    }

    @Test
    @DisplayName("an unset reference (lookup returns null) is an infrastructure/config error")
    void anUnsetReferenceIsAConfigurationError() {
        EnvironmentReferenceResolver resolver = new EnvironmentReferenceResolver(reference -> null);

        assertThatThrownBy(() -> resolver.resolve("MISSING"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("did not resolve");
    }

    @Test
    @DisplayName("a null or blank reference is rejected before any lookup")
    void blankReferenceIsRejected() {
        EnvironmentReferenceResolver resolver = new EnvironmentReferenceResolver(reference -> "value");

        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> resolver.resolve(""))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> resolver.resolve("   "))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("a null lookup is rejected at construction")
    void nullLookupIsRejected() {
        assertThatThrownBy(() -> new EnvironmentReferenceResolver((UnaryOperator<String>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lookup must not be null");
    }

    @Test
    @DisplayName("the default constructor reads the process environment, so an unset variable is a config error")
    void theDefaultConstructorReadsTheProcessEnvironment() {
        EnvironmentReferenceResolver resolver = new EnvironmentReferenceResolver();

        assertThatThrownBy(() -> resolver.resolve("STAND_TEST_DB_R3_DEFINITELY_UNSET"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("did not resolve");
    }
}
