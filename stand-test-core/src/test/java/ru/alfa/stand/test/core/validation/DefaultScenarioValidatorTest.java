package ru.alfa.stand.test.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

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

    private static EnvironmentRegistry mainDbRegistry() {
        DatasourceDefinition datasource = new DatasourceDefinition(
                "mainDb", "MAIN_DB_URL", "MAIN_DB_USER", "MAIN_DB_PASSWORD", Set.of("public"), true);
        EnvironmentDefinition environment = new EnvironmentDefinition(
                "ift", Map.of(), Map.of(), Map.of("mainDb", datasource), Map.of());
        return new InMemoryEnvironmentRegistry(Map.of("ift", environment));
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
