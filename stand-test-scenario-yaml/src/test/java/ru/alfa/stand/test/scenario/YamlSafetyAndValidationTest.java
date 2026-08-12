package ru.alfa.stand.test.scenario;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

class YamlSafetyAndValidationTest {

    private final YamlScenarioParser parser = new YamlScenarioParser();

    @Test
    @DisplayName("the safe loader rejects arbitrary Java-type tags instead of instantiating them")
    void safeLoader_rejectsArbitraryTags() {
        assertThatThrownBy(() -> parser.parse("id: !!java.util.ArrayList []\nenv: ift\n"))
                .isInstanceOf(StandTestException.class);
    }

    // The alias and nesting limits are the half of SafeYaml this module was NOT pinning, while
    // stand-test-config pinned all four of its own. The two SafeYaml copies are duplicated on purpose (a
    // shared one would need SnakeYAML in core, which core must never take), so nothing but a test on each
    // side keeps their numbers together — and this is the side whose stated threat model is an
    // AI-generated document.

    @Test
    @DisplayName("an alias bomb (more than 10 aliases for collections) is rejected by the loader options")
    void aliasBomb_isRejected() {
        StringBuilder yaml = new StringBuilder("base: &b [1, 2]\nlist: [");
        for (int index = 0; index < 11; index++) {
            if (index > 0) {
                yaml.append(", ");
            }
            yaml.append("*b");
        }
        yaml.append("]\n");

        // SafeYaml.load, not parser.parse: the parser refuses this document for a dozen other reasons
        // (unknown top-level fields among them), so going through it would pass whether the alias limit
        // fired or not. The loader's own message is what proves WHICH check refused it.
        assertThatThrownBy(() -> SafeYaml.load(yaml.toString()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to parse document");
    }

    @Test
    @DisplayName("a document nested deeper than 50 levels is rejected by the loader options")
    void excessiveNesting_isRejected() {
        StringBuilder yaml = new StringBuilder();
        for (int depth = 0; depth < 60; depth++) {
            yaml.append("  ".repeat(depth)).append("a:\n");
        }
        yaml.append("  ".repeat(60)).append("1\n");

        assertThatThrownBy(() -> SafeYaml.load(yaml.toString()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to parse document");
    }

    @Test
    @DisplayName("duplicate keys are rejected by the loader options")
    void duplicateKeys_areRejected() {
        assertThatThrownBy(() -> parser.parse("""
                id: a
                id: b
                env: ift
                given:
                  - rest.get:
                      service: s
                      path: /a
                """)).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("a well-formed scenario passes the shared DefaultScenarioValidator")
    void builtScenario_passesValidator() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - rest.post:
                      service: client-service
                      path: /api/request
                then:
                  - db.expectEventually:
                      datasource: mainDb
                      sql: select 1
                      equals: 1
                """);

        assertThatCode(() -> new DefaultScenarioValidator().validate(scenario).throwIfInvalid()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("duplicate explicit step ids are caught by the shared validator")
    void duplicateStepIds_areCaughtByValidator() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - rest.get:
                      id: dup
                      service: s
                      path: /a
                  - rest.get:
                      id: dup
                      service: s
                      path: /b
                """);

        assertThatThrownBy(() -> new DefaultScenarioValidator().validate(scenario).throwIfInvalid())
                .isInstanceOf(StandTestException.class);
    }
}
