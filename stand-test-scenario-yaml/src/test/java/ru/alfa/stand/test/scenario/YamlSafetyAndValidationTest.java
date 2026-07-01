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
