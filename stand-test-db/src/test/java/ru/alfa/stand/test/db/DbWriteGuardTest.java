package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.exception.StandTestException;

class DbWriteGuardTest {

    private static final DatasourceDefinition WRITABLE =
            new DatasourceDefinition("mainDb", "URL", "USER", "PWD", Set.of("test_data"), true);
    private static final DatasourceDefinition READONLY =
            new DatasourceDefinition("mainDb", "URL", "USER", "PWD", Set.of("test_data"), false);

    @Test
    @DisplayName("a read is allowed on every operation")
    void readAlwaysAllowed() {
        assertThatCode(() -> DbWriteGuard.classifyAndEnforce("SELECT 1 FROM test_data.orders", DbOperation.QUERY, READONLY, false)).doesNotThrowAnyException();
        assertThatCode(() -> DbWriteGuard.classifyAndEnforce("SELECT 1 FROM test_data.orders", DbOperation.EXPECT_EVENTUALLY, READONLY, false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a valid INSERT seed into a whitelisted schema is allowed when writeAllowed")
    void seedAllowed() {
        assertThatCode(() -> DbWriteGuard.classifyAndEnforce("INSERT INTO test_data.orders(id) VALUES (:id)", DbOperation.SEED, WRITABLE, false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a cleanup DELETE with the SDK-declared testRunId predicate is allowed when writeAllowed")
    void cleanupAllowed() {
        assertThatCode(() -> DbWriteGuard.classifyAndEnforce("DELETE FROM test_data.orders WHERE test_run_id = :testRunId", DbOperation.CLEANUP, WRITABLE, true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a write is forbidden on a non-write operation (db.query)")
    void writeForbiddenOnQuery() {
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("INSERT INTO test_data.orders(id) VALUES (:id)", DbOperation.QUERY, WRITABLE, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("only allowed on db.seed/db.cleanup");
    }

    @Test
    @DisplayName("a write to a read-only datasource is forbidden")
    void writeForbiddenWhenReadonly() {
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("INSERT INTO test_data.orders(id) VALUES (:id)", DbOperation.SEED, READONLY, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("a write to an unqualified table is forbidden (schema cannot be proven)")
    void unqualifiedWriteForbidden() {
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("INSERT INTO orders(id) VALUES (:id)", DbOperation.SEED, WRITABLE, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("schema-qualified");
    }

    @Test
    @DisplayName("a write to a non-whitelisted schema is forbidden")
    void nonWhitelistedSchemaForbidden() {
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("INSERT INTO public.orders(id) VALUES (:id)", DbOperation.SEED, WRITABLE, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("allowedSchemas");
    }

    @Test
    @DisplayName("an UPDATE/DELETE without the SDK-declared marker is forbidden — even when the SQL itself mentions :testRunId")
    void mutationWithoutDeclaredMarkerForbidden() {
        // No marker declared at all.
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("DELETE FROM test_data.orders", DbOperation.CLEANUP, WRITABLE, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("declared testRunId predicate");
        // The substring-bypass vectors: :testRunId in SET, or a degenerate WHERE — both reference the bind
        // textually but scope nothing. Must still be rejected because no marker was declared.
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("UPDATE test_data.orders SET note = :testRunId", DbOperation.SEED, WRITABLE, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("declared testRunId predicate");
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("DELETE FROM test_data.orders WHERE id = :testRunId OR 1=1", DbOperation.CLEANUP, WRITABLE, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("declared testRunId predicate");
    }

    @Test
    @DisplayName("a marker-declared UPDATE/DELETE whose WHERE was commented out is refused (predicate neutralised)")
    void neutralisedPredicateForbidden() {
        // The declared flag is true, but the final SQL the executor would send has the appended WHERE
        // swallowed by a trailing line comment — the guard must not trust the flag alone.
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("DELETE FROM test_data.orders -- WHERE test_run_id = :testRunId", DbOperation.CLEANUP, WRITABLE, true))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("neutralised");
    }

    @Test
    @DisplayName("destructive / DDL SQL is always forbidden")
    void destructiveForbidden() {
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("TRUNCATE TABLE test_data.orders", DbOperation.CLEANUP, WRITABLE, true))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Destructive");
    }

    @Test
    @DisplayName("a SELECT ... INTO write disguised as a read is rejected even on db.query")
    void selectIntoRejected() {
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("SELECT * INTO scratch.dump FROM test_data.orders", DbOperation.QUERY, READONLY, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    @DisplayName("an INSERT upsert (ON CONFLICT DO UPDATE) is rejected fail-closed")
    void upsertRejected() {
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("INSERT INTO test_data.orders(id) VALUES (:id) ON CONFLICT (id) DO UPDATE SET status = 'X'", DbOperation.SEED, WRITABLE, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    @DisplayName("a 3-part catalog.schema.table write target is rejected (schema not provable)")
    void threePartNameForbidden() {
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("INSERT INTO catalog.public.orders(id) VALUES (:id)", DbOperation.SEED, WRITABLE, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("schema-qualified");
    }

    @Test
    @DisplayName("unparseable / multi-statement SQL is rejected fail-closed")
    void rejectedFailClosed() {
        assertThatThrownBy(() -> DbWriteGuard.classifyAndEnforce("SELECT 1; DELETE FROM test_data.orders", DbOperation.QUERY, READONLY, false))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    @DisplayName("an upper-case unquoted schema target is folded to its whitelisted lower-case schema (PostgreSQL folds unquoted identifiers)")
    void upperCaseTargetSchemaIsFoldedToWhitelistedSchema() {
        assertThatCode(() -> DbWriteGuard.classifyAndEnforce("INSERT INTO TEST_DATA.orders(id) VALUES (:id)", DbOperation.SEED, WRITABLE, false))
                .doesNotThrowAnyException();
    }
}
