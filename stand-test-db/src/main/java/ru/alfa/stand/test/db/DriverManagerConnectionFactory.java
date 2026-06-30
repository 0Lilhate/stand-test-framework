package ru.alfa.stand.test.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Default {@link ConnectionFactory}: opens connections through {@link DriverManager}.
 *
 * <p>The JDBC driver is supplied by the consumer (plan §4) and self-registers via the JDBC 4 service
 * provider mechanism, so no {@code Class.forName} is needed. Each call opens a fresh connection; the
 * executor owns it for the run via the {@code ResourceScope} and closes it at the end of the run.
 */
public final class DriverManagerConnectionFactory implements ConnectionFactory {

    @Override
    public Connection open(ResolvedDatasource datasource) throws SQLException {
        return DriverManager.getConnection(datasource.url(), datasource.user(), datasource.password());
    }
}
