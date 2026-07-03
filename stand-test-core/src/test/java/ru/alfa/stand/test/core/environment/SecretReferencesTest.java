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
