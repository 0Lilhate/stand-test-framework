package ru.alfa.stand.test.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Bootstraps the H2 schema/table out-of-band. DDL is forbidden through the SDK write-guard (plan §8.8),
 * so the example creates its table directly via JDBC before any scenario runs. Idempotent, so every
 * example test class can call it; the in-memory database lives for the JVM via {@code DB_CLOSE_DELAY=-1},
 * and the connection values mirror the env refs the SDK resolves at run time.
 *
 * <p>{@code synchronized} because under the module's class-level parallel execution several classes call
 * this from {@code @BeforeAll} at once, and concurrent {@code CREATE SCHEMA/TABLE IF NOT EXISTS} on H2 is
 * not race-safe (it throws {@code JdbcSQLNonTransientException}). Serialising the idempotent bootstrap in
 * this in-JVM helper is enough — the SDK itself never issues DDL.
 */
final class ExampleH2 {

    private ExampleH2() {
    }

    static synchronized void createOrdersTable() {
        String url = System.getenv("MAIN_DB_URL");
        String user = System.getenv("MAIN_DB_USER");
        String password = System.getenv("MAIN_DB_PASSWORD");
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + ExampleStand.SCHEMA);
            statement.execute("CREATE TABLE IF NOT EXISTS " + ExampleStand.SCHEMA + ".orders ("
                    + "id VARCHAR(64) PRIMARY KEY, "
                    + "status VARCHAR(32), "
                    + "amount INTEGER, "
                    + "test_run_id VARCHAR(64))");
        } catch (SQLException failure) {
            throw new IllegalStateException("could not bootstrap the H2 example schema", failure);
        }
    }
}
