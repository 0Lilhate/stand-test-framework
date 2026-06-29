package ru.alfa.stand.test.core.validation;

import ru.alfa.stand.test.core.scenario.Scenario;

/**
 * Validates a scenario model before execution.
 *
 * <p>The validator is the single place where structural rules and (later) environment/forbidden-op
 * rules are enforced for both DSL inputs. This iteration ships the contract plus a structural
 * {@link DefaultScenarioValidator}.
 */
public interface ScenarioValidator {

    /**
     * Validates the given scenario.
     *
     * @param scenario the scenario to validate
     * @return the validation result
     */
    ValidationResult validate(Scenario scenario);
}
