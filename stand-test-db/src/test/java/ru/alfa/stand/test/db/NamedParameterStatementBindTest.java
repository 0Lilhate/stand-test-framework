package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Binding-path tests for {@link NamedParameterStatement} (the {@code parse()} coverage lives in
 * {@link NamedParameterStatementTest}): {@code create()} builds a {@link PreparedStatement} and binds each
 * {@code :name} positionally from the value map (round-tripped through H2), and fails closed when a
 * referenced name has no value — the "only parameterized binds" guarantee (plan §8.8).
 */
class NamedParameterStatementBindTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        this.connection = DbTestSupport.open(DbTestSupport.uniqueUrl());
        DbTestSupport.createOrdersTable(this.connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        this.connection.close();
    }

    @Test
    @DisplayName("create binds each :name positionally so the value reaches the database")
    void bindsValuesIntoTheStatement() throws SQLException {
        NamedParameterStatement insert = NamedParameterStatement.parse(
                "INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, :status, :run)");
        try (PreparedStatement statement = insert.create(this.connection, Map.of("id", "o1", "status", "READY", "run", "r1"), NamedParameterStatement.DEFAULT_STATEMENT_TIMEOUT_SECONDS)) {
            statement.executeUpdate();
        }

        try (PreparedStatement select = this.connection.prepareStatement("SELECT status, test_run_id FROM test_data.orders WHERE id = 'o1'");
                ResultSet rows = select.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("status")).isEqualTo("READY");
            assertThat(rows.getString("test_run_id")).isEqualTo("r1");
        }
    }

    @Test
    @DisplayName("a referenced :name with no supplied value fails closed before execution")
    void missingBindValueFailsClosed() {
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT id FROM test_data.orders WHERE id = :missing");

        assertThatThrownBy(() -> statement.create(this.connection, Map.of("other", "x"), NamedParameterStatement.DEFAULT_STATEMENT_TIMEOUT_SECONDS))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("No bind value supplied")
                .hasMessageContaining("missing");
    }
}
