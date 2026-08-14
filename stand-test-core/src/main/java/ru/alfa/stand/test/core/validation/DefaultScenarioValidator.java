package ru.alfa.stand.test.core.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
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
 * ({@link ForbiddenOperation#NON_WHITELISTED_ENVIRONMENT}) and every step's logical alias must resolve in
 * that environment pre-flight — a REST {@code service} ({@link ForbiddenOperation#NON_WHITELISTED_SERVICE}),
 * a Kafka {@code topic} ({@link ForbiddenOperation#NON_WHITELISTED_TOPIC}), a gRPC {@code target}
 * ({@link ForbiddenOperation#NON_WHITELISTED_GRPC_TARGET}), a {@code ui.*} {@code application}
 * ({@link ForbiddenOperation#NON_WHITELISTED_UI_APPLICATION}) and a {@code db.*} {@code datasource}
 * ({@link ForbiddenOperation#NON_WHITELISTED_DATASOURCE}) — so a typo'd alias in ANY step aborts the run
 * before an earlier step can mutate the stand, not only when the adapter later resolves it. A {@code db.*}
 * step's inline SQL must additionally not be destructive or unclassifiable
 * ({@link ForbiddenOperation#DESTRUCTIVE_SQL_WITHOUT_ALLOW}), and a {@code ui.login} step must name a role
 * the application declares ({@code UI_LOGIN_ROLE_REQUIRED} / {@code UI_LOGIN_ROLE_UNKNOWN}). Adapters
 * re-resolve each alias as defence in depth (plan §8.6); DB write-allow semantics stay with the adapters.
 *
 * <p>The registry overload also enforces the value-level guardrails the AI JSON Schema
 * ({@code stand-test-ai-schema}) used to express statically. That module was removed deliberately, so this
 * is no longer a second net under a first one — it is the only net, and every declarative document reaches
 * the runner without a prior schema pass (plan §11):
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

    private static final List<String> TIMEOUT_KEYS = List.of(
            StepParameterKeys.TIMEOUT_MILLIS,
            StepParameterKeys.POLL_TIMEOUT_MILLIS,
            StepParameterKeys.POLL_INTERVAL_MILLIS,
            StepParameterKeys.DEADLINE_MILLIS,
            StepParameterKeys.ACCOUNT_TIMEOUT_MILLIS);

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
            if (step instanceof GenericStep generic) {
                checkAliasWhitelist(generic, definition, issues);
            }
        }
    }

    /**
     * Pre-flight alias whitelist: every step's logical alias (REST {@code service}, Kafka {@code topic},
     * gRPC {@code target}, UI {@code application}, {@code db.*} {@code datasource}) must resolve in the
     * environment, so a typo'd or non-whitelisted alias in ANY step aborts the run before an earlier step can
     * mutate the stand — not only when the adapter later resolves it. Only a plain string alias is checked (a
     * step that omits the alias is a per-adapter schema concern); adapters re-resolve as defence in depth
     * (plan §8.6).
     *
     * <p>For a {@code ui.*} step this is what rejects a non-whitelisted application before a browser is
     * started. Note the shared limit of {@code checkAlias}, which matters more here than elsewhere: only a
     * <em>declared</em> alias is checked, so a step omitting the parameter passes — for the other
     * transports the adapter's parameter schema and its own re-resolution close that (plan §8.6), and for
     * {@code ui.*} the schema of the not-yet-shipped UI step owns the "alias is required" rule. A prefix
     * that reaches no branch of this dispatch is silently unguarded, which is why every branch carries a
     * test proving the guardrail fires — and one proving it stops firing when the branch is removed.
     */
    private static void checkAliasWhitelist(GenericStep step, EnvironmentDefinition environment, List<ValidationIssue> issues) {
        String type = step.type();
        if (type.startsWith(StepParameterKeys.DB_PREFIX)) {
            checkDbStep(step, environment, issues);
        } else if (type.startsWith(StepParameterKeys.REST_PREFIX)) {
            checkAlias(step, StepParameterKeys.SERVICE, environment, ForbiddenOperation.NON_WHITELISTED_SERVICE, "Service", EnvironmentDefinition::service, issues);
        } else if (type.startsWith(StepParameterKeys.KAFKA_PREFIX)) {
            checkAlias(step, StepParameterKeys.TOPIC, environment, ForbiddenOperation.NON_WHITELISTED_TOPIC, "Topic", EnvironmentDefinition::topic, issues);
        } else if (type.startsWith(StepParameterKeys.GRPC_PREFIX)) {
            checkAlias(step, StepParameterKeys.TARGET, environment, ForbiddenOperation.NON_WHITELISTED_GRPC_TARGET, "gRPC target", EnvironmentDefinition::grpcTarget, issues);
        } else if (type.startsWith(StepParameterKeys.UI_PREFIX)) {
            checkAlias(step, StepParameterKeys.APPLICATION, environment, ForbiddenOperation.NON_WHITELISTED_UI_APPLICATION, "UI application", EnvironmentDefinition::uiApplication, issues);
            checkUiLoginRole(step, environment, issues);
        }
    }

    /**
     * Pre-flight rule for {@code ui.login}: once an application declares the roles a scenario may request,
     * a sign-in step must name one of them, and the one it names must be declared.
     *
     * <p>Both halves matter and neither can be checked where the step is built: a lazy builder never sees
     * the environment registry, so "any account" and "a role nobody declared" would otherwise be found only
     * after a browser had started and an account had been leased. Checking here costs nothing and fails
     * before the run touches anything. These are configuration/scenario mismatches rather than forbidden
     * operations, so they carry their own codes instead of a {@link ForbiddenOperation} one.
     */
    private static void checkUiLoginRole(GenericStep step, EnvironmentDefinition environment, List<ValidationIssue> issues) {
        if (!StepParameterKeys.UI_LOGIN_TYPE.equals(step.type())) {
            return;
        }
        if (!(step.parameters().get(StepParameterKeys.APPLICATION) instanceof String alias) || alias.isBlank()) {
            return;
        }
        List<String> declaredRoles = environment.uiApplication(alias)
                .map(UiApplicationDefinition::auth)
                .filter(Objects::nonNull)
                .map(UiAuthConfig::roles)
                .orElse(List.of());
        if (declaredRoles.isEmpty()) {
            return;
        }
        Object requested = step.parameters().get(StepParameterKeys.ROLE);
        if (!(requested instanceof String role) || role.isBlank()) {
            issues.add(ValidationIssue.error(
                    "UI_LOGIN_ROLE_REQUIRED",
                    "Step '" + step.id() + "' signs in to UI application '" + alias + "', which declares roles " + declaredRoles
                            + " — name one with role(...): with a pool of accounts, 'any account' is not expressible"));
            return;
        }
        if (!declaredRoles.contains(role)) {
            issues.add(ValidationIssue.error(
                    "UI_LOGIN_ROLE_UNKNOWN",
                    "Step '" + step.id() + "' requests role '" + role + "' of UI application '" + alias
                            + "', which declares only " + declaredRoles));
        }
    }

    private static void checkAlias(
            GenericStep step,
            String parameterKey,
            EnvironmentDefinition environment,
            ForbiddenOperation operation,
            String label,
            BiFunction<EnvironmentDefinition, String, Optional<?>> resolver,
            List<ValidationIssue> issues) {
        if (step.parameters().get(parameterKey) instanceof String alias && !alias.isBlank()
                && resolver.apply(environment, alias).isEmpty()) {
            issues.add(ValidationIssue.error(
                    operation.code(),
                    label + " '" + alias + "' is not whitelisted in environment '" + environment.name() + "'"));
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
        checkPollIntervalFitsTheTimeout(step, issues);
    }

    /**
     * A poll interval larger than the wait it belongs to is refused, for every adapter that polls.
     *
     * <p>Each of the two is bounded on its own, which is why this needs saying separately: the pair is not.
     * A step declaring a 500 ms timeout and a 60 s interval passes both checks and then waits a minute — the
     * awaiter probes once, and the adapters that bound a single probe by the interval (the UI one does, so
     * that no probe can eat the step's budget) block for the whole interval. The step's declared bound then
     * describes nothing, which is what {@link ForbiddenOperation#UNBOUNDED_TIMEOUT} names: a wait that is
     * effectively unbounded relative to what was declared.
     */
    private static void checkPollIntervalFitsTheTimeout(GenericStep step, List<ValidationIssue> issues) {
        if (!(step.parameters().get(StepParameterKeys.POLL_INTERVAL_MILLIS) instanceof Number interval)
                || !(step.parameters().get(StepParameterKeys.TIMEOUT_MILLIS) instanceof Number timeout)) {
            return;
        }
        if (interval.longValue() > timeout.longValue()) {
            issues.add(ValidationIssue.error(
                    ForbiddenOperation.UNBOUNDED_TIMEOUT.code(),
                    "Step '" + step.id() + "' polls every " + interval.longValue() + " ms inside a wait of " + timeout.longValue()
                            + " ms — the interval must not exceed the timeout, or the step waits for the interval and its declared bound describes nothing"));
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
     * re-classified fail-closed by the DB adapter at runtime over the exact assembled SQL, before any IO:
     * {@code DbWriteGuard} runs the SAME destructive/unclassifiable classification AND the same
     * {@link SqlStatementClassifier#containsSideEffectingTimeFunction sleep/side-effect scan} this method
     * applies here, so the resource path genuinely meets the same net one layer later (they share
     * {@link SqlStatementClassifier}, so they cannot drift). The same applies to any non-{@code GenericStep}
     * custom step the static layer cannot inspect.
     */
    private static void checkSql(GenericStep step, String sql, List<ValidationIssue> issues) {
        SqlStatementKind kind = SqlStatementClassifier.classify(sql).kind();
        if (kind == SqlStatementKind.DESTRUCTIVE || kind == SqlStatementKind.REJECTED) {
            issues.add(ValidationIssue.error(
                    ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code(),
                    "Destructive or unclassifiable SQL in step '" + step.id() + "'"));
        }
        if (SqlStatementClassifier.containsSideEffectingTimeFunction(sql)) {
            issues.add(ValidationIssue.error(
                    ForbiddenOperation.THREAD_SLEEP.code(),
                    "SQL sleep/side-effect time function in step '" + step.id()
                            + "' — the only wait is the declarative step timeout"));
        }
    }

    private static void checkTimeout(GenericStep step, String key, Object value, List<ValidationIssue> issues) {
        if (!(value instanceof Number)) {
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
