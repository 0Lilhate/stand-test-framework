package ru.alfa.stand.test.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class SecretReferencesTest {

    @Test
    @DisplayName("conventional and unconventional reference NAMES pass")
    void referenceNamesPass() {
        assertThat(SecretReferences.requireReferenceShape("MAIN_DB_URL", "url-ref", "env.ift")).isEqualTo("MAIN_DB_URL");
        assertThat(SecretReferences.requireReferenceShape("client.service.base-url", "base-url-ref", "env.ift")).isEqualTo("client.service.base-url");
        assertThat(SecretReferences.requireReferenceShape("kafkaBootstrap", "bootstrap-servers-ref", "env.ift")).isEqualTo("kafkaBootstrap");
    }

    @Test
    @DisplayName("placeholder spellings ${NAME} and ${NAME:default} pass the shape guard verbatim")
    void placeholderShapesPass() {
        assertThat(SecretReferences.requireReferenceShape("${MAIN_DB_URL}", "url-ref", "env.ift")).isEqualTo("${MAIN_DB_URL}");
        assertThat(SecretReferences.requireReferenceShape("${MAIN_DB_URL:jdbc:h2:mem:example}", "url-ref", "env.ift")).isEqualTo("${MAIN_DB_URL:jdbc:h2:mem:example}");
        assertThat(SecretReferences.requireReferenceShape("${CLIENT_SERVICE_URL:http://127.0.0.1:18080}", "base-url-ref", "env.ift")).isEqualTo("${CLIENT_SERVICE_URL:http://127.0.0.1:18080}");
    }

    @Test
    @DisplayName("a malformed placeholder is rejected with a syntax hint")
    void malformedPlaceholderRejected() {
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("${ MAIN DB }", "url-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("malformed placeholder");
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("${}", "url-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("malformed placeholder");
    }

    @Test
    @DisplayName("resolve honours bare names, ${NAME} and ${NAME:default} — the default applies only when the variable is missing")
    void resolvePlaceholders() {
        java.util.Map<String, String> env = java.util.Map.of("SET_VAR", "from-env", "EMPTY_VAR", "");

        assertThat(SecretReferences.resolve("SET_VAR", env::get)).isEqualTo("from-env");
        assertThat(SecretReferences.resolve("${SET_VAR}", env::get)).isEqualTo("from-env");
        assertThat(SecretReferences.resolve("${SET_VAR:fallback}", env::get)).isEqualTo("from-env");
        assertThat(SecretReferences.resolve("${MISSING_VAR:fallback}", env::get)).isEqualTo("fallback");
        assertThat(SecretReferences.resolve("${MISSING_VAR:jdbc:h2:mem:x}", env::get)).isEqualTo("jdbc:h2:mem:x");
        assertThat(SecretReferences.resolve("${MISSING_VAR}", env::get)).isNull();
        assertThat(SecretReferences.resolve("MISSING_VAR", env::get)).isNull();
        // A variable SET to an empty value wins over the default (Spring semantics) — an intentionally
        // empty password must not be silently replaced.
        assertThat(SecretReferences.resolve("${EMPTY_VAR:fallback}", env::get)).isEmpty();
    }

    @Test
    @DisplayName("literal-wrapped values resolve verbatim without any environment lookup")
    void literalResolvesVerbatim() {
        java.util.function.UnaryOperator<String> failingLookup = name -> {
            throw new AssertionError("lookup must not be called for a literal, but was called with '" + name + "'");
        };

        assertThat(SecretReferences.resolve(SecretReferences.literal("https://x:8080/a}b:c{"), failingLookup)).isEqualTo("https://x:8080/a}b:c{");
        assertThat(SecretReferences.resolve(SecretReferences.literal(""), failingLookup)).isEmpty();
        // A value that itself looks like a placeholder is NOT re-resolved.
        assertThat(SecretReferences.resolve(SecretReferences.literal("${FOO}"), failingLookup)).isEqualTo("${FOO}");
    }

    @Test
    @DisplayName("isLiteral recognises wrapped values and nothing else")
    void isLiteralRecognisesWrappedValues() {
        assertThat(SecretReferences.isLiteral(SecretReferences.literal("https://x"))).isTrue();
        assertThat(SecretReferences.isLiteral(SecretReferences.literal(""))).isTrue();
        assertThat(SecretReferences.isLiteral(null)).isFalse();
        assertThat(SecretReferences.isLiteral("MAIN_DB_URL")).isFalse();
        assertThat(SecretReferences.isLiteral("${MAIN_DB_URL}")).isFalse();
    }

    @Test
    @DisplayName("the SDK-internal literal marker is rejected in configuration fail-closed")
    void literalMarkerRejectedInConfiguration() {
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("literal://https://real-stand.example", "base-url-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("literal marker");
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("  literal://x  ", "url-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("literal marker");
    }

    @Test
    @DisplayName("values that are obviously resolved endpoints or inline secrets are rejected fail-closed")
    void valueShapedInputRejected() {
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("jdbc:postgresql://db:5432/app", "url-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("https://real-stand.example", "base-url-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("Bearer sk-abc123def456", "password-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("basic dXNlcjpwYXNz", "password-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("some secret value", "sasl-jaas-config-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> SecretReferences.requireReferenceShape("  ", "url-ref", "env.ift"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("non-blank");
    }
}
