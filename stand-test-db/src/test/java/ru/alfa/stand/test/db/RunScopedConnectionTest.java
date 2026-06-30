package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.ResourceScope;

/**
 * Lifecycle tests for {@link RunScopedConnection} (plan §8.7): a successful close releases the underlying
 * JDBC connection; a driver close failure is wrapped as a {@link StandTestException} carrying the datasource
 * alias; and such failures aggregate through {@link ResourceScope#closeAll()}. The generic aggregation
 * mechanics live in core {@code ResourceScopeTest}; here they are exercised through real
 * {@code RunScopedConnection}s over a {@link FakeConnection}.
 */
class RunScopedConnectionTest {

    @Test
    @DisplayName("close releases the underlying connection")
    void closeReleasesUnderlyingConnection() {
        FakeConnection fake = new FakeConnection(false, false);
        RunScopedConnection connection = new RunScopedConnection(fake.connection(), "mainDb");

        assertThat(connection.connection()).isSameAs(fake.connection());
        assertThat(connection.datasourceAlias()).isEqualTo("mainDb");
        assertThatCode(connection::close).doesNotThrowAnyException();
        assertThat(fake.closeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a driver close failure is wrapped as a StandTestException naming the datasource")
    void closeFailureIsWrappedWithAlias() {
        FakeConnection fake = new FakeConnection(false, true);
        RunScopedConnection connection = new RunScopedConnection(fake.connection(), "mainDb");

        assertThatThrownBy(connection::close)
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to close JDBC connection")
                .hasMessageContaining("mainDb")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("closeAll aggregates multiple RunScopedConnection close failures (first as cause, rest suppressed)")
    void closeAllAggregatesConnectionCloseFailures() {
        FakeConnection a = new FakeConnection(false, true);
        FakeConnection b = new FakeConnection(false, true);
        ResourceScope scope = new ResourceScope();
        scope.register("db.datasource:a", new RunScopedConnection(a.connection(), "a"));
        scope.register("db.datasource:b", new RunScopedConnection(b.connection(), "b"));

        Throwable thrown = catchThrowable(scope::closeAll);

        assertThat(thrown)
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to close 2 run-scoped resource(s)");
        assertThat(thrown.getCause())
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to close JDBC connection");
        assertThat(thrown.getSuppressed()).hasSize(1);
        assertThat(a.closeCount()).isEqualTo(1);
        assertThat(b.closeCount()).isEqualTo(1);
    }
}
