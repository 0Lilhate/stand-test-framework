package ru.alfa.stand.test.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

class DefaultScenarioValidatorTest {

    private final ScenarioValidator validator = new DefaultScenarioValidator();

    @Test
    @DisplayName("a well-formed scenario is valid")
    void validScenario_passes() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(GenericStep.of("s1", "rest.post"))
                .step(GenericStep.of("s2", "kafka.expect"))
                .build();

        ValidationResult result = validator.validate(scenario);

        assertThat(result.isValid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("an empty scenario reports a missing-steps error")
    void emptySteps_reportsError() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow")).environment("ift").build();

        ValidationResult result = validator.validate(scenario);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).extracting(ValidationIssue::code).contains("STEPS_REQUIRED");
    }

    @Test
    @DisplayName("duplicate step ids are reported")
    void duplicateStepIds_reportError() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(GenericStep.of("dup", "rest.post"))
                .step(GenericStep.of("dup", "kafka.expect"))
                .build();

        ValidationResult result = validator.validate(scenario);

        assertThat(result.errors()).extracting(ValidationIssue::code).contains("STEP_ID_DUPLICATE");
    }

    @Test
    @DisplayName("a blank environment is reported")
    void blankEnvironment_reportsError() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .step(GenericStep.of("s1", "rest.post"))
                .build();

        ValidationResult result = validator.validate(scenario);

        assertThat(result.errors()).extracting(ValidationIssue::code).contains("ENVIRONMENT_REQUIRED");
    }

    @Test
    @DisplayName("a blank step type is reported")
    void blankStepType_reportsError() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(new FakeStep("s1", "  "))
                .build();

        ValidationResult result = validator.validate(scenario);

        assertThat(result.errors()).extracting(ValidationIssue::code).contains("STEP_TYPE_REQUIRED");
    }

    @Test
    @DisplayName("a blank step id (from a non-GenericStep impl) is reported")
    void blankStepId_reportsError() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(new FakeStep("  ", "rest.post"))
                .build();

        ValidationResult result = validator.validate(scenario);

        assertThat(result.errors()).extracting(ValidationIssue::code).contains("STEP_ID_REQUIRED");
    }

    /** Test double that allows a blank type, which {@link GenericStep} would reject. */
    private record FakeStep(String id, String type) implements ScenarioStep {

        @Override
        public String description() {
            return "";
        }
    }
}
