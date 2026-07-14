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
 *
 * <p><strong>Bounded connect (plan §2.5).</strong> The JDBC-standard {@link DriverManager#setLoginTimeout}
 * is bounded so connecting to an unreachable stand cannot hang on the driver's (often infinite) default.
 * It is only lowered — a caller that has already set a smaller (tighter) login timeout is left untouched —
 * and it bounds the connect phase; the read/execute phase is bounded separately by each statement's query
 * timeout ({@link NamedParameterStatement#DEFAULT_STATEMENT_TIMEOUT_SECONDS}).
 */
public final class DriverManagerConnectionFactory implements ConnectionFactory {

    /** Bounded login/connect timeout, in seconds, for opening a JDBC connection to a stand. */
    static final int LOGIN_TIMEOUT_SECONDS = 30;

    @Override
    public Connection open(ResolvedDatasource datasource) throws SQLException {
        int current = DriverManager.getLoginTimeout();
        // getLoginTimeout() == 0 means "unbounded" (the JDBC default): bound it. A caller-set smaller value
        // is a tighter bound and is respected.
        if (current <= 0 || current > LOGIN_TIMEOUT_SECONDS) {
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        }
        return DriverManager.getConnection(datasource.url(), datasource.user(), datasource.password());
    }
}
