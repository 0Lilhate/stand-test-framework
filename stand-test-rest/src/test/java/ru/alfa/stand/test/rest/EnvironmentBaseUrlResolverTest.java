package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class EnvironmentBaseUrlResolverTest {

    @Test
    @DisplayName("a reference is resolved indirectly to its value")
    void referenceResolvesToValue() {
        UnaryOperator<String> lookup = Map.of("CLIENT_SERVICE_URL", "http://stand.local:8080")::get;
        BaseUrlResolver resolver = new EnvironmentBaseUrlResolver(lookup);
        assertThat(resolver.resolve("CLIENT_SERVICE_URL")).isEqualTo("http://stand.local:8080");
    }

    @Test
    @DisplayName("the ${NAME:default} placeholder spelling resolves the variable and falls back to the inline default")
    void placeholderWithDefaultResolves() {
        UnaryOperator<String> lookup = Map.of("CLIENT_SERVICE_URL", "http://stand.local:8080")::get;
        BaseUrlResolver resolver = new EnvironmentBaseUrlResolver(lookup);

        assertThat(resolver.resolve("${CLIENT_SERVICE_URL}")).isEqualTo("http://stand.local:8080");
        assertThat(resolver.resolve("${CLIENT_SERVICE_URL:http://fallback:1}")).isEqualTo("http://stand.local:8080");
        assertThat(resolver.resolve("${MISSING_URL:http://127.0.0.1:18080}")).isEqualTo("http://127.0.0.1:18080");
        assertThatThrownBy(() -> resolver.resolve("${MISSING_URL}"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("did not resolve");
    }

    @Test
    @DisplayName("a blank reference is rejected")
    void blankReferenceRejected() {
        assertThatThrownBy(() -> new EnvironmentBaseUrlResolver().resolve(" ")).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("an unset or blank reference value fails")
    void unsetReferenceFails() {
        assertThatThrownBy(() -> new EnvironmentBaseUrlResolver(ref -> null).resolve("MISSING"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("did not resolve");
        assertThatThrownBy(() -> new EnvironmentBaseUrlResolver(ref -> "  ").resolve("BLANK_VALUE"))
                .isInstanceOf(StandTestException.class);
    }
}
