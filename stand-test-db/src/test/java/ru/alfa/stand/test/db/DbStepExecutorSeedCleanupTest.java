package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class DbStepExecutorSeedCleanupTest {

    private final String url = DbTestSupport.uniqueUrl();
    private Connection keepAlive;
    private VariableStore store;
    private StepExecutionContext context;
    private DbStepExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        this.keepAlive = DbTestSupport.open(this.url);
        DbTestSupport.createOrdersTable(this.keepAlive);
        this.store = new VariableStore();
        EnvironmentRegistry registry = DbTestSupport.registry(DbTestSupport.datasource(this.url, true, DbTestSupport.SCHEMA));
        this.context = DbTestSupport.context(registry, this.store);
        this.executor = DbTestSupport.executor();
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.context.resourceScope().closeAll();
        this.keepAlive.close();
    }

    private String testRunId() {
        return this.context.scenarioContext().testRunId().value();
    }

    private String testRunIdOf(String orderId) throws SQLException {
        try (PreparedStatement statement = this.keepAlive.prepareStatement("SELECT test_run_id FROM test_data.orders WHERE id = ?")) {
            statement.setString(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private void insertDirectly(String orderId, String runId) throws SQLException {
        try (PreparedStatement statement = this.keepAlive.prepareStatement("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (?, 'OLD', ?)")) {
            statement.setString(1, orderId);
            statement.setString(2, runId);
            statement.executeUpdate();
        }
    }

    @Test
    @DisplayName("seed inserts a row tagged with the run's testRunId via the reserved bind")
    void seedTagsWithTestRunId() throws SQLException {
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)")
                .param("id", "o-1")
                .build();

        StepResult result = this.executor.execute(seed, this.context);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.diagnostics()).containsEntry("db.operation", "seed").containsEntry("db.rowsAffected", 1);
        assertThat(testRunIdOf("o-1")).isEqualTo(testRunId());
    }

    @Test
    @DisplayName("seed can read its SQL from a classpath resource")
    void seedFromResource() throws SQLException {
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sqlFromResource("fixtures/seed-order.sql")
                .param("id", "o-res")
                .build();

        this.executor.execute(seed, this.context);

        assertThat(testRunIdOf("o-res")).isEqualTo(testRunId());
    }

    @Test
    @DisplayName("cleanup deletes only the run's own rows (testRunId predicate)")
    void cleanupDeletesOnlyRunRows() throws SQLException {
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)")
                .param("id", "mine")
                .build();
        this.executor.execute(seed, this.context);
        insertDirectly("foreign", "another-run");

        ScenarioStep cleanup = DbStep.cleanup(DbTestSupport.DATASOURCE_ALIAS)
                .sql("DELETE FROM test_data.orders")
                .whereTestRunId("test_run_id")
                .build();
        StepResult result = this.executor.execute(cleanup, this.context);

        assertThat(result.diagnostics()).containsEntry("db.operation", "cleanup").containsEntry("db.rowsAffected", 1);
        assertThat(testRunIdOf("mine")).isNull();
        assertThat(testRunIdOf("foreign")).isEqualTo("another-run");
    }

    @Test
    @DisplayName("a write to a read-only datasource is an infrastructure error and never touches the DB")
    void writeForbiddenWhenReadonly() throws SQLException {
        EnvironmentRegistry readonly = DbTestSupport.registry(DbTestSupport.datasource(this.url, false, DbTestSupport.SCHEMA));
        StepExecutionContext readonlyContext = DbTestSupport.context(readonly, new VariableStore());
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, test_run_id) VALUES (:id, :testRunId)")
                .param("id", "blocked")
                .build();

        assertThatThrownBy(() -> this.executor.execute(seed, readonlyContext))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        readonlyContext.resourceScope().closeAll();
        assertThat(testRunIdOf("blocked")).isNull();
    }

    @Test
    @DisplayName("destructive SQL on a seed step is rejected before any IO")
    void destructiveSqlRejected() throws SQLException {
        insertDirectly("survivor", testRunId());
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("TRUNCATE TABLE test_data.orders")
                .build();

        assertThatThrownBy(() -> this.executor.execute(seed, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Destructive");
        assertThat(testRunIdOf("survivor")).isEqualTo(testRunId());
    }

    @Test
    @DisplayName("a write to a non-whitelisted schema is rejected")
    void nonWhitelistedSchemaRejected() {
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO public.orders(id, test_run_id) VALUES (:id, :testRunId)")
                .param("id", "x")
                .build();

        assertThatThrownBy(() -> this.executor.execute(seed, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("allowedSchemas");
    }

    @Test
    @DisplayName("a step against a non-whitelisted datasource is an infrastructure error")
    void nonWhitelistedDatasource() {
        ScenarioStep seed = DbStep.seed("otherDb")
                .sql("INSERT INTO test_data.orders(id, test_run_id) VALUES (:id, :testRunId)")
                .param("id", "x")
                .build();

        assertThatThrownBy(() -> this.executor.execute(seed, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("NON_WHITELISTED_DATASOURCE");
    }

    private String statusOf(String orderId) throws SQLException {
        try (PreparedStatement statement = this.keepAlive.prepareStatement("SELECT status FROM test_data.orders WHERE id = ?")) {
            statement.setString(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    @Test
    @DisplayName("an UPDATE seed without the whereTestRunId marker is rejected and changes no rows (substring-bypass closed)")
    void seedUpdateWithoutMarkerRejected() throws SQLException {
        insertDirectly("u1", testRunId());
        // Mentions :testRunId only in SET — a textual reference that scopes nothing; must NOT pass the guard.
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("UPDATE test_data.orders SET status = :testRunId")
                .build();

        assertThatThrownBy(() -> this.executor.execute(seed, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("declared testRunId predicate");
        assertThat(statusOf("u1")).isEqualTo("OLD");
    }

    @Test
    @DisplayName("an INSERT upsert (ON CONFLICT DO UPDATE) seed is rejected fail-closed")
    void seedUpsertRejected() throws SQLException {
        insertDirectly("up1", testRunId());
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'NEW') ON CONFLICT (id) DO UPDATE SET status = 'HACKED'")
                .param("id", "up1")
                .build();

        assertThatThrownBy(() -> this.executor.execute(seed, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("rejected");
        assertThat(statusOf("up1")).isEqualTo("OLD");
    }

    @Test
    @DisplayName("the reserved :testRunId bind wins over an author-supplied param of the same name (per-run isolation)")
    void reservedTestRunIdBindCannotBeSpoofed() throws SQLException {
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)")
                .param("id", "mine")
                .build();
        this.executor.execute(seed, this.context);
        insertDirectly("victim", "victim-run");

        // The step tries to redirect the cleanup at another run by supplying a spoofed testRunId param.
        ScenarioStep cleanup = DbStep.cleanup(DbTestSupport.DATASOURCE_ALIAS)
                .sql("DELETE FROM test_data.orders")
                .param("testRunId", "victim-run")
                .whereTestRunId("test_run_id")
                .build();
        this.executor.execute(cleanup, this.context);

        // The reserved bind won: the run's OWN row is gone, the victim row is untouched.
        assertThat(testRunIdOf("mine")).isNull();
        assertThat(testRunIdOf("victim")).isEqualTo("victim-run");
    }

    @Test
    @DisplayName("a trailing line comment cannot neutralise the testRunId scoping — the cleanup stays scoped")
    void trailingLineCommentStaysScoped() throws SQLException {
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)")
                .param("id", "mine")
                .build();
        this.executor.execute(seed, this.context);
        insertDirectly("victim", "victim-run");

        // A trailing `--` would swallow a same-line appended WHERE; the SDK appends it on a fresh line.
        ScenarioStep cleanup = DbStep.cleanup(DbTestSupport.DATASOURCE_ALIAS)
                .sql("DELETE FROM test_data.orders --")
                .whereTestRunId("test_run_id")
                .build();
        this.executor.execute(cleanup, this.context);

        assertThat(testRunIdOf("mine")).isNull();
        assertThat(testRunIdOf("victim")).isEqualTo("victim-run");
    }

    @Test
    @DisplayName("a trailing unterminated block comment that swallows the appended WHERE is refused, deleting nothing")
    void trailingBlockCommentRefused() throws SQLException {
        insertDirectly("survivor", testRunId());
        ScenarioStep cleanup = DbStep.cleanup(DbTestSupport.DATASOURCE_ALIAS)
                .sql("DELETE FROM test_data.orders /*")
                .whereTestRunId("test_run_id")
                .build();

        assertThatThrownBy(() -> this.executor.execute(cleanup, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("neutralised");
        assertThat(testRunIdOf("survivor")).isEqualTo(testRunId());
    }

    @Test
    @DisplayName("a step using whereTestRunId must not carry its own WHERE clause")
    void whereTestRunIdMutualExclusion() {
        ScenarioStep cleanup = DbStep.cleanup(DbTestSupport.DATASOURCE_ALIAS)
                .sql("DELETE FROM test_data.orders WHERE status = 'OLD'")
                .whereTestRunId("test_run_id")
                .build();

        assertThatThrownBy(() -> this.executor.execute(cleanup, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("must not carry its own WHERE");
    }

    @Test
    @DisplayName("a seed from a missing classpath resource is an infrastructure error")
    void seedFromMissingResourceFails() {
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS).sqlFromResource("fixtures/does-not-exist.sql").build();

        assertThatThrownBy(() -> this.executor.execute(seed, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("a seed from an empty classpath resource is an infrastructure error")
    void seedFromEmptyResourceFails() {
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS).sqlFromResource("fixtures/empty.sql").build();

        assertThatThrownBy(() -> this.executor.execute(seed, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("is empty");
    }

    @Test
    @DisplayName("a parameter map carrying both sql and sqlResource (bypassing the builder, as YAML could) is rejected")
    void bothSqlAndResourceInParamMapRejected() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(DbStepParameters.DATASOURCE, DbTestSupport.DATASOURCE_ALIAS);
        params.put(DbStepParameters.SQL, "INSERT INTO test_data.orders(id) VALUES (:id)");
        params.put(DbStepParameters.SQL_RESOURCE, "fixtures/seed-order.sql");
        ScenarioStep step = new GenericStep("seed-both", "db.seed", "seed", params);

        assertThatThrownBy(() -> this.executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not both");
    }

    @Test
    @DisplayName("a parameter map carrying neither sql nor sqlResource is rejected")
    void neitherSqlNorResourceInParamMapRejected() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(DbStepParameters.DATASOURCE, DbTestSupport.DATASOURCE_ALIAS);
        ScenarioStep step = new GenericStep("seed-neither", "db.seed", "seed", params);

        assertThatThrownBy(() -> this.executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("requires");
    }

    @Test
    @DisplayName("a bare-CR line comment hiding a DELETE is refused by the guard before any IO, deleting nothing (DBSEC-1)")
    void crLineCommentBypassRefusedBeforeIo() throws SQLException {
        insertDirectly("survivor", testRunId());
        // The WITH-CTE DELETE is hidden behind a `--c` comment terminated by a lone '\r'; on PostgreSQL the comment
        // ends at the CR and the unscoped DELETE would run. The classifier must reject it as a non-read, so the
        // write-guard refuses it before any IO rather than dispatching it to executeUpdate.
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("WITH x AS (SELECT 1) --c\rDELETE FROM test_data.orders")
                .build();

        assertThatThrownBy(() -> this.executor.execute(seed, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("rejected (fail-closed");
        assertThat(testRunIdOf("survivor")).isEqualTo(testRunId());
    }
}
