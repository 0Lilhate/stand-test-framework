package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.await.DefaultAwaiter;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class DbStepExecutorExpectTest {

    private final String url = DbTestSupport.uniqueUrl();
    private Connection keepAlive;
    private StepExecutionContext context;

    @BeforeEach
    void setUp() throws SQLException {
        this.keepAlive = DbTestSupport.open(this.url);
        DbTestSupport.createOrdersTable(this.keepAlive);
        EnvironmentRegistry registry = DbTestSupport.registry(DbTestSupport.datasource(this.url, false, DbTestSupport.SCHEMA));
        this.context = DbTestSupport.context(registry, new VariableStore());
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.context.resourceScope().closeAll();
        this.keepAlive.close();
    }

    private void insertStatus(Connection connection, String id, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (?, ?, 'r')")) {
            statement.setString(1, id);
            statement.setString(2, status);
            statement.executeUpdate();
        }
    }

    private ScenarioStep expectStatus(String id, Object expected) {
        return DbStep.expectEventually(DbTestSupport.DATASOURCE_ALIAS)
                .sql("SELECT status FROM test_data.orders WHERE id = :id")
                .param("id", id)
                .expectValue(expected)
                .within(Duration.ofMillis(100))
                .build();
    }

    @Test
    @DisplayName("expectEventually succeeds immediately when the value already matches")
    void immediateMatch() throws SQLException {
        insertStatus(this.keepAlive, "e1", "DONE");
        DbStepExecutor executor = DbTestSupport.executor();

        StepResult result = executor.execute(expectStatus("e1", "DONE"), this.context);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.diagnostics()).containsEntry("db.operation", "expectEventually").containsEntry("db.value", "DONE");
    }

    @Test
    @DisplayName("expectEventually polls until a concurrently-written row appears")
    void eventualMatch() throws Exception {
        DbStepExecutor executor = DbTestSupport.executor();
        ScenarioStep step = DbStep.expectEventually(DbTestSupport.DATASOURCE_ALIAS)
                .sql("SELECT status FROM test_data.orders WHERE id = :id")
                .param("id", "e2")
                .expectValue("DONE")
                .within(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .build();
        Thread writer = new Thread(() -> {
            try (Connection connection = DbTestSupport.open(this.url)) {
                Thread.sleep(60);
                insertStatus(connection, "e2", "DONE");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        writer.start();
        try {
            StepResult result = executor.execute(step, this.context);
            assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        } finally {
            writer.join();
        }
    }

    @Test
    @DisplayName("a never-present row times out as a JUnit-native assertion error")
    void timeoutWhenNeverPresent() {
        DbStepExecutor executor = DbTestSupport.executor(new DefaultAwaiter(new FakeTimeSource()));

        assertThatThrownBy(() -> executor.execute(expectStatus("missing", "DONE"), this.context))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("did not observe the expected value")
                .hasMessageContaining("lastObserved=<no rows>");
    }

    @Test
    @DisplayName("a value that never matches times out, reporting the last observed value")
    void timeoutWhenValueNeverMatches() throws SQLException {
        insertStatus(this.keepAlive, "e3", "PENDING");
        DbStepExecutor executor = DbTestSupport.executor(new DefaultAwaiter(new FakeTimeSource()));

        assertThatThrownBy(() -> executor.execute(expectStatus("e3", "DONE"), this.context))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("lastObserved=PENDING")
                .hasMessageContaining("expected=DONE");
    }

    @Test
    @DisplayName("a SELECT returning more than one row is an ambiguous infrastructure error")
    void ambiguousMultipleRows() throws SQLException {
        insertStatus(this.keepAlive, "m1", "X");
        insertStatus(this.keepAlive, "m2", "X");
        DbStepExecutor executor = DbTestSupport.executor();
        ScenarioStep step = DbStep.expectEventually(DbTestSupport.DATASOURCE_ALIAS)
                .sql("SELECT status FROM test_data.orders WHERE status = :s")
                .param("s", "X")
                .expectValue("X")
                .within(Duration.ofMillis(100))
                .build();

        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    @DisplayName("the value comparison is type-aware: an int expected value matches a BIGINT count")
    void typeAwareNumericMatch() throws SQLException {
        insertStatus(this.keepAlive, "c1", "READY");
        DbStepExecutor executor = DbTestSupport.executor();
        ScenarioStep step = DbStep.expectEventually(DbTestSupport.DATASOURCE_ALIAS)
                .sql("SELECT count(*) FROM test_data.orders WHERE status = :s")
                .param("s", "READY")
                .expectValue(1)
                .within(Duration.ofMillis(100))
                .build();

        assertThat(executor.execute(step, this.context).status()).isEqualTo(StepStatus.SUCCESS);
    }

    @Test
    @DisplayName("a SQLException during polling fails fast as an infrastructure error (expect does not retry SQL errors)")
    void probeSqlErrorFailsFast() {
        DbStepExecutor executor = DbTestSupport.executor();
        ScenarioStep step = DbStep.expectEventually(DbTestSupport.DATASOURCE_ALIAS)
                .sql("SELECT status FROM test_data.missing_table WHERE id = :id")
                .param("id", "x")
                .expectValue("DONE")
                .within(Duration.ofSeconds(5))
                .build();

        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("query failed");
    }

    @Test
    @DisplayName("a row whose selected column is SQL NULL never matches and times out reporting <null>")
    void nullColumnNeverMatchesAndTimesOut() throws SQLException {
        insertStatus(this.keepAlive, "n1", null);
        DbStepExecutor executor = DbTestSupport.executor(new DefaultAwaiter(new FakeTimeSource()));

        assertThatThrownBy(() -> executor.execute(expectStatus("n1", "DONE"), this.context))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("lastObserved=<null>");
    }
}
