package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.compensation.CleanupPolicy;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.rest.RestStep;

/**
 * End-to-end example (prompt §6): a scenario seeds committed data into two datasource aliases via
 * {@code db.write} and then calls an external API that is unavailable. The API failure fails the run, so
 * the SDK's default {@link CleanupPolicy#ON_FAILURE} compensation removes both written rows by primary key
 * — no explicit cleanup step, no {@code test_run_id} marker column — while the original API failure remains
 * the test's primary failure.
 *
 * <p>Both datasource aliases resolve to the same in-memory H2 double (the module wires one
 * {@code MAIN_DB_URL} env ref); the two-physically-distinct-datasource undo is proven in the db module's
 * {@code DbWriteUndoTest}. No real API and no real database are used.
 */
class FailedApiRollbackExampleTest {

    private static final String SECOND_DATASOURCE = "secondDb";

    @BeforeAll
    static void bootstrapSchema() {
        ExampleH2.createOrdersTable();
    }

    @Test
    @DisplayName("API failure rolls back committed writes across two datasource aliases; the API failure stays primary")
    void apiFailure_rollsBackBothDatasources() throws SQLException {
        String idA = "rollback-a-" + UUID.randomUUID();
        String idB = "rollback-b-" + UUID.randomUUID();

        try (ExampleHttpServer unavailable = new ExampleHttpServer(503, "{\"error\":\"service unavailable\"}")) {
            StandClient stand = ExampleStand.stand(twoDatasourceRegistry(unavailable.baseUrl()));
            Scenario scenario = Scenario.builder("failed-api-rollback-example")
                    .environment(ExampleStand.ENVIRONMENT)
                    // default cleanupPolicy is ON_FAILURE; shown for clarity
                    .cleanupPolicy(CleanupPolicy.ON_FAILURE)
                    .step(DbStep.write(ExampleStand.DATASOURCE)
                            .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'PREPARED')")
                            .param("id", idA)
                            .identifiedBy("id")
                            .build())
                    .step(DbStep.write(SECOND_DATASOURCE)
                            .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'PREPARED')")
                            .param("id", idB)
                            .identifiedBy("id")
                            .build())
                    .step(RestStep.post(ExampleStand.SERVICE, "/api/requests")
                            .body("{\"amount\":1}")
                            .injectCorrelationId()
                            .expectStatus(200)
                            .build())
                    .build();

            // The API returns 503, so the expectStatus(200) step fails the run.
            assertThatThrownBy(() -> stand.run(scenario)).isInstanceOf(StandTestAssertionError.class);

            // Both committed rows were compensated (deleted by primary key) in the runner's finally.
            assertThat(rowExists(idA)).isFalse();
            assertThat(rowExists(idB)).isFalse();
        }
    }

    @Test
    @DisplayName("on a green run the default ON_FAILURE policy keeps the prepared data")
    void greenRun_keepsData() throws SQLException {
        String id = "kept-" + UUID.randomUUID();
        try (ExampleHttpServer ok = new ExampleHttpServer(200, "{\"ok\":true}")) {
            StandClient stand = ExampleStand.stand(twoDatasourceRegistry(ok.baseUrl()));
            Scenario scenario = Scenario.builder("green-keeps-data-example")
                    .environment(ExampleStand.ENVIRONMENT)
                    .step(DbStep.write(ExampleStand.DATASOURCE)
                            .sql("INSERT INTO test_data.orders(id, status) VALUES (:id, 'PREPARED')")
                            .param("id", id)
                            .identifiedBy("id")
                            .build())
                    .step(RestStep.post(ExampleStand.SERVICE, "/api/requests")
                            .body("{\"amount\":1}")
                            .injectCorrelationId()
                            .expectStatus(200)
                            .build())
                    .build();

            stand.run(scenario);

            // Green run + ON_FAILURE => the prepared row is kept for inspection.
            assertThat(rowExists(id)).isTrue();
        } finally {
            deleteRow(id);
        }
    }

    private static EnvironmentRegistry twoDatasourceRegistry(String restBaseUrl) {
        ServiceEndpointDefinition service = new ServiceEndpointDefinition(
                ExampleStand.SERVICE, restBaseUrl, new CorrelationConfig(CorrelationSource.HEADER, ExampleStand.CORRELATION_HEADER));
        // Both aliases resolve to the same in-memory H2 double via the module's MAIN_DB_* env refs.
        DatasourceDefinition dsA = new DatasourceDefinition(
                ExampleStand.DATASOURCE, "MAIN_DB_URL", "MAIN_DB_USER", "MAIN_DB_PASSWORD", Set.of(ExampleStand.SCHEMA), true);
        DatasourceDefinition dsB = new DatasourceDefinition(
                SECOND_DATASOURCE, "MAIN_DB_URL", "MAIN_DB_USER", "MAIN_DB_PASSWORD", Set.of(ExampleStand.SCHEMA), true);
        EnvironmentDefinition environment = new EnvironmentDefinition(
                ExampleStand.ENVIRONMENT, Map.of(ExampleStand.SERVICE, service), Map.of(),
                Map.of(ExampleStand.DATASOURCE, dsA, SECOND_DATASOURCE, dsB), Map.of());
        return new InMemoryEnvironmentRegistry(Map.of(ExampleStand.ENVIRONMENT, environment));
    }

    private static boolean rowExists(String id) throws SQLException {
        try (Connection connection = openDb();
                PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM test_data.orders WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void deleteRow(String id) throws SQLException {
        try (Connection connection = openDb();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM test_data.orders WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private static Connection openDb() throws SQLException {
        return DriverManager.getConnection(System.getenv("MAIN_DB_URL"), System.getenv("MAIN_DB_USER"), System.getenv("MAIN_DB_PASSWORD"));
    }
}
