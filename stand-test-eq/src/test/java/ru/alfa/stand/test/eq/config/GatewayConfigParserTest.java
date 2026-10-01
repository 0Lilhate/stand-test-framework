package ru.alfa.stand.test.eq.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.SectionEntry;

class GatewayConfigParserTest {

    @Test
    void parsesTheVersionSixGatewayExampleWithoutResolvingReferences() {
        GatewayBackendConfig config = (GatewayBackendConfig) EqBackendConfigParser.parse("test", example());

        assertThat(config.alias()).isEqualTo("eq");
        assertThat(config.writeAllowed()).isTrue();
        assertThat(config.baseUrlReference()).isEqualTo("EQ_GATEWAY_URL");
        assertThat(config.unit().reference()).isEqualTo("EQ_UNIT");
        assertThat(config.cashAccounts()).containsKey("RUR");
        assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.responseTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.acquireTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.unitPhase().allowed()).containsExactly("ACTIVE", "READY");
        assertThat(config.visibility().expectStatus()).isEqualTo(200);
        assertThat(config.defaults().topUp()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(config.innRegionCode().resolve(name -> {
            throw new AssertionError("literal must not use lookup");
        })).isEqualTo(77);
        assertThat(config.unit().resolve(name -> "UNIT-1")).isEqualTo("UNIT-1");
        // A bare ref whose variable is unset falls back to the raw text as a literal value: on the
        // Spring surface Spring collapses ${VAR:default} before the SDK sees the section, so a *-ref
        // field legitimately arrives holding a value (a URL, a unit). See SecretReferences.resolveOrLiteral.
        assertThat(config.unit().resolve(name -> null)).isEqualTo("EQ_UNIT");
        assertThat(config.innTaxOffices().resolveStringList(name -> "[\"7701\",\"7702\"]"))
                .containsExactly("7701", "7702");
        assertThatThrownBy(() -> config.innTaxOffices().resolveStringList(name -> "[12]"))
                .hasMessageContaining("non-blank strings");
        assertThatThrownBy(() -> config.innTaxOffices().resolveStringList(name -> "secret-invalid-json"))
                .hasMessageContaining("JSON array").hasMessageNotContaining("secret-invalid-json");
    }

    @Test
    void rejectsUnknownNestedFieldsAndReferenceTraps() {
        assertThatThrownBy(() -> EqBackendConfigParser.parse("test", with("timeouts", Map.of("respones", "60s"))))
                .hasMessageContaining("test").hasMessageContaining("eq").hasMessageContaining("respones");
        // A *-ref field accepts a reference OR a value: a Spring-collapsed ${VAR:default} arrives as the
        // value itself, and the type system cannot tell it from a URL. The literal marker is still refused.
        assertThat(EqBackendConfigParser.parse("test", with("base-url-ref", "${EQ_GATEWAY_URL}"))).isNotNull();
        assertThatThrownBy(() -> EqBackendConfigParser.parse("test", with("base-url-ref", "literal://x")))
                .hasMessageContaining("literal marker");
        assertThatThrownBy(() -> EqBackendConfigParser.parse("test", with("unit", Map.of("ref", "EQ_UNIT", "x", 1))))
                .hasMessageContaining("unit.x");
        assertThatThrownBy(() -> EqBackendConfigParser.parse("test", with("unit", "literal://UNIT")))
                .hasMessageContaining("literal marker");
        assertThatThrownBy(() -> EqBackendConfigParser.parse("test", with("unit", List.of("wrong"))))
                .hasMessageContaining("unit").hasMessageContaining("scalar");
        assertThatThrownBy(() -> EqBackendConfigParser.parse("test", with("inn-tax-offices", "7701")))
                .hasMessageContaining("inn-tax-offices").hasMessageContaining("list");
        assertThatThrownBy(() -> EqBackendConfigParser.parse("test", with("defaults", Map.of(
                "account", Map.of("top-up", "bad")))))
                .hasMessageContaining("top-up");
    }

    private static SectionEntry with(String key, Object value) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>(example().fields());
        fields.put(key, value);
        return new SectionEntry("eq", fields);
    }

    private static SectionEntry example() {
        return new SectionEntry("eq", Map.ofEntries(
                Map.entry("kind", "gateway"),
                Map.entry("write-allowed", true),
                Map.entry("base-url-ref", "EQ_GATEWAY_URL"),
                Map.entry("unit", Map.of("ref", "EQ_UNIT")),
                Map.entry("branch", Map.of("ref", "EQ_BRANCH")),
                Map.entry("inn-region-code", 77),
                Map.entry("inn-tax-offices", Map.of("ref", "EQ_INN_TAX_OFFICES")),
                Map.entry("cash-accounts", Map.of("RUR", Map.of("ref", "EQ_CASH_RUR"))),
                Map.entry("timeouts", Map.of("connect", "10s", "response", "60s")),
                Map.entry("serialization", Map.of("acquire-timeout", "10m")),
                Map.entry("unit-phase", Map.of("system-ref", "EQ_AS400_SYSTEM", "username-ref", "EQ_AS400_USER",
                        "password-ref", "EQ_AS400_PASSWORD", "allowed", List.of("ACTIVE", "READY"), "cache-ttl", "5m")),
                Map.entry("visibility", Map.of("probe", Map.of("service", "tks", "path", "/api/client",
                        "query", Map.of("clientCode", "{seed.pin}"), "expect-status", 200,
                        "expect-body", Map.of("path", "$.clientCode", "equals", "{seed.pin}")),
                        "timeout", "120s", "poll-interval", "2s")),
                Map.entry("defaults", Map.of("organisation", Map.of("name-prefix", "ООО АТ"),
                        "account", Map.of("type-organisation", "CA", "type-individual", "EE",
                                "currency", "RUR", "top-up", 100000)))));
    }
}
