package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.environment.ViewportProfile;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.ResourceScope;

class EnvironmentUiApplicationResolverTest {

    @Test
    @DisplayName("the base URL is resolved indirectly, through the environment variable the registry names")
    void baseUrlIsResolvedFromTheReference() {
        ResolvedUiApplication resolved = resolver(name -> UiTestSupport.BASE_URL_REF.equals(name) ? "http://portal.ift:8080" : null)
                .resolve(UiTestSupport.APPLICATION, UiTestSupport.context());

        assertThat(resolved.baseUrl()).isEqualTo("http://portal.ift:8080");
        assertThat(resolved.alias()).isEqualTo(UiTestSupport.APPLICATION);
        assertThat(resolved.urlFor("applications/new")).isEqualTo("http://portal.ift:8080/applications/new");
    }

    @Test
    @DisplayName("the default viewport profile comes with the application, so the scenario never carries one")
    void defaultViewportComesFromTheRegistry() {
        UiApplicationDefinition application = new UiApplicationDefinition(
                UiTestSupport.APPLICATION, UiTestSupport.BASE_URL_REF, "desktop", Map.of("desktop", new ViewportProfile(1440, 900)), null, null);

        ResolvedUiApplication resolved = resolver(name -> "http://portal.ift:8080")
                .resolve(UiTestSupport.APPLICATION, UiTestSupport.context(UiTestSupport.registry(application), new ResourceScope()));

        assertThat(resolved.viewport()).isEqualTo(new ViewportProfile(1440, 900));
    }

    @Test
    @DisplayName("an alias that is not in the registry is refused by name, with the environment named too")
    void unknownAliasIsRefused() {
        assertThatThrownBy(() -> resolver(name -> "http://portal.ift:8080").resolve("rogue-portal", UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("rogue-portal")
                .hasMessageContaining(UiTestSupport.ENVIRONMENT);
    }

    @Test
    @DisplayName("an unset environment variable is a configuration error naming the reference, not an empty URL")
    void unresolvedReferenceIsRefused() {
        assertThatThrownBy(() -> resolver(name -> null).resolve(UiTestSupport.APPLICATION, UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining(UiTestSupport.BASE_URL_REF)
                .hasMessageContaining("did not resolve");
    }

    @Test
    @DisplayName("a literal base URL (the starter's value twin) resolves verbatim, and an empty one is refused")
    void literalValuesResolveVerbatim() {
        UiApplicationDefinition literal = new UiApplicationDefinition(UiTestSupport.APPLICATION, SecretReferences.literal("http://localhost:9090"));
        UiApplicationDefinition empty = new UiApplicationDefinition(UiTestSupport.APPLICATION, SecretReferences.literal(""));

        assertThat(resolver(name -> null).resolve(UiTestSupport.APPLICATION, UiTestSupport.context(UiTestSupport.registry(literal), new ResourceScope())).baseUrl())
                .isEqualTo("http://localhost:9090");
        assertThatThrownBy(() -> resolver(name -> null).resolve(UiTestSupport.APPLICATION, UiTestSupport.context(UiTestSupport.registry(empty), new ResourceScope())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("literal base URL but it is empty");
    }

    @Test
    @DisplayName("an environment that is not whitelisted is refused before anything else")
    void unknownEnvironmentIsRefused() {
        assertThatThrownBy(() -> resolver(name -> "http://portal.ift:8080").resolve("", UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("the trace mode is carried through from the registry, so the parsed field is actually consumed (UITG-S016)")
    void traceModeComesFromTheRegistry() {
        UiApplicationDefinition application = new UiApplicationDefinition(
                UiTestSupport.APPLICATION, UiTestSupport.BASE_URL_REF, null, Map.of(), UiTraceMode.ON_FAILURE, null);

        ResolvedUiApplication resolved = resolver(name -> "http://portal.ift:8080")
                .resolve(UiTestSupport.APPLICATION, UiTestSupport.context(UiTestSupport.registry(application), new ResourceScope()));

        assertThat(resolved.trace()).as("an on-failure registry declaration must reach the resolved application").isEqualTo(UiTraceMode.ON_FAILURE);
    }

    @Test
    @DisplayName("a registry without a trace declaration resolves to the safe OFF default (UITG-S016)")
    void absentTraceDefaultsToOff() {
        ResolvedUiApplication resolved = resolver(name -> "http://portal.ift:8080")
                .resolve(UiTestSupport.APPLICATION, UiTestSupport.context());

        assertThat(resolved.trace()).as("an undeclared trace must default to OFF, never to recording").isEqualTo(UiTraceMode.OFF);
    }

    private static EnvironmentUiApplicationResolver resolver(java.util.function.UnaryOperator<String> lookup) {
        return new EnvironmentUiApplicationResolver(lookup);
    }
}
