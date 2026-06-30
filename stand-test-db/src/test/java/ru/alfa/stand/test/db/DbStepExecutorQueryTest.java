package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class DbStepExecutorQueryTest {

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
        EnvironmentRegistry registry = DbTestSupport.registry(DbTestSupport.datasource(this.url, false, DbTestSupport.SCHEMA));
        this.context = DbTestSupport.context(registry, this.store);
        this.executor = DbTestSupport.executor();
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.context.resourceScope().closeAll();
        this.keepAlive.close();
    }

    private void insert(String id, String status, Integer amount) throws SQLException {
        try (PreparedStatement statement = this.keepAlive.prepareStatement("INSERT INTO test_data.orders(id, status, amount, test_run_id) VALUES (?, ?, ?, 'r')")) {
            statement.setString(1, id);
            statement.setString(2, status);
            if (amount == null) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setInt(3, amount);
            }
            statement.executeUpdate();
        }
    }

    @Test
    @DisplayName("query captures column values (type-preserving) into the variable store, resolving ${...} binds")
    void capturesColumns() throws SQLException {
        insert("q1", "READY", 42);
        this.store.put("wanted", "q1");
        ScenarioStep step = DbStep.query(DbTestSupport.DATASOURCE_ALIAS)
                .sql("SELECT status, amount FROM test_data.orders WHERE id = :id")
                .param("id", "${wanted}")
                .capture("status", "status")
                .capture("orderAmount", "amount")
                .build();

        StepResult result = this.executor.execute(step, this.context);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.diagnostics()).containsEntry("db.operation", "query");
        assertThat(this.store.get("status")).contains("READY");
        assertThat(this.store.get("orderAmount")).contains(42);
    }

    @Test
    @DisplayName("a query whose captures hit no rows is an infrastructure error")
    void captureNoRowsFails() {
        ScenarioStep step = DbStep.query(DbTestSupport.DATASOURCE_ALIAS)
                .sql("SELECT status FROM test_data.orders WHERE id = :id")
                .param("id", "missing")
                .capture("status", "status")
                .build();

        assertThatThrownBy(() -> this.executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("returned no rows");
    }

    @Test
    @DisplayName("capturing a column whose value is null is an infrastructure error")
    void captureNullFails() throws SQLException {
        insert("q2", null, null);
        ScenarioStep step = DbStep.query(DbTestSupport.DATASOURCE_ALIAS)
                .sql("SELECT status FROM test_data.orders WHERE id = :id")
                .param("id", "q2")
                .capture("status", "status")
                .build();

        assertThatThrownBy(() -> this.executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("is null");
    }

    @Test
    @DisplayName("a write disguised as a query is rejected by the guard")
    void writeAsQueryRejected() {
        ScenarioStep step = DbStep.query(DbTestSupport.DATASOURCE_ALIAS)
                .sql("DELETE FROM test_data.orders WHERE id = :id")
                .param("id", "x")
                .build();

        assertThatThrownBy(() -> this.executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("only allowed on db.seed/db.cleanup");
    }

    @Test
    @DisplayName("a query reusing the run-scoped connection sees a previous step's committed write")
    void reusesRunScopedConnection() throws SQLException {
        insert("q3", "READY", 7);
        ScenarioStep first = DbStep.query(DbTestSupport.DATASOURCE_ALIAS).id("q-a").sql("SELECT status FROM test_data.orders WHERE id = :id").param("id", "q3").capture("s1", "status").build();
        ScenarioStep second = DbStep.query(DbTestSupport.DATASOURCE_ALIAS).id("q-b").sql("SELECT amount FROM test_data.orders WHERE id = :id").param("id", "q3").capture("a1", "amount").build();

        this.executor.execute(first, this.context);
        this.executor.execute(second, this.context);

        assertThat(this.store.get("s1")).contains("READY");
        assertThat(this.store.get("a1")).contains(7);
        // One connection registered for the datasource alias, shared by both steps.
        assertThat(this.context.resourceScope().contains("db.datasource:" + DbTestSupport.DATASOURCE_ALIAS)).isTrue();
    }
}
