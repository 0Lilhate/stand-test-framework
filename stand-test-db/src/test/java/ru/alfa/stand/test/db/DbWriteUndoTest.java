package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.compensation.CompensationOutcome;
import ru.alfa.stand.test.core.compensation.CompensationStatus;
import ru.alfa.stand.test.core.compensation.Compensator;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

@DisplayName("db.write undo-log (INSERT captured, compensated by primary key)")
class DbWriteUndoTest {

    private final String url = DbTestSupport.uniqueUrl();
    private Connection keepAlive;
    private StepExecutionContext context;
    private DbStepExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        this.keepAlive = DbTestSupport.open(this.url);
        DbTestSupport.createOrdersTable(this.keepAlive);
        EnvironmentRegistry registry = DbTestSupport.registry(DbTestSupport.datasource(this.url, true, DbTestSupport.SCHEMA));
        this.context = DbTestSupport.context(registry, new VariableStore());
        this.executor = DbTestSupport.executor();
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.context.resourceScope().closeAll();
        this.keepAlive.close();
    }

    private boolean exists(String orderId) throws SQLException {
        try (PreparedStatement statement = this.keepAlive.prepareStatement("SELECT 1 FROM test_data.orders WHERE id = ?")) {
            statement.setString(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private List<CompensationOutcome> drain() {
        return this.context.undoLog().inReverseOrder().stream().map(Compensator::compensate).toList();
    }

    @Test
    @DisplayName("db.write inserts a committed row and registers one undo action")
    void write_insertsAndRegistersUndo() throws SQLException {
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'ACTIVE')")
                .param("id", "w-1")
                .identifiedBy("id")
                .build();

        StepResult result = this.executor.execute(write, this.context);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.diagnostics()).containsEntry("db.operation", "write").containsEntry("db.undoRegistered", true);
        assertThat(exists("w-1")).isTrue();
        assertThat(this.context.undoLog().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("draining the undo-log deletes the written row by primary key")
    void undo_deletesRow() throws SQLException {
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'ACTIVE')")
                .param("id", "w-2")
                .identifiedBy("id")
                .build();
        this.executor.execute(write, this.context);
        assertThat(exists("w-2")).isTrue();

        List<CompensationOutcome> outcomes = drain();

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).status()).isEqualTo(CompensationStatus.APPLIED);
        assertThat(outcomes.get(0).affectedRows()).isEqualTo(1);
        assertThat(outcomes.get(0).diagnostics()).containsEntry("db.table", "test_data.orders");
        assertThat(exists("w-2")).isFalse();
    }

    @Test
    @DisplayName("undo does not touch a foreign row with a different primary key")
    void undo_onlyOwnRow() throws SQLException {
        try (PreparedStatement insert = this.keepAlive.prepareStatement("INSERT INTO test_data.orders(id, status) VALUES ('foreign', 'KEEP')")) {
            insert.executeUpdate();
        }
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'ACTIVE')")
                .param("id", "mine")
                .identifiedBy("id")
                .build();
        this.executor.execute(write, this.context);

        drain();

        assertThat(exists("mine")).isFalse();
        assertThat(exists("foreign")).isTrue();
    }

    @Test
    @DisplayName("undo is idempotent: a second drain over an already-removed row is SKIPPED")
    void undo_idempotent() throws SQLException {
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'ACTIVE')")
                .param("id", "w-3")
                .identifiedBy("id")
                .build();
        this.executor.execute(write, this.context);

        Compensator compensator = this.context.undoLog().inReverseOrder().get(0);
        assertThat(compensator.compensate().status()).isEqualTo(CompensationStatus.APPLIED);
        assertThat(compensator.compensate().status()).isEqualTo(CompensationStatus.SKIPPED);
        assertThat(exists("w-3")).isFalse();
    }

    @Test
    @DisplayName("db.write requires identifiedBy(...) at build time")
    void write_requiresIdentifiedBy() {
        assertThatThrownBy(() -> DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'ACTIVE')")
                .param("id", "x")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identifiedBy");
    }

    @Test
    @DisplayName("db.write fails closed when the primary-key value is not bound as :<column>")
    void write_pkValueMustBeBound() {
        // identifiedBy references 'id', but the INSERT provides id as a literal, not a :id bind — cannot capture the PK.
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES ('literal-id', 'ACTIVE')")
                .identifiedBy("id")
                .build();

        assertThatThrownBy(() -> this.executor.execute(write, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("cannot capture its primary key");
    }

    @Test
    @DisplayName("db.write rejects an UPDATE (MVP supports INSERT only)")
    void write_rejectsUpdate() throws SQLException {
        try (PreparedStatement insert = this.keepAlive.prepareStatement("INSERT INTO test_data.orders(id, status) VALUES ('u1', 'OLD')")) {
            insert.executeUpdate();
        }
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("UPDATE test_data.orders SET status = 'NEW' WHERE id = :id")
                .param("id", "u1")
                .identifiedBy("id")
                .build();

        assertThatThrownBy(() -> this.executor.execute(write, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("INSERT only");
    }

    @Test
    @DisplayName("db.write to a read-only datasource is refused before any IO")
    void write_readOnlyDatasourceRefused() throws SQLException {
        EnvironmentRegistry readonly = DbTestSupport.registry(DbTestSupport.datasource(this.url, false, DbTestSupport.SCHEMA));
        StepExecutionContext readonlyContext = DbTestSupport.context(readonly, new VariableStore());
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'ACTIVE')")
                .param("id", "blocked")
                .identifiedBy("id")
                .build();

        assertThatThrownBy(() -> this.executor.execute(write, readonlyContext))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("read-only");
        readonlyContext.resourceScope().closeAll();
        assertThat(exists("blocked")).isFalse();
    }

    @Test
    @DisplayName("db.write rejects a multi-row VALUES INSERT (its full row set is not undoable)")
    void write_rejectsMultiRowValues() throws SQLException {
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'A'), ('second', 'B')")
                .param("id", "multi-1")
                .identifiedBy("id")
                .build();

        assertThatThrownBy(() -> this.executor.execute(write, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("single-row INSERT");
        assertThat(exists("multi-1")).isFalse();
        assertThat(exists("second")).isFalse();
    }

    @Test
    @DisplayName("db.write rejects an INSERT ... SELECT (unknown written row set)")
    void write_rejectsInsertSelect() throws SQLException {
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) SELECT :id, 'A'")
                .param("id", "sel-1")
                .identifiedBy("id")
                .build();

        assertThatThrownBy(() -> this.executor.execute(write, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("single-row INSERT");
        assertThat(exists("sel-1")).isFalse();
    }

    @Test
    @DisplayName("db.write rejects whereTestRunId(...) at build time (db.write is undone by primary key)")
    void write_rejectsWhereTestRunId() {
        assertThatThrownBy(() -> DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'A')")
                .param("id", "x")
                .identifiedBy("id")
                .whereTestRunId("test_run_id")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereTestRunId");
    }

    @Test
    @DisplayName("a raw identifiedBy value that is not a plain identifier is refused fail-closed (SQL-injection defense on the wire path)")
    void write_rawIdentifiedByInjectionRefused() {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put(DbStepParameters.DATASOURCE, DbTestSupport.DATASOURCE_ALIAS);
        params.put(DbStepParameters.SQL, "INSERT INTO test_data.orders(id, status) VALUES (:id, 'A')");
        params.put(DbStepParameters.PARAMS, Map.of("id", "x"));
        params.put(DbStepParameters.IDENTIFIED_BY, List.of("id = '1' OR 1=1 --"));
        ScenarioStep raw = new ru.alfa.stand.test.core.scenario.GenericStep("raw-write", "db.write", "", params);

        assertThatThrownBy(() -> this.executor.execute(raw, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("plain identifier");
    }

    @Test
    @DisplayName("db.write fails closed when the identifiedBy column is not in the INSERT column list")
    void write_pkColumnMustBeInserted() {
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'A')")
                .param("id", "x")
                .param("amount", 5)
                .identifiedBy("amount")
                .build();

        assertThatThrownBy(() -> this.executor.execute(write, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not in the INSERT column list");
    }

    @Test
    @DisplayName("an undo that would delete more than one row (non-unique identifiedBy) is FAILED, not a silent APPLIED, and deletes nothing extra")
    void undo_nonUniqueKey_isFailed() throws SQLException {
        try (java.sql.Statement ddl = this.keepAlive.createStatement()) {
            ddl.execute("CREATE TABLE test_data.dupes (k VARCHAR(64), v VARCHAR(64))");
        }
        try (PreparedStatement insert = this.keepAlive.prepareStatement("INSERT INTO test_data.dupes(k, v) VALUES ('x', 'one'), ('x', 'two')")) {
            insert.executeUpdate();
        }
        DatasourceDefinition datasource = DbTestSupport.datasource(this.url, true, DbTestSupport.SCHEMA);
        RunScopedConnection connection = new RunScopedConnection(this.keepAlive, DbTestSupport.DATASOURCE_ALIAS);
        DbCompensator compensator = new DbCompensator(
                "act", DbTestSupport.DATASOURCE_ALIAS, connection, datasource, "test_data.dupes", List.of("k"), Map.of("k", "x"));

        CompensationOutcome outcome = compensator.compensate();

        assertThat(outcome.status()).isEqualTo(CompensationStatus.FAILED);
        assertThat(outcome.message()).contains("not unique");
        // both rows survive: a non-unique undo must not delete foreign rows
        try (PreparedStatement count = this.keepAlive.prepareStatement("SELECT COUNT(*) FROM test_data.dupes WHERE k = 'x'");
                ResultSet rows = count.executeQuery()) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a compensation whose table has vanished is reported FAILED, never throwing")
    void undo_sqlError_isFailedNotThrown() throws SQLException {
        ScenarioStep write = DbStep.write(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'A')")
                .param("id", "vanish")
                .identifiedBy("id")
                .build();
        this.executor.execute(write, this.context);
        Compensator compensator = this.context.undoLog().inReverseOrder().get(0);
        try (java.sql.Statement ddl = this.keepAlive.createStatement()) {
            ddl.execute("DROP TABLE test_data.orders");
        }

        CompensationOutcome outcome = compensator.compensate();

        assertThat(outcome.status()).isEqualTo(CompensationStatus.FAILED);
        assertThat(outcome.cause()).isNotNull();
    }

    @Test
    @DisplayName("multi-datasource: writes to A and B each register an undo; draining removes both")
    void multiDatasource_bothUndone() throws SQLException {
        String urlB = DbTestSupport.uniqueUrl();
        try (Connection keepAliveB = DbTestSupport.open(urlB)) {
            DbTestSupport.createOrdersTable(keepAliveB);
            EnvironmentRegistry twoDs = twoDatasourceRegistry(this.url, urlB);
            StepExecutionContext ctx = DbTestSupport.context(twoDs, new VariableStore());

            ScenarioStep writeA = DbStep.write("dsA")
                    .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'A')")
                    .param("id", "a-1")
                    .identifiedBy("id")
                    .build();
            ScenarioStep writeB = DbStep.write("dsB")
                    .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'B')")
                    .param("id", "b-1")
                    .identifiedBy("id")
                    .build();
            this.executor.execute(writeA, ctx);
            this.executor.execute(writeB, ctx);

            assertThat(exists("a-1")).isTrue();
            assertThat(existsIn(keepAliveB, "b-1")).isTrue();

            List<CompensationOutcome> outcomes = ctx.undoLog().inReverseOrder().stream().map(Compensator::compensate).toList();
            assertThat(outcomes).extracting(CompensationOutcome::status).containsExactly(CompensationStatus.APPLIED, CompensationStatus.APPLIED);
            // reverse order: B (last registered) first, then A
            assertThat(outcomes).extracting(CompensationOutcome::target).containsExactly("dsB", "dsA");

            assertThat(exists("a-1")).isFalse();
            assertThat(existsIn(keepAliveB, "b-1")).isFalse();
            ctx.resourceScope().closeAll();
        }
    }

    private static boolean existsIn(Connection connection, String orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM test_data.orders WHERE id = ?")) {
            statement.setString(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static EnvironmentRegistry twoDatasourceRegistry(String urlA, String urlB) {
        DatasourceDefinition dsA = new DatasourceDefinition("dsA", urlA, DbTestSupport.USER, DbTestSupport.PASSWORD, java.util.Set.of(DbTestSupport.SCHEMA), true);
        DatasourceDefinition dsB = new DatasourceDefinition("dsB", urlB, DbTestSupport.USER, DbTestSupport.PASSWORD, java.util.Set.of(DbTestSupport.SCHEMA), true);
        EnvironmentDefinition environment = new EnvironmentDefinition(
                DbTestSupport.ENVIRONMENT, Map.of(), Map.of(), Map.of("dsA", dsA, "dsB", dsB), Map.of());
        return new InMemoryEnvironmentRegistry(Map.of(DbTestSupport.ENVIRONMENT, environment));
    }
}
