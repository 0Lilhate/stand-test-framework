package ru.alfa.stand.test.db;

import java.util.Locale;
import java.util.Set;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.validation.ForbiddenOperation;
import ru.alfa.stand.test.core.validation.SqlClassification;
import ru.alfa.stand.test.core.validation.SqlStatementClassifier;
import ru.alfa.stand.test.core.validation.SqlStatementKind;

/**
 * The DB write-guard (plan §8.8): turns a {@link SqlClassification} plus the step operation and the
 * resolved {@link DatasourceDefinition} into an allow/deny decision, failing closed with a
 * {@link StandTestException} on any violation.
 *
 * <p>It derives every decision from the core {@link SqlStatementClassifier} and the
 * {@link ForbiddenOperation} constants, so it cannot drift from the pre-flight {@code ScenarioValidator}
 * (plan §8.6) — the two are the SDK's only derivations of that enum since {@code stand-test-ai-schema}
 * was removed. The DB executor calls it at runtime, before any IO, on the exact SQL it is about to send —
 * the defense-in-depth runtime re-enforcement the plan mandates. Rules (MVP):
 *
 * <ul>
 *   <li>unparseable / multi-statement → reject (fail-closed);</li>
 *   <li>{@code READ} → always allowed;</li>
 *   <li>{@code DESTRUCTIVE} (DDL/{@code TRUNCATE}/…) → always forbidden (no destructive-allow flag in the MVP);</li>
 *   <li>{@code WRITE} → only on {@code db.seed}/{@code db.cleanup}, only when {@code writeAllowed}, only to a
 *       schema-qualified table whose schema is in {@code allowedSchemas}; an {@code UPDATE}/{@code DELETE}
 *       must scope rows through the SDK-declared {@code whereTestRunId} marker, and an {@code INSERT} seed
 *       must tag its rows with the reserved {@code :testRunId} bind so parallel runs stay isolated
 *       (plan §15).</li>
 * </ul>
 *
 * <p><strong>Why the marker, not a substring (plan §8.8).</strong> The {@code UPDATE}/{@code DELETE}
 * predicate requirement is gated on whether the step <em>declared</em> the {@code whereTestRunId} marker
 * (so the SDK itself appended {@code WHERE <column> = :testRunId} and forbade any author {@code WHERE}),
 * never on the statement merely mentioning {@code :testRunId}. A textual reference is trivially defeated —
 * {@code UPDATE t SET note = :testRunId} or {@code DELETE ... WHERE id = :testRunId OR 1=1} both reference
 * the bind yet scope nothing — so it cannot be the gate for a whole-table mutation.
 */
final class DbWriteGuard {

    private DbWriteGuard() {
    }

    /**
     * The base guard (no seed tag-column verification): classifies and enforces the destructive/write/schema
     * and UPDATE/DELETE-marker rules. This overload does NOT require a seed INSERT to declare its tag column,
     * so it is the surface for the classifier/write-guard unit tests; the executor uses the five-argument
     * overload, which additionally enforces seed tagging.
     */
    static SqlClassification classifyAndEnforce(String sql, DbOperation operation, DatasourceDefinition datasource,
            boolean testRunIdPredicateDeclared) {
        rejectSideEffectingTimeFunction(sql);
        SqlClassification classification = SqlStatementClassifier.classify(sql);
        enforce(classification, operation, datasource, testRunIdPredicateDeclared);
        return classification;
    }

    /**
     * The full guard used by the executor: the base rules plus seed tag-column verification (plan §15). A
     * seed that classifies as an {@code INSERT} must declare its testRunId tag column (via
     * {@code DbStep.taggedByTestRunId(...)}) AND that column must appear in the INSERT column list. The base
     * {@link #enforceWrite} rule only proves the statement references {@code :testRunId} <em>somewhere</em>,
     * which is necessary but not sufficient: {@code INSERT INTO t(id) VALUES (:testRunId)} references the bind
     * yet tags no reapable column, so the row would leak across parallel runs. Verifying the declared column —
     * the same one the paired cleanup filters on — closes that gap. Seed {@code UPDATE}/{@code DELETE} use the
     * {@code whereTestRunId} marker instead and are not tag-verified here.
     */
    static SqlClassification classifyAndEnforce(
            String sql, DbOperation operation, DatasourceDefinition datasource, boolean testRunIdPredicateDeclared,
                    String seedTestRunIdColumn) {
        rejectSideEffectingTimeFunction(sql);
        SqlClassification classification = SqlStatementClassifier.classify(sql);
        enforce(classification, operation, datasource, testRunIdPredicateDeclared);
        enforceSeedTagColumn(classification, sql, seedTestRunIdColumn);
        return classification;
    }

    /**
     * Fails closed on a blocking/side-effecting SQL time function
     * ({@code pg_sleep}/{@code sleep}/{@code waitfor}/{@code benchmark}/{@code dbms_lock}) on the exact
     * assembled SQL — the runtime re-enforcement that closes the {@code sqlResource} bypass (the static
     * validator only sees inline {@code sql}, plan §8.6). It also catches a side-effecting function hidden
     * behind a {@code SELECT} (which classifies as a {@code READ}), so a read cannot run a real sleep on the
     * stand. Shares {@link SqlStatementClassifier#containsSideEffectingTimeFunction} with the static
     * validator, so the inline and resource paths cannot drift.
     */
    private static void rejectSideEffectingTimeFunction(String sql) {
        if (SqlStatementClassifier.containsSideEffectingTimeFunction(sql)) {
            throw new StandTestException("SQL calls a blocking/side-effecting time function "
                    + "(pg_sleep/sleep/waitfor/benchmark/dbms_lock), which is forbidden — the only sanctioned wait is "
                    + "the declarative step timeout [" + ForbiddenOperation.THREAD_SLEEP.code() + "]");
        }
    }

    private static void enforceSeedTagColumn(SqlClassification classification, String sql, String seedTestRunIdColumn) {
        if (!classification.isWrite() || !"INSERT".equals(classification.leadingKeyword())) {
            return;
        }
        if (seedTestRunIdColumn == null) {
            throw new StandTestException("A db.seed INSERT must declare its testRunId tag column via DbStep.taggedByTestRunId(...) — the "
                    + "same column its cleanup filters on — so the write-guard can verify the row is reapable "
                    + "across parallel runs (plan §15) ["
                    + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code() + "]");
        }
        Set<String> columns = SqlStatementClassifier.insertColumns(sql);
        if (!columns.contains(seedTestRunIdColumn.toLowerCase(Locale.ROOT))) {
            throw new StandTestException("A db.seed INSERT must tag its rows in the declared testRunId column '" + seedTestRunIdColumn
                    + "' bound to :testRunId (for example INSERT INTO test_data.orders(id, " + seedTestRunIdColumn
                            + ") VALUES (:id, :testRunId)); the column is absent from the INSERT column list " + columns
                            + ", so the row would not be reaped by the run's own testRunId-scoped cleanup and would "
                            + "leak across concurrent runs ["
                    + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code() + "]");
        }
    }

    static void enforce(SqlClassification classification, DbOperation operation, DatasourceDefinition datasource,
            boolean testRunIdPredicateDeclared) {
        if (classification.isRejected()) {
            throw new StandTestException("SQL rejected (fail-closed, plan §8.8): " + classification.detail());
        }
        if (classification.kind() == SqlStatementKind.DESTRUCTIVE) {
            throw new StandTestException("Destructive/DDL SQL is forbidden [" + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code()
                    + "]: " + classification.detail());
        }
        if (classification.isWrite()) {
            enforceWrite(classification, operation, datasource, testRunIdPredicateDeclared);
        }
    }

    /**
     * The {@code db.write} lane (undo-log model, {@code docs/arch/stand-test-db-rollback-design.md}):
     * classifies and enforces the destructive/schema/write-allowed rules for a business write, WITHOUT the
     * legacy {@code testRunId}-marker requirement — row identity comes from the primary key instead. The
     * MVP accepts only {@code INSERT} (UPDATE/DELETE undo via before-image capture is staged).
     *
     * @param sql the SQL about to be executed
     * @param datasource the resolved datasource
     * @return the classification (its {@code writeSchema}/{@code writeTable} identify the target)
     */
    static SqlClassification classifyAndEnforceBusinessWrite(String sql, DatasourceDefinition datasource) {
        rejectSideEffectingTimeFunction(sql);
        SqlClassification classification = SqlStatementClassifier.classify(sql);
        if (classification.isRejected()) {
            throw new StandTestException("SQL rejected (fail-closed): " + classification.detail());
        }
        if (classification.kind() == SqlStatementKind.DESTRUCTIVE) {
            throw new StandTestException("Destructive/DDL SQL is forbidden [" + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code()
                    + "]: " + classification.detail());
        }
        if (!classification.isWrite()) {
            throw new StandTestException("db.write requires an INSERT/UPDATE/DELETE statement, but got: " + classification.detail());
        }
        if (!"INSERT".equals(classification.leadingKeyword())) {
            throw new StandTestException("db.write currently supports INSERT only (UPDATE/DELETE undo is staged); use the legacy "
                    + "db.cleanup for scoped deletes");
        }
        // Only a single-row INSERT ... VALUES (...) is undoable: its one primary key can be captured and
        // deleted. A multi-row VALUES or an INSERT ... SELECT commits a row set the MVP cannot fully capture,
        // so the undo would delete at most one row and silently leak the rest — reject fail-closed.
        int arity = SqlStatementClassifier.insertValuesRowArity(sql);
        if (arity != 1) {
            throw new StandTestException("db.write requires a single-row INSERT ... VALUES (...): a multi-row VALUES or an INSERT ... "
                    + "SELECT is not undoable in the MVP "
                    + "(its full written row set cannot be captured for primary-key compensation, so extra rows would leak). Found "
                    + (arity == 0 ? "no top-level VALUES tuple" : arity + " VALUES tuples") + " ["
                            + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code() + "]");
        }
        enforceSchemaAndWriteAllowed(classification, datasource);
        return classification;
    }

    /**
     * The compensation lane: enforces the base write rules on an SDK-generated undo statement (a
     * primary-key-scoped {@code DELETE}). The statement is built by the SDK from captured primary-key
     * values, so it is inherently parameterized and PK-scoped; this method re-checks it fails closed on the
     * same destructive/schema/write-allowed rules before it runs, and confirms it carries a {@code WHERE}.
     *
     * @param sql the compensation SQL about to be executed
     * @param datasource the resolved datasource
     * @return the classification
     */
    static SqlClassification classifyAndEnforceCompensationDelete(String sql, DatasourceDefinition datasource) {
        rejectSideEffectingTimeFunction(sql);
        SqlClassification classification = SqlStatementClassifier.classify(sql);
        if (classification.isRejected()) {
            throw new StandTestException("Compensation SQL rejected (fail-closed): " + classification.detail());
        }
        if (classification.kind() == SqlStatementKind.DESTRUCTIVE || !classification.isWrite()
                || !"DELETE".equals(classification.leadingKeyword())) {
            throw new StandTestException("A compensation statement must be a single DELETE, but got: " + classification.detail());
        }
        enforceSchemaAndWriteAllowed(classification, datasource);
        if (!classification.containsWhereClause()) {
            throw new StandTestException("A compensation DELETE must be primary-key-scoped (carry a WHERE clause)");
        }
        return classification;
    }

    private static void enforceSchemaAndWriteAllowed(SqlClassification classification, DatasourceDefinition datasource) {
        if (!datasource.writeAllowed()) {
            throw new StandTestException("Write to datasource '" + datasource.alias() + "' is forbidden ["
                    + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code() + "]: the datasource is read-only (writeAllowed=false)");
        }
        if (!classification.writeTargetSchemaQualified()) {
            throw new StandTestException("A " + classification.leadingKeyword()
                    + " write must target a schema-qualified table (for example test_data.orders); its schema cannot be proven otherwise");
        }
        if (!datasource.isSchemaAllowed(classification.writeSchema())) {
            throw new StandTestException("Schema '" + classification.writeSchema() + "' is not in the datasource's allowedSchemas "
                    + datasource.allowedSchemas() + " (datasource '" + datasource.alias() + "')");
        }
    }

    private static void enforceWrite(SqlClassification classification, DbOperation operation, DatasourceDefinition datasource,
            boolean testRunIdPredicateDeclared) {
        if (!operation.isWrite()) {
            throw new StandTestException("A " + classification.leadingKeyword() + " write is only allowed on db.seed/db.cleanup, not "
                    + operation.stepType());
        }
        enforceSchemaAndWriteAllowed(classification, datasource);
        if (isUpdateOrDelete(classification)) {
            if (!testRunIdPredicateDeclared) {
                throw new StandTestException("A " + classification.leadingKeyword()
                        + " requires a declared testRunId predicate (DbStep.whereTestRunId(...)): the SDK-appended WHERE <column> = "
                        + ":testRunId is the single source of the predicate ["
                        + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code() + "]");
            }
            if (!classification.containsWhereClause() || !classification.referencesTestRunIdBind()) {
                throw new StandTestException("A " + classification.leadingKeyword()
                        + " whose testRunId predicate was neutralised (e.g. by a trailing comment or unterminated literal) is "
                        + "refused as an unscoped mutation ["
                        + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code() + "]");
            }
        } else {
            if (!classification.referencesTestRunIdBind()) {
                throw new StandTestException("A " + classification.leadingKeyword()
                        + " seed must tag its rows with the reserved :testRunId bind (for example INSERT INTO test_data.orders(id, "
                        + "test_run_id) VALUES (:id, :testRunId)), so the run's testRunId-scoped cleanup reaps them and "
                        + "concurrent runs stay isolated ["
                        + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code() + "]");
            }
        }
    }

    private static boolean isUpdateOrDelete(SqlClassification classification) {
        return "UPDATE".equals(classification.leadingKeyword()) || "DELETE".equals(classification.leadingKeyword());
    }
}
