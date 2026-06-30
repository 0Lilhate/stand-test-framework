package ru.alfa.stand.test.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Shared fixtures for the DB adapter tests: a unique H2 in-memory database per test (the broker-free
 * analog of the Kafka {@code MockConsumer} / REST {@code HttpServer}, plan §16), a whitelisted
 * environment with one datasource, a passthrough {@link ReferenceResolver} (so a registry whose refs are
 * literal H2 connection values resolves directly, mirroring the REST {@code liveExecutor}) and a
 * ready-made {@link StepExecutionContext}.
 */
final class DbTestSupport {

    static final String ENVIRONMENT = "ift";
    static final String DATASOURCE_ALIAS = "mainDb";
    static final String USER = "sa";
    static final String PASSWORD = "sa";
    static final String SCHEMA = "test_data";
    static final ReferenceResolver PASSTHROUGH = reference -> reference;

    private DbTestSupport() {
    }

    /**
     * A unique H2 in-memory URL. {@code DB_CLOSE_DELAY=-1} keeps the database alive between connections
     * (the executor's run-scoped connection is separate from the test's setup connection); PostgreSQL
     * mode mirrors the real stands.
     */
    static String uniqueUrl() {
        return "jdbc:h2:mem:standdb-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    }

    static Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, USER, PASSWORD);
    }

    static void createOrdersTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            statement.execute("CREATE TABLE " + SCHEMA + ".orders ("
                    + "id VARCHAR(64) PRIMARY KEY, "
                    + "status VARCHAR(32), "
                    + "amount INTEGER, "
                    + "test_run_id VARCHAR(64))");
        }
    }

    static DatasourceDefinition datasource(String url, boolean writeAllowed, String... allowedSchemas) {
        return new DatasourceDefinition(DATASOURCE_ALIAS, url, USER, PASSWORD, Set.of(allowedSchemas), writeAllowed);
    }

    static EnvironmentRegistry registry(DatasourceDefinition datasource) {
        EnvironmentDefinition environment =
                new EnvironmentDefinition(ENVIRONMENT, Map.of(), Map.of(), Map.of(datasource.alias(), datasource), Map.of());
        return new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
    }

    static StepExecutionContext context(EnvironmentRegistry registry, VariableStore store) {
        ScenarioContext scenarioContext = ScenarioContext.start(ScenarioId.of("scenario-db"), ENVIRONMENT);
        return new StepExecutionContext(scenarioContext, store, registry, NoOpReportingEventPublisher.INSTANCE);
    }

    static DbStepExecutor executor() {
        return executor(Awaiter.create());
    }

    static DbStepExecutor executor(Awaiter awaiter) {
        return new DbStepExecutor(PASSTHROUGH, new DriverManagerConnectionFactory(), awaiter);
    }
}
