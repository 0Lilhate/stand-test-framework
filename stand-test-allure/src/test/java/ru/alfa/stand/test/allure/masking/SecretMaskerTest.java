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
    @DisplayName("a Bearer/Basic credential-shaped VALUE is masked even under an innocuous key")
    void mask_credentialShapedValue_isMasked() {
        assertThat(masker.mask("X-Custom", "Bearer sk-abc123def456")).isEqualTo(SecretMasker.MASK);
        assertThat(masker.mask("note", "basic dXNlcjpwYXNzd29yZA==")).isEqualTo(SecretMasker.MASK);
    }

    @Test
    @DisplayName("prose values that merely start with bearer/basic are not over-masked")
    void mask_proseValues_passThrough() {
        assertThat(masker.mask("message", "Basic authentication required")).isEqualTo("Basic authentication required");
        assertThat(masker.mask("note", "bearer of good news")).isEqualTo("bearer of good news");
        assertThat(masker.mask("url", "https://basic-auth.example/x")).isEqualTo("https://basic-auth.example/x");
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

    @Test
    @DisplayName("maskText masks JSON string fields under sensitive keys, whatever the separator spacing")
    void maskText_sensitiveJsonStringField_isMasked() {
        assertThat(masker.maskText("{\"login\":\"alice\",\"password\":\"hunter2\"}"))
                .contains("\"password\":\"***\"")
                .contains("\"login\":\"alice\"")
                .doesNotContain("hunter2");
        assertThat(masker.maskText("{\"X-Api-Key\" : \"k-123\"}"))
                .contains("\"X-Api-Key\" : \"***\"")
                .doesNotContain("k-123");
        assertThat(masker.maskText("{\"clientSecret\":\"a\\\"b\\\"c12345\"}"))
                .contains("\"clientSecret\":\"***\"")
                .doesNotContain("c12345");
    }

    @Test
    @DisplayName("maskText masks non-string JSON scalars under sensitive keys")
    void maskText_sensitiveNonStringValue_isMasked() {
        assertThat(masker.maskText("{\"pinToken\":1234,\"attempts\":3}"))
                .contains("\"pinToken\":\"***\"")
                .contains("\"attempts\":3")
                .doesNotContain("1234");
        assertThat(masker.maskText("{\"secretEnabled\":true}")).contains("\"secretEnabled\":\"***\"");
    }

    @Test
    @DisplayName("maskText catches sensitive keys nested inside objects and arrays")
    void maskText_nestedSensitiveKeys_areMaskedInside() {
        assertThat(masker.maskText("{\"credentials\":{\"user\":\"alice\",\"password\":\"x1\"}}"))
                .contains("\"password\":\"***\"")
                .contains("\"user\":\"alice\"")
                .doesNotContain("x1");
    }

    @Test
    @DisplayName("maskText masks embedded Bearer/Basic credential tokens anywhere in the body")
    void maskText_embeddedCredential_isMasked() {
        assertThat(masker.maskText("header Authorization was Bearer sk-abc123def456 at call time"))
                .contains("Bearer ***")
                .doesNotContain("sk-abc123def456");
        assertThat(masker.maskText("<auth>basic dXNlcjpwYXNzd29yZA==</auth>"))
                .contains("basic ***")
                .doesNotContain("dXNlcjpwYXNzd29yZA==");
    }

    @Test
    @DisplayName("maskText leaves prose that merely contains bearer/basic untouched")
    void maskText_prose_passesThrough() {
        assertThat(masker.maskText("Basic authentication required")).isEqualTo("Basic authentication required");
        assertThat(masker.maskText("bearer of good news")).isEqualTo("bearer of good news");
    }

    @Test
    @DisplayName("maskText returns non-secret JSON byte-for-byte unchanged")
    void maskText_nonSecretJson_isUnchanged() {
        String body = "{\"requestId\":\"r-1\",\"amount\": 100,\"note\":\"paid\",\"flags\":[true,null]}";

        assertThat(masker.maskText(body)).isEqualTo(body);
    }

    @Test
    @DisplayName("maskText is safe on null and empty content")
    void maskText_nullAndEmpty_areSafe() {
        assertThat(masker.maskText(null)).isNull();
        assertThat(masker.maskText("")).isEmpty();
    }

    @Test
    @DisplayName("maskText completes on large content with long string values")
    void maskText_largeContent_completes() {
        String longValue = "a".repeat(100_000);
        String body = "{\"data\":\"" + longValue + "\",\"token\":\"t-1\"}";

        assertThat(masker.maskText(body))
                .contains("\"token\":\"***\"")
                .contains(longValue);
    }
}
