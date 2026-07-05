package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.exception.StandTestException;

class EnvironmentAuthHeaderResolverTest {

    private static EnvironmentAuthHeaderResolver resolver(Map<String, String> values) {
        return new EnvironmentAuthHeaderResolver(values::get);
    }

    @Test
    @DisplayName("basic auth encodes username:password as base64 per the RFC 7617 example")
    void basic_encodesRfcExample() {
        EnvironmentAuthHeaderResolver resolver = resolver(Map.of("U_REF", "Aladdin", "P_REF", "open sesame"));

        String value = resolver.resolve(AuthConfig.basic("U_REF", "P_REF"));

        assertThat(value).isEqualTo("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==");
    }

    @Test
    @DisplayName("basic auth encodes non-ASCII credentials as UTF-8 bytes")
    void basic_encodesUtf8() {
        EnvironmentAuthHeaderResolver resolver = resolver(Map.of("U_REF", "user", "P_REF", "пароль"));

        String value = resolver.resolve(AuthConfig.basic("U_REF", "P_REF"));

        assertThat(value).isEqualTo("Basic dXNlcjrQv9Cw0YDQvtC70Yw=");
    }

    @Test
    @DisplayName("bearer auth prefixes the resolved token, trimming surrounding whitespace")
    void bearer_prefixesTrimmedToken() {
        EnvironmentAuthHeaderResolver resolver = resolver(Map.of("T_REF", "sk-abc123\n"));

        assertThat(resolver.resolve(AuthConfig.bearer("T_REF"))).isEqualTo("Bearer sk-abc123");
    }

    @Test
    @DisplayName("an unresolved reference fails with the reference name and scheme, never a value")
    void unresolvedReference_failsWithName() {
        EnvironmentAuthHeaderResolver resolver = resolver(Map.of("U_REF", "user"));

        assertThatThrownBy(() -> resolver.resolve(AuthConfig.basic("U_REF", "P_REF")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("P_REF")
                .hasMessageContaining("BASIC")
                .hasMessageContaining("did not resolve");
        assertThatThrownBy(() -> resolver.resolve(AuthConfig.bearer("T_REF")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("T_REF")
                .hasMessageContaining("BEARER");
    }

    @Test
    @DisplayName("a resolved username containing ':' is rejected (RFC 7617)")
    void basic_colonInUsername_isRejected() {
        EnvironmentAuthHeaderResolver resolver = resolver(Map.of("U_REF", "user:x", "P_REF", "p"));

        assertThatThrownBy(() -> resolver.resolve(AuthConfig.basic("U_REF", "P_REF")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("U_REF")
                .hasMessageContaining(":");
    }

    @Test
    @DisplayName("a bearer token with embedded whitespace or control characters is rejected (header injection)")
    void bearer_embeddedWhitespaceOrControl_isRejected() {
        assertThatThrownBy(() -> resolver(Map.of("T_REF", "abc def")).resolve(AuthConfig.bearer("T_REF")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("T_REF");
        assertThatThrownBy(() -> resolver(Map.of("T_REF", "abc\r\ndef")).resolve(AuthConfig.bearer("T_REF")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("T_REF");
    }

    @Test
    @DisplayName("basic credentials containing control characters are rejected")
    void basic_controlCharacters_areRejected() {
        EnvironmentAuthHeaderResolver resolver = resolver(Map.of("U_REF", "user", "P_REF", "pa\nss"));

        assertThatThrownBy(() -> resolver.resolve(AuthConfig.basic("U_REF", "P_REF")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("P_REF")
                .hasMessageNotContaining("pa\nss");
    }

    @Test
    @DisplayName("the ${NAME:default} placeholder spelling resolves with its default")
    void placeholderDefault_resolves() {
        UnaryOperator<String> emptyLookup = name -> null;
        EnvironmentAuthHeaderResolver resolver = new EnvironmentAuthHeaderResolver(emptyLookup);

        assertThat(resolver.resolve(AuthConfig.bearer("${T_REF:fallback-token}"))).isEqualTo("Bearer fallback-token");
    }
}
