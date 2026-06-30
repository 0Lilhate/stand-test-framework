package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Connection-lifecycle tests for {@link DbStepExecutor} (plan §8.7): the run-scoped connection must be owned
 * by the {@code ResourceScope} the instant it is opened, so a later configuration failure cannot leak it. A
 * {@link FakeConnection} short-circuits real JDBC IO, so no H2 database is needed.
 */
class DbStepExecutorConnectionTest {

    private static final String KEY = "db.datasource:" + DbTestSupport.DATASOURCE_ALIAS;

    private static StepExecutionContext writableContext() {
        EnvironmentRegistry registry = DbTestSupport.registry(DbTestSupport.datasource("jdbc:unused", true, DbTestSupport.SCHEMA));
        return DbTestSupport.context(registry, new VariableStore());
    }

    private static ScenarioStep seed() {
        return DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, test_run_id) VALUES (:id, :testRunId)")
                .param("id", "x")
                .build();
    }

    @Test
    @DisplayName("a setAutoCommit failure still registers the connection in the run scope, so closeAll closes it exactly once (no leak)")
    void setAutoCommitFailureRegistersConnectionSoItGetsClosed() {
        FakeConnection fake = new FakeConnection(true);
        DbStepExecutor executor = new DbStepExecutor(DbTestSupport.PASSTHROUGH, resolved -> fake.connection(), Awaiter.create());
        StepExecutionContext context = writableContext();

        assertThatThrownBy(() -> executor.execute(seed(), context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to configure");

        assertThat(context.resourceScope().contains(KEY)).isTrue();
        context.resourceScope().closeAll();
        assertThat(fake.closeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an open failure propagates as an infrastructure error and registers nothing (nothing to leak)")
    void openFailurePropagatesAndRegistersNothing() {
        DbStepExecutor executor = new DbStepExecutor(DbTestSupport.PASSTHROUGH, resolved -> {
            throw new SQLException("cannot connect");
        }, Awaiter.create());
        StepExecutionContext context = writableContext();

        assertThatThrownBy(() -> executor.execute(seed(), context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to open");

        assertThat(context.resourceScope().contains(KEY)).isFalse();
    }

    @Test
    @DisplayName("a urlRef that resolves to a blank value is an infrastructure/config error, not an IllegalArgumentException")
    void blankResolvedUrlIsAConfigError() {
        DbStepExecutor executor = new DbStepExecutor(reference -> "", resolved -> {
            throw new SQLException("connection should not be opened when resolution fails");
        }, Awaiter.create());
        StepExecutionContext context = writableContext();

        assertThatThrownBy(() -> executor.execute(seed(), context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("urlRef")
                .hasMessageContaining("resolved to a blank value");
    }

    @Test
    @DisplayName("a userRef that resolves to a blank value is an infrastructure/config error")
    void blankResolvedUserIsAConfigError() {
        DatasourceDefinition datasource = new DatasourceDefinition(DbTestSupport.DATASOURCE_ALIAS, "URL_REF", "USER_REF", "PWD_REF", Set.of(DbTestSupport.SCHEMA), true);
        EnvironmentRegistry registry = DbTestSupport.registry(datasource);
        StepExecutionContext context = DbTestSupport.context(registry, new VariableStore());
        DbStepExecutor executor = new DbStepExecutor(reference -> "USER_REF".equals(reference) ? "" : "value", resolved -> {
            throw new SQLException("connection should not be opened when resolution fails");
        }, Awaiter.create());

        assertThatThrownBy(() -> executor.execute(seed(), context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("userRef")
                .hasMessageContaining("resolved to a blank value");
    }
}
