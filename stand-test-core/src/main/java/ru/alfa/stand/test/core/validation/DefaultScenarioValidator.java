package ru.alfa.stand.test.core.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
 *
 * <p>The registry overload also re-enforces at runtime the value-level guardrails the AI JSON Schema
 * ({@code stand-test-ai-schema}) expresses statically, so a declarative document that reaches the runner
 * WITHOUT a prior schema pass meets the same net (runtime is a superset of the schema, plan §11):
 * secret-bearing header names and {@code Bearer}/{@code Basic}-shaped header values are rejected
 * ({@link ForbiddenOperation#SECRET_IN_SOURCE}), SQL sleep/side-effect time functions are rejected
 * ({@link ForbiddenOperation#THREAD_SLEEP}) and every declared timeout/deadline must be a positive whole
 * number of milliseconds no greater than {@link #MAX_TIMEOUT_MILLIS}
 * ({@link ForbiddenOperation#UNBOUNDED_TIMEOUT}). The checks are deliberately fail-closed textual nets
 * (like the schema patterns they mirror): a quoted identifier that merely looks like a sleep function is
 * rejected too.
 */
public final class DefaultScenarioValidator implements ScenarioValidator {

    /**
     * Upper bound for every declared step timeout/deadline, in milliseconds (1 hour). Aligned with the AI
     * schema's {@code duration} caps ({@code <=99999ms} / {@code <=999s} / {@code <=60m}), so a
     * schema-valid document always passes this bound and a larger value is rejected as effectively
     * unbounded ({@link ForbiddenOperation#UNBOUNDED_TIMEOUT}).
     */
    public static final long MAX_TIMEOUT_MILLIS = 3_600_000L;

    private static final Pattern SECRET_HEADER_NAME =
            Pattern.compile("authorization|token|password|secret|api[-_]?key|cookie", Pattern.CASE_INSENSITIVE);

    private static final Pattern SECRET_HEADER_VALUE =
            Pattern.compile("^\\s*(?:bearer|basic)\\s+\\S+", Pattern.CASE_INSENSITIVE);

    private static final Pattern SQL_SLEEP_FUNCTION =
            Pattern.compile("\\b(?:pg_sleep|sleep|waitfor|benchmark|dbms_lock)\\b", Pattern.CASE_INSENSITIVE);

    private static final List<String> TIMEOUT_KEYS = List.of(
            StepParameterKeys.TIMEOUT_MILLIS,
            StepParameterKeys.POLL_TIMEOUT_MILLIS,
            StepParameterKeys.POLL_INTERVAL_MILLIS,
            StepParameterKeys.DEADLINE_MILLIS);

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
        for (ScenarioStep step : scenario.steps()) {
            if (step instanceof GenericStep generic) {
                checkStepValues(generic, issues);
            }
        }
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

    /**
     * Environment-independent value guardrails, applied to every {@link GenericStep} regardless of
     * whether the scenario environment resolves: inline secret headers, SQL sleep/side-effect functions
     * and timeout/deadline bounds. Running them before the environment whitelist keeps the report
     * complete even when the environment itself is rejected.
     */
    private static void checkStepValues(GenericStep step, List<ValidationIssue> issues) {
        Map<String, Object> parameters = step.parameters();
        if (parameters.get(StepParameterKeys.HEADERS) instanceof Map<?, ?> headers) {
            checkHeaders(step, headers, issues);
        }
        if (step.type().startsWith(StepParameterKeys.DB_PREFIX)
                && parameters.get(StepParameterKeys.SQL) instanceof String sql) {
            checkSql(step, sql, issues);
        }
        for (String key : TIMEOUT_KEYS) {
            Object value = parameters.get(key);
            if (value != null) {
                checkTimeout(step, key, value, issues);
            }
        }
    }

    private static void checkHeaders(GenericStep step, Map<?, ?> headers, List<ValidationIssue> issues) {
        for (Map.Entry<?, ?> header : headers.entrySet()) {
            String name = String.valueOf(header.getKey());
            if (SECRET_HEADER_NAME.matcher(name).find()) {
                issues.add(ValidationIssue.error(
                        ForbiddenOperation.SECRET_IN_SOURCE.code(),
                        "Secret-bearing header name '" + name + "' in step '" + step.id()
                                + "' — secrets are supplied by the SDK from secret references, never inline"));
            } else if (header.getValue() instanceof String value && SECRET_HEADER_VALUE.matcher(value).find()) {
                issues.add(ValidationIssue.error(
                        ForbiddenOperation.SECRET_IN_SOURCE.code(),
                        "Header '" + name + "' in step '" + step.id()
                                + "' carries a Bearer/Basic credential value — secrets are supplied by the SDK from secret references, never inline"));
            }
        }
    }

    /**
     * Static SQL guardrail over the INLINE {@code sql} parameter only. A {@code sqlResource} parameter
     * is a classpath path whose content is not visible at validation time — that content is loaded and
     * re-classified fail-closed by the DB adapter at runtime ({@code DbWriteGuard.classifyAndEnforce}
     * over the exact assembled SQL, before any IO), so the resource path meets the same net one layer
     * later. The same applies to any non-{@code GenericStep} custom step the static layer cannot inspect.
     */
    private static void checkSql(GenericStep step, String sql, List<ValidationIssue> issues) {
        SqlStatementKind kind = SqlStatementClassifier.classify(sql).kind();
        if (kind == SqlStatementKind.DESTRUCTIVE || kind == SqlStatementKind.REJECTED) {
            issues.add(ValidationIssue.error(
                    ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code(),
                    "Destructive or unclassifiable SQL in step '" + step.id() + "'"));
        }
        if (SQL_SLEEP_FUNCTION.matcher(sql).find()) {
            issues.add(ValidationIssue.error(
                    ForbiddenOperation.THREAD_SLEEP.code(),
                    "SQL sleep/side-effect time function in step '" + step.id()
                            + "' — the only wait is the declarative step timeout"));
        }
    }

    private static void checkTimeout(GenericStep step, String key, Object value, List<ValidationIssue> issues) {
        if (!(value instanceof Number)) {
            // Non-numeric values are the adapter parameter schema's concern (a config error there);
            // the guardrail bounds only what is already declared as a number.
            return;
        }
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            issues.add(ValidationIssue.error(
                    ForbiddenOperation.UNBOUNDED_TIMEOUT.code(),
                    "Timeout '" + key + "' in step '" + step.id()
                            + "' must be a whole number of milliseconds (Integer or Long), but was " + value));
            return;
        }
        long millis = ((Number) value).longValue();
        if (millis <= 0 || millis > MAX_TIMEOUT_MILLIS) {
            issues.add(ValidationIssue.error(
                    ForbiddenOperation.UNBOUNDED_TIMEOUT.code(),
                    "Timeout '" + key + "' in step '" + step.id() + "' must be within (0; " + MAX_TIMEOUT_MILLIS
                            + "] milliseconds, but was " + millis));
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
    }
}
