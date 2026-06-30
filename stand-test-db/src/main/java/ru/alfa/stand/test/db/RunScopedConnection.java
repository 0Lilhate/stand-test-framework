package ru.alfa.stand.test.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * A per-run JDBC connection held in the run-scoped {@code ResourceScope}, keyed by datasource alias
 * (plan §8.7 — the generic mechanism by which DB/gRPC adapters keep per-run connections).
 *
 * <p>One connection per datasource alias is opened lazily on first use and shared by every DB step on
 * that datasource within the run, so {@code db.seed} → {@code db.query}/{@code db.expectEventually} see
 * each other's committed effects. The runner calls {@link #close()} via {@code ResourceScope.closeAll()}
 * in the run's {@code finally}, so the connection never leaks even when a step throws. It is
 * single-threaded: a run is driven on one thread, consistent with the thread-confinement of the awaiter.
 */
final class RunScopedConnection implements AutoCloseable {

    private final Connection connection;
    private final String datasourceAlias;

    RunScopedConnection(Connection connection, String datasourceAlias) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.datasourceAlias = Objects.requireNonNull(datasourceAlias, "datasourceAlias must not be null");
    }

    Connection connection() {
        return this.connection;
    }

    String datasourceAlias() {
        return this.datasourceAlias;
    }

    @Override
    public void close() {
        try {
            this.connection.close();
        } catch (SQLException failure) {
            throw new StandTestException("Failed to close JDBC connection for datasource '" + this.datasourceAlias + "'", failure);
        }
    }
}
