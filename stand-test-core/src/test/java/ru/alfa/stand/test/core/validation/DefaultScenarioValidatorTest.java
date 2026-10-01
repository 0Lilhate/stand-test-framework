package ru.alfa.stand.test.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentSection;
import ru.alfa.stand.test.core.environment.SectionEntry;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.environment.TopicDefinition;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.core.environment.UiLoginFormConfig;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

class DefaultScenarioValidatorTest {

    private final ScenarioValidator validator = new DefaultScenarioValidator();

    @Test
    @DisplayName("SEC-02: eq steps require a backend alias declared in the environment section")
    void eqBackendWhitelist() {
        EnvironmentDefinition ift = new EnvironmentDefinition("ift", Map.of(), Map.of(), Map.of(), Map.of(),
                null, Map.of(), Map.of(), Map.of("eq-backends", new EnvironmentSection("eq-backends",
                        Map.of("eq", new SectionEntry("eq", Map.of("kind", "showcases"))))));
        EnvironmentRegistry registry = new InMemoryEnvironmentRegistry(Map.of("ift", ift));

        Scenario allowed = Scenario.builder("eq-allowed").environment("ift")
                .step(new GenericStep("seed", "eq.seed", "", Map.of("backend", "eq"))).build();
        Scenario unknown = Scenario.builder("eq-unknown").environment("ift")
                .step(new GenericStep("seed", "eq.seed", "", Map.of("backend", "other"))).build();
        Scenario missing = Scenario.builder("eq-missing").environment("ift")
                .step(GenericStep.of("seed", "eq.seed")).build();

        assertThat(validator.validate(allowed, registry).isValid()).isTrue();
        assertThat(validator.validate(unknown, registry).errors()).extracting(ValidationIssue::code)
                .contains("NON_WHITELISTED_EQ_BACKEND");
        assertThat(validator.validate(missing, registry).errors()).extracting(ValidationIssue::code)
                .contains("EQ_BACKEND_REQUIRED");
    }

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

    @Test
    @DisplayName("guardrail: a non-whitelisted environment is a NON_WHITELISTED_ENVIRONMENT error")
    void guardrail_unknownEnvironment_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("prod")
                .step(GenericStep.of("s1", "rest.post"))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.NON_WHITELISTED_ENVIRONMENT.code());
    }

    @Test
    @DisplayName("guardrail: an empty registry rejects any environment (strict)")
    void guardrail_emptyRegistry_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(GenericStep.of("s1", "rest.post"))
                .build();

        ValidationResult result = validator.validate(scenario, new InMemoryEnvironmentRegistry(Map.of()));

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.NON_WHITELISTED_ENVIRONMENT.code());
    }

    @Test
    @DisplayName("guardrail: a db step on a non-whitelisted datasource is a NON_WHITELISTED_DATASOURCE error")
    void guardrail_unknownDatasource_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(dbStep("s1", "ghost", "SELECT 1"))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.NON_WHITELISTED_DATASOURCE.code());
    }

    @Test
    @DisplayName("guardrail: destructive inline SQL is a DESTRUCTIVE_SQL_WITHOUT_ALLOW error")
    void guardrail_destructiveSql_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(dbStep("s1", "mainDb", "TRUNCATE TABLE orders"))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code());
    }

    @Test
    @DisplayName("guardrail: multi-statement (unclassifiable) SQL is a DESTRUCTIVE_SQL_WITHOUT_ALLOW error")
    void guardrail_multiStatementSql_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(dbStep("s1", "mainDb", "SELECT 1; DROP TABLE orders"))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code());
    }

    @Test
    @DisplayName("guardrail: a read-only db step on a whitelisted datasource passes")
    void guardrail_validDbRead_passes() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(dbStep("s1", "mainDb", "SELECT status FROM orders WHERE id = :id"))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("guardrail: an SQL sleep/side-effect time function is a THREAD_SLEEP error")
    void guardrail_sqlSleepFunction_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(dbStep("s1", "mainDb", "SELECT pg_sleep(30)"))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.THREAD_SLEEP.code());
    }

    @Test
    @DisplayName("guardrail: a secret-bearing header name is a SECRET_IN_SOURCE error")
    void guardrail_secretHeaderName_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(restStep("s1", Map.of("Authorization", "${authToken}")))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.SECRET_IN_SOURCE.code());
    }

    @Test
    @DisplayName("guardrail: a Bearer/Basic-shaped header value under an innocuous name is a SECRET_IN_SOURCE error")
    void guardrail_secretHeaderValue_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(restStep("s1", Map.of("X-Custom", "Bearer sk-abc123")))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.SECRET_IN_SOURCE.code());
    }

    @Test
    @DisplayName("guardrail: benign headers pass")
    void guardrail_benignHeaders_pass() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(restStep("s1", Map.of("Accept", "application/json", "X-Request-Source", "stand-test")))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("guardrail: a timeout above the 1-hour bound is an UNBOUNDED_TIMEOUT error")
    void guardrail_timeoutAboveBound_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(timedStep("s1", StepParameterKeys.TIMEOUT_MILLIS, DefaultScenarioValidator.MAX_TIMEOUT_MILLIS + 1))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.UNBOUNDED_TIMEOUT.code());
    }

    @Test
    @DisplayName("guardrail: a non-integer numeric deadline (Double) is an UNBOUNDED_TIMEOUT error")
    void guardrail_floatingPointDeadline_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(timedStep("s1", StepParameterKeys.DEADLINE_MILLIS, 1e30))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.UNBOUNDED_TIMEOUT.code());
    }

    @Test
    @DisplayName("guardrail: a bounded whole-number timeout passes")
    void guardrail_boundedTimeout_passes() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(timedStep("s1", StepParameterKeys.TIMEOUT_MILLIS, 30_000L))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("guardrail: value-level issues are reported even when the environment is not whitelisted")
    void guardrail_valueChecksRun_whenEnvironmentUnknown() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("prod")
                .step(restStep("s1", Map.of("Authorization", "x")))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(
                        ForbiddenOperation.NON_WHITELISTED_ENVIRONMENT.code(),
                        ForbiddenOperation.SECRET_IN_SOURCE.code());
    }

    @Test
    @DisplayName("guardrail: a non-whitelisted service/topic/grpc-target alias is rejected pre-flight")
    void guardrail_nonWhitelistedAliases_rejected() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(new GenericStep("s1", "rest.get", "", Map.of(StepParameterKeys.SERVICE, "unknown-service", StepParameterKeys.PATH, "/api")))
                .step(new GenericStep("s2", "kafka.expect", "", Map.of(StepParameterKeys.TOPIC, "unknown-topic", StepParameterKeys.TIMEOUT_MILLIS, 1_000)))
                .step(new GenericStep("s3", "grpc.unary", "", Map.of(StepParameterKeys.TARGET, "unknown-target", StepParameterKeys.METHOD_FULL_NAME, "pkg.Svc/M", StepParameterKeys.DEADLINE_MILLIS, 1_000)))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(
                        ForbiddenOperation.NON_WHITELISTED_SERVICE.code(),
                        ForbiddenOperation.NON_WHITELISTED_TOPIC.code(),
                        ForbiddenOperation.NON_WHITELISTED_GRPC_TARGET.code());
    }

    @Test
    @DisplayName("guardrail: whitelisted service/topic aliases pass the pre-flight whitelist")
    void guardrail_whitelistedAliases_pass() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(restStep("s1", Map.of("Accept", "application/json")))
                .step(timedStep("s2", StepParameterKeys.TIMEOUT_MILLIS, 30_000L))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("guardrail: a ui step on a non-whitelisted application is a NON_WHITELISTED_UI_APPLICATION error, raised pre-flight")
    void guardrail_unknownUiApplication_reportsForbiddenOp() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(uiStep("s1", "ui.open", "ghost-portal"))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.NON_WHITELISTED_UI_APPLICATION.code());
        assertThat(result.errors()).extracting(ValidationIssue::message)
                .anySatisfy(message -> assertThat(message).contains("UI application 'ghost-portal'").contains("ift"));
    }

    @Test
    @DisplayName("guardrail: a ui.login step must name a role once the application declares them — 'any account' is not expressible, and it is caught pre-flight")
    void guardrail_uiLoginWithoutRole_isRejected() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(loginStep("s1", "client-portal", null))
                .build();

        ValidationResult result = validator.validate(scenario, roleAwarePortalRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code).contains("UI_LOGIN_ROLE_REQUIRED");
        assertThat(result.errors()).extracting(ValidationIssue::message)
                .anySatisfy(message -> assertThat(message).contains("client-portal").contains("client", "manager"));
    }

    @Test
    @DisplayName("guardrail: a ui.login step naming a role the application does not declare is rejected pre-flight, listing the roles that exist")
    void guardrail_uiLoginWithUnknownRole_isRejected() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(loginStep("s1", "client-portal", "auditor"))
                .build();

        ValidationResult result = validator.validate(scenario, roleAwarePortalRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code).contains("UI_LOGIN_ROLE_UNKNOWN");
    }

    @Test
    @DisplayName("guardrail: a declared role passes, and an application declaring no roles needs none — the rule follows the registry, not the step")
    void guardrail_uiLoginRoleRules_areDrivenByTheRegistry() {
        Scenario declared = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(loginStep("s1", "client-portal", "manager"))
                .build();
        Scenario noRolesDeclared = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(loginStep("s1", "client-portal", null))
                .build();

        assertThat(validator.validate(declared, roleAwarePortalRegistry()).isValid()).isTrue();
        // The registry of mainDbRegistry() declares the same alias with no auth at all: no role is required.
        assertThat(validator.validate(noRolesDeclared, mainDbRegistry()).isValid()).isTrue();
    }

    @Test
    @DisplayName("guardrail: the account wait is a timeout like any other — beyond the SDK bound it is UNBOUNDED_TIMEOUT")
    void guardrail_accountTimeout_isBounded() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(new GenericStep("s1", StepParameterKeys.UI_LOGIN_TYPE, "", Map.of(
                        StepParameterKeys.APPLICATION, "client-portal",
                        StepParameterKeys.ROLE, "manager",
                        StepParameterKeys.ACCOUNT_TIMEOUT_MILLIS, DefaultScenarioValidator.MAX_TIMEOUT_MILLIS + 1)))
                .build();

        ValidationResult result = validator.validate(scenario, roleAwarePortalRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code).contains(ForbiddenOperation.UNBOUNDED_TIMEOUT.code());
        assertThat(result.errors()).extracting(ValidationIssue::message)
                .anySatisfy(message -> assertThat(message).contains(StepParameterKeys.ACCOUNT_TIMEOUT_MILLIS));
    }

    @Test
    @DisplayName("guardrail: a poll interval larger than the wait it belongs to is UNBOUNDED_TIMEOUT — each bound is legal alone, the pair is not")
    void guardrail_pollIntervalLargerThanTimeout_isRejected() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(new GenericStep("s1", "kafka.expect", "", Map.of(
                        StepParameterKeys.TOPIC, "response-topic",
                        StepParameterKeys.TIMEOUT_MILLIS, 500L,
                        StepParameterKeys.POLL_INTERVAL_MILLIS, 60_000L)))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code).contains(ForbiddenOperation.UNBOUNDED_TIMEOUT.code());
        assertThat(result.errors()).extracting(ValidationIssue::message)
                .anySatisfy(message -> assertThat(message).contains("polls every 60000 ms inside a wait of 500 ms"));
    }

    @Test
    @DisplayName("guardrail: an interval equal to or below the wait passes, and a step declaring only one of the two is not the pair rule's business")
    void guardrail_pollIntervalWithinTimeout_passes() {
        Scenario within = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(new GenericStep("s1", "kafka.expect", "", Map.of(
                        StepParameterKeys.TOPIC, "response-topic",
                        StepParameterKeys.TIMEOUT_MILLIS, 30_000L,
                        StepParameterKeys.POLL_INTERVAL_MILLIS, 30_000L)))
                .build();
        Scenario intervalOnly = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(new GenericStep("s1", "kafka.expect", "", Map.of(
                        StepParameterKeys.TOPIC, "response-topic",
                        StepParameterKeys.POLL_INTERVAL_MILLIS, 60_000L)))
                .build();

        assertThat(validator.validate(within, mainDbRegistry()).isValid()).isTrue();
        assertThat(validator.validate(intervalOnly, mainDbRegistry()).isValid()).isTrue();
    }

    @Test
    @DisplayName("guardrail: a whitelisted application alias passes, for every ui.* step type")
    void guardrail_whitelistedUiApplication_passes() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(uiStep("s1", "ui.open", "client-portal"))
                .step(uiStep("s2", "ui.click", "client-portal"))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.isValid()).isTrue();
    }

    /**
     * The whitelist is a dispatch on the step-type prefix, so a guardrail that is declared but wired to no
     * prefix passes every test that only ever asserts a violation. This pair pins the wiring itself: the
     * SAME parameters are rejected under {@code ui.} and ignored under a type no branch claims. Delete the
     * {@code ui.} branch and the first half fails; widen the dispatch to catch everything and the second
     * half fails.
     */
    @Test
    @DisplayName("guardrail: the ui.* alias check fires because of the ui. branch — the same parameters under an unclaimed type are not checked")
    void guardrail_uiApplicationCheck_isBoundToTheUiPrefix() {
        Scenario onUiPrefix = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(uiStep("s1", "ui.expect", "ghost-portal"))
                .build();
        Scenario onUnclaimedPrefix = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(uiStep("s1", "custom.expect", "ghost-portal"))
                .build();

        assertThat(validator.validate(onUiPrefix, mainDbRegistry()).errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.NON_WHITELISTED_UI_APPLICATION.code());
        assertThat(validator.validate(onUnclaimedPrefix, mainDbRegistry()).errors()).extracting(ValidationIssue::code)
                .doesNotContain(ForbiddenOperation.NON_WHITELISTED_UI_APPLICATION.code());
    }

    /**
     * The limit of the shared alias check, stated rather than left to be discovered: only a DECLARED alias
     * is whitelisted, so a step omitting it passes this stage. For rest/kafka/grpc/db the adapter's
     * parameter schema and its own re-resolution close the gap; for {@code ui.*} there is no adapter yet, so
     * this test records that the "alias is required" rule is owed by the UI step schema and is not silently
     * assumed to live here.
     */
    @Test
    @DisplayName("guardrail: a step that declares no alias at all is not flagged here — requiring the alias is the step schema's rule, not the whitelist's")
    void guardrail_missingAlias_isNotTheWhitelistsRule() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(GenericStep.of("s1", "ui.open"))
                .step(GenericStep.of("s2", "rest.get"))
                .build();

        ValidationResult result = validator.validate(scenario, mainDbRegistry());

        assertThat(result.errors()).extracting(ValidationIssue::code)
                .doesNotContain(
                        ForbiddenOperation.NON_WHITELISTED_UI_APPLICATION.code(),
                        ForbiddenOperation.NON_WHITELISTED_SERVICE.code());
    }

    @Test
    @DisplayName("the structural-only validate ignores the whitelist (no registry)")
    void structuralValidate_ignoresWhitelist() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("prod")
                .step(GenericStep.of("s1", "rest.post"))
                .build();

        ValidationResult result = validator.validate(scenario);

        assertThat(result.isValid()).isTrue();
        assertThat(result.errors()).extracting(ValidationIssue::code)
                .doesNotContain(ForbiddenOperation.NON_WHITELISTED_ENVIRONMENT.code());
    }

    @Test
    @DisplayName("AC-7: a direct RestStep.post(\"showcases\") in the test environment is refused before IO")
    void showcasesServiceIsNotWhitelistedOnTestEnvironment() {
        // The `test` environment has the real EQ gateway and deliberately no `showcases` alias: the
        // data-mart mock exists only on ift. A scenario that reaches for it must be refused here, before
        // any HTTP call, so a mock-seeded test cannot silently run against it.
        EnvironmentDefinition test = new EnvironmentDefinition("test", Map.of(), Map.of(), Map.of(), Map.of(),
                null, Map.of(), Map.of(), Map.of("eq-backends", new EnvironmentSection("eq-backends",
                        Map.of("eq", new SectionEntry("eq", Map.of("kind", "gateway"))))));
        EnvironmentRegistry registry = new InMemoryEnvironmentRegistry(Map.of("test", test));

        Scenario directSeeding = Scenario.builder("lgot-direct-showcases")
                .environment("test")
                .step(new GenericStep("seed", "rest.post", "", Map.of(StepParameterKeys.SERVICE, "showcases")))
                .build();

        ValidationResult result = validator.validate(directSeeding, registry);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).extracting(ValidationIssue::code)
                .contains(ForbiddenOperation.NON_WHITELISTED_SERVICE.code());
    }

    private static EnvironmentRegistry mainDbRegistry() {
        DatasourceDefinition datasource = new DatasourceDefinition(
                "mainDb", "MAIN_DB_URL", "MAIN_DB_USER", "MAIN_DB_PASSWORD", Set.of("public"), true);
        // Whitelist the aliases the rest/kafka helper steps use, so the pre-flight alias whitelist
        // (NON_WHITELISTED_SERVICE/TOPIC) does not flag them and the value-level guardrail assertions are
        // exercised in isolation.
        ServiceEndpointDefinition service = new ServiceEndpointDefinition("client-service", "CLIENT_SERVICE_URL", null, null);
        TopicDefinition topic = new TopicDefinition("response-topic", "response.topic.physical", null, null);
        UiApplicationDefinition application = new UiApplicationDefinition("client-portal", "CLIENT_PORTAL_URL");
        EnvironmentDefinition environment = new EnvironmentDefinition(
                "ift", Map.of("client-service", service), Map.of("response-topic", topic), Map.of("mainDb", datasource), Map.of(),
                null, Map.of(), Map.of("client-portal", application));
        return new InMemoryEnvironmentRegistry(Map.of("ift", environment));
    }

    private static GenericStep uiStep(String id, String type, String application) {
        return new GenericStep(id, type, "", Map.of(StepParameterKeys.APPLICATION, application));
    }

    /**
     * A registry whose one UI application declares the roles a scenario may request — the shape that makes
     * naming a role mandatory.
     */
    private static EnvironmentRegistry roleAwarePortalRegistry() {
        UiAuthConfig auth = new UiAuthConfig(
                UiAuthScheme.FORM,
                "CLIENT_PORTAL_ACCOUNTS",
                List.of("client", "manager"),
                null,
                new UiLoginFormConfig("/login", "testId=login-username", "testId=login-password", "role=button:Sign in", "testId=user-menu"),
                UiLoginChallenge.NONE);
        UiApplicationDefinition application = new UiApplicationDefinition(
                "client-portal", "CLIENT_PORTAL_URL", null, Map.of(), UiTraceMode.OFF, auth);
        EnvironmentDefinition environment = new EnvironmentDefinition(
                "ift", Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of("client-portal", application));
        return new InMemoryEnvironmentRegistry(Map.of("ift", environment));
    }

    private static GenericStep loginStep(String id, String application, String role) {
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put(StepParameterKeys.APPLICATION, application);
        if (role != null) {
            parameters.put(StepParameterKeys.ROLE, role);
        }
        return new GenericStep(id, StepParameterKeys.UI_LOGIN_TYPE, "", parameters);
    }

    private static GenericStep dbStep(String id, String datasource, String sql) {
        return new GenericStep(id, "db.query", "",
                Map.of(StepParameterKeys.DATASOURCE, datasource, StepParameterKeys.SQL, sql));
    }

    private static GenericStep restStep(String id, Map<String, String> headers) {
        return new GenericStep(id, "rest.get", "",
                Map.of(StepParameterKeys.SERVICE, "client-service", StepParameterKeys.PATH, "/api", StepParameterKeys.HEADERS, headers));
    }

    private static GenericStep timedStep(String id, String timeoutKey, Object timeoutValue) {
        return new GenericStep(id, "kafka.expect", "",
                Map.of(StepParameterKeys.TOPIC, "response-topic", timeoutKey, timeoutValue));
    }

    /** Test double that allows a blank type, which {@link GenericStep} would reject. */
    private record FakeStep(String id, String type) implements ScenarioStep {

        @Override
        public String description() {
            return "";
        }
    }
}
