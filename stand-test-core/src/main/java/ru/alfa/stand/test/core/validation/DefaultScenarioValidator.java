package ru.alfa.stand.test.core.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

/**
 * Structural scenario validator with pre-execution guardrails.
 *
 * <p>{@link #validate(Scenario)} checks structure only: the scenario id is present, the environment is not
 * blank, there is at least one step, step ids are unique and step types are not blank.
 *
 * <p>{@link #validate(Scenario, EnvironmentRegistry)} adds the guardrails the runner enforces before any
 * step runs, keyed off {@link ForbiddenOperation}: the scenario environment must be whitelisted
 * ({@link ForbiddenOperation#NON_WHITELISTED_ENVIRONMENT}), every {@code db.*} step's datasource must be
 * whitelisted ({@link ForbiddenOperation#NON_WHITELISTED_DATASOURCE}) and its inline SQL must not be
 * destructive or unclassifiable ({@link ForbiddenOperation#DESTRUCTIVE_SQL_WITHOUT_ALLOW}). Service/topic
 * whitelist and DB write-allow semantics stay with the adapters as defence in depth.
 */
public final class DefaultScenarioValidator implements ScenarioValidator {

    @Override
    public ValidationResult validate(Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        return ValidationResult.of(structuralIssues(scenario));
    }

    @Override
    public ValidationResult validate(Scenario scenario, EnvironmentRegistry registry) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        List<ValidationIssue> issues = structuralIssues(scenario);
        guardrailIssues(scenario, registry, issues);
        return ValidationResult.of(issues);
    }

    private static List<ValidationIssue> structuralIssues(Scenario scenario) {
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
        return issues;
    }

    private static void guardrailIssues(Scenario scenario, EnvironmentRegistry registry, List<ValidationIssue> issues) {
        String environmentName = scenario.environment();
        if (environmentName == null || environmentName.isBlank()) {
            return;
        }
        Optional<EnvironmentDefinition> environment = registry.environment(environmentName);
        if (environment.isEmpty()) {
            issues.add(ValidationIssue.error(
                    ForbiddenOperation.NON_WHITELISTED_ENVIRONMENT.code(),
                    "Environment '" + environmentName + "' is not whitelisted"));
            return;
        }
        EnvironmentDefinition definition = environment.get();
        for (ScenarioStep step : scenario.steps()) {
            if (step instanceof GenericStep generic && generic.type().startsWith(StepParameterKeys.DB_PREFIX)) {
                checkDbStep(generic, definition, issues);
            }
        }
    }

    private static void checkDbStep(GenericStep step, EnvironmentDefinition environment, List<ValidationIssue> issues) {
        Map<String, Object> parameters = step.parameters();
        if (parameters.get(StepParameterKeys.DATASOURCE) instanceof String datasource
                && environment.datasource(datasource).isEmpty()) {
            issues.add(ValidationIssue.error(
                    ForbiddenOperation.NON_WHITELISTED_DATASOURCE.code(),
                    "Datasource '" + datasource + "' is not whitelisted in environment '" + environment.name() + "'"));
        }
        if (parameters.get(StepParameterKeys.SQL) instanceof String sql) {
            SqlStatementKind kind = SqlStatementClassifier.classify(sql).kind();
            if (kind == SqlStatementKind.DESTRUCTIVE || kind == SqlStatementKind.REJECTED) {
                issues.add(ValidationIssue.error(
                        ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code(),
                        "Destructive or unclassifiable SQL in step '" + step.id() + "'"));
            }
        }
    }
}
