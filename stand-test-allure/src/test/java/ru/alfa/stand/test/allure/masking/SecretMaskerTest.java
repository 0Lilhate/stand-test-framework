package ru.alfa.stand.test.allure.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretMaskerTest {

    private final SecretMasker masker = new SecretMasker();

    @Test
    @DisplayName("values under secret-looking keys are masked, case-insensitively")
    void mask_sensitiveKeys_areMasked() {
        assertThat(masker.mask("password", "hunter2")).isEqualTo("***");
        assertThat(masker.mask("Authorization", "Bearer abc")).isEqualTo("***");
        assertThat(masker.mask("X-Api-Key", "k-123")).isEqualTo("***");
        assertThat(masker.mask("apiKey", "k-123")).isEqualTo("***");
        assertThat(masker.mask("Set-Cookie", "sid=42")).isEqualTo("***");
        assertThat(masker.mask("session.token", "t-99")).isEqualTo("***");
        assertThat(masker.mask("clientSecret", "s-1")).isEqualTo("***");
    }

    @Test
    @DisplayName("values under ordinary keys pass through unchanged")
    void mask_ordinaryKeys_passThrough() {
        assertThat(masker.mask("scenarioId", "flow")).isEqualTo("flow");
        assertThat(masker.mask("status", "200")).isEqualTo("200");
        assertThat(masker.isSensitive("environment")).isFalse();
        assertThat(masker.isSensitive(null)).isFalse();
    }

    @Test
    @DisplayName("a map is masked entry-by-entry, preserving order, with nulls rendered as text")
    void mask_map_masksSensitiveAndKeepsOrder() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("login", "alice");
        input.put("password", "hunter2");
        input.put("token", null);

        Map<String, String> masked = masker.mask(input);

        assertThat(masked).containsExactly(
                Map.entry("login", "alice"),
                Map.entry("password", "***"),
                Map.entry("token", "***"));
    }

    @Test
    @DisplayName("a null map masks to an empty map")
    void mask_nullMap_isEmpty() {
        assertThat(masker.mask((Map<String, String>) null)).isEmpty();
    }
}
