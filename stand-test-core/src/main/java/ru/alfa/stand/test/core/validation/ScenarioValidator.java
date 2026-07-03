package ru.alfa.stand.test.core.validation;

import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.scenario.Scenario;

/**
 * Validates a scenario model before execution.
 *
 * <p>The validator is the single place where structural rules and environment/forbidden-op guardrails are
 * enforced for both DSL inputs. Structural rules need no context; the environment/datasource whitelist and
 * destructive-SQL guardrails need the run's {@link EnvironmentRegistry}, so they are enforced through the
 * two-argument overload (which the runner calls before executing any step).
 */
public interface ScenarioValidator {

    /**
     * Validates the given scenario's structure (no environment context).
     *
     * @param scenario the scenario to validate
     * @return the validation result
     */
    ValidationResult validate(Scenario scenario);

    /**
     * Validates the given scenario against the run's environment registry, adding the pre-execution
     * guardrail checks (environment/datasource whitelist, destructive SQL, secret headers, timeout
     * bounds) on top of the structural rules. Deliberately abstract — an earlier default silently
     * delegated to {@link #validate(Scenario)}, which let a custom validator drop every guardrail
     * without any signal; an implementation must now make that choice explicitly.
     *
     * @param scenario the scenario to validate
     * @param registry the environment registry the scenario will run against
     * @return the validation result
     */
    ValidationResult validate(Scenario scenario, EnvironmentRegistry registry);
}
