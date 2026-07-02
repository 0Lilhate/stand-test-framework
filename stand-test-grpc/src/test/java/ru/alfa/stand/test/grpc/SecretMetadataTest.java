package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretMetadataTest {

    @Test
    @DisplayName("secret-bearing names are recognised, including wrapped api-key variants")
    void recognisesSecretNames() {
        assertThat(SecretMetadata.isSecret("Authorization")).isTrue();
        assertThat(SecretMetadata.isSecret("x-access-token")).isTrue();
        assertThat(SecretMetadata.isSecret("user-password")).isTrue();
        assertThat(SecretMetadata.isSecret("session-cookie")).isTrue();
        assertThat(SecretMetadata.isSecret("x-api-key")).isTrue();
        assertThat(SecretMetadata.isSecret("grpc-apikey")).isTrue();
    }

    @Test
    @DisplayName("ordinary names are not treated as secret")
    void allowsOrdinaryNames() {
        assertThat(SecretMetadata.isSecret("x-tenant")).isFalse();
        assertThat(SecretMetadata.isSecret("x-request-id")).isFalse();
        assertThat(SecretMetadata.isSecret(null)).isFalse();
    }

    @Test
    @DisplayName("maskIfSecret masks secret values and leaves ordinary values intact")
    void masksSecretValues() {
        assertThat(SecretMetadata.maskIfSecret("x-api-key", "k-123")).isEqualTo("***");
        assertThat(SecretMetadata.maskIfSecret("x-tenant", "acme")).isEqualTo("acme");
    }
}
