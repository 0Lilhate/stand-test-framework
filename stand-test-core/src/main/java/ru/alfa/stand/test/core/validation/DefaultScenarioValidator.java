package ru.alfa.stand.test.core.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * Structural scenario validator.
 *
 * <p>Checks that the scenario id is present, the environment is not blank, there is at least one
 * step, step ids are unique and step types are not blank. Environment whitelist and forbidden-op
 * validation are intentionally out of scope for this iteration.
 */
public final class DefaultScenarioValidator implements ScenarioValidator {

    @Override
    public ValidationResult validate(Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        List<ValidationIssue> issues = new ArrayList<>();
        if (scenario.id() == null) {
            issues.add(ValidationIssue.error("SCENARIO_ID_REQUIRED", "Scenario id must not be null"));
        }
        if (scenario.environment() == null || scenario.environment().isBlank()) {
            issues.add(ValidationIssue.error("ENVIRONMENT_REQUIRED", "Scenario environment must not be blank"));
        }
        List<ScenarioStep> steps = scenario.steps();
        if (steps.isEmpty()) {
            issues.add(ValidationIssue.error("STEPS_REQUIRED", "Scenario must contain at least one step"));
        }
        Set<String> seenIds = new HashSet<>();
        for (ScenarioStep step : steps) {
            String id = step.id();
            if (id == null || id.isBlank()) {
                issues.add(ValidationIssue.error("STEP_ID_REQUIRED", "Step id must not be blank"));
            } else if (!seenIds.add(id)) {
                issues.add(ValidationIssue.error("STEP_ID_DUPLICATE", "Duplicate step id: " + id));
            }
            if (step.type() == null || step.type().isBlank()) {
                issues.add(ValidationIssue.error("STEP_TYPE_REQUIRED", "Step type must not be blank: stepId=" + id));
            }
        }
        return ValidationResult.of(issues);
    }
}
