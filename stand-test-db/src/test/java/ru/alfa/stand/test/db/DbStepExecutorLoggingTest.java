package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class DbStepExecutorLoggingTest {

    private final String url = DbTestSupport.uniqueUrl();
    private Connection keepAlive;
    private StepExecutionContext context;
    private DbStepExecutor executor;
    private Logger executorLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void setUp() throws SQLException {
        this.keepAlive = DbTestSupport.open(this.url);
        DbTestSupport.createOrdersTable(this.keepAlive);
        EnvironmentRegistry registry = DbTestSupport.registry(DbTestSupport.datasource(this.url, true, DbTestSupport.SCHEMA));
        this.context = DbTestSupport.context(registry, new VariableStore());
        this.executor = DbTestSupport.executor();
        this.executorLogger = (Logger) LoggerFactory.getLogger(DbStepExecutor.class);
        this.previousLevel = this.executorLogger.getLevel();
        this.executorLogger.setLevel(Level.DEBUG);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.executorLogger.addAppender(this.appender);
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.executorLogger.detachAppender(this.appender);
        this.executorLogger.setLevel(this.previousLevel);
        this.context.resourceScope().closeAll();
        this.keepAlive.close();
    }

    @Test
    @DisplayName("a db.seed emits DEBUG tracing with the classified operation, datasource alias and affected row count — and never the SQL, bound values or JDBC URL")
    void seedLogsMetadataOnly() {
        String secretId = "confidential-order-id-42";
        ScenarioStep seed = DbStep.seed(DbTestSupport.DATASOURCE_ALIAS)
                .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'CONFIDENTIAL_STATUS', :testRunId)")
                .taggedByTestRunId("test_run_id")
                .param("id", secretId)
                .build();

        StepResult result = this.executor.execute(seed, this.context);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        // A DEBUG line carries the classified operation keyword, the logical datasource alias and the affected-row count.
        assertThat(this.appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .contains("INSERT")
                    .contains(DbTestSupport.DATASOURCE_ALIAS)
                    .contains("1 row(s)");
        });
        // Metadata only: no captured line may leak the SQL text, the bound parameter value, a column literal or the JDBC URL.
        assertThat(this.appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(secretId)
                .doesNotContain("CONFIDENTIAL_STATUS")
                .doesNotContain("INSERT INTO")
                .doesNotContain(this.url));
    }
}
