package ru.alfa.stand.test.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens a JDBC {@link Connection} for a resolved datasource.
 *
 * <p>This is the single seam where the adapter touches a real JDBC driver. The default
 * {@link DriverManagerConnectionFactory} goes through {@link java.sql.DriverManager}; tests inject a
 * factory that returns an H2 in-memory connection (the broker-free analog of Kafka's {@code MockConsumer}
 * and REST's {@code HttpServer}, plan §16). The SDK never bundles a JDBC driver — the consumer supplies
 * it (plan §4).
 */
public interface ConnectionFactory {

    /**
     * Opens a new connection to the given datasource.
     *
     * @param datasource the resolved datasource (url/user/password)
     * @return a new open JDBC connection
     * @throws SQLException if the connection cannot be opened
     */
    Connection open(ResolvedDatasource datasource) throws SQLException;
}
