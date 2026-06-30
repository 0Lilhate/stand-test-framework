package ru.alfa.stand.test.db;

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
 * {@link ForbiddenOperation} constants, so it cannot drift from the static validator or the AI schema
 * (plan §8.6). The DB executor calls it at runtime, before any IO, on the exact SQL it is about to send —
 * the defense-in-depth runtime re-enforcement the plan mandates. Rules (MVP):
 *
 * <ul>
 *   <li>unparseable / multi-statement → reject (fail-closed);</li>
 *   <li>{@code READ} → always allowed;</li>
 *   <li>{@code DESTRUCTIVE} (DDL/{@code TRUNCATE}/…) → always forbidden (no destructive-allow flag in the MVP);</li>
 *   <li>{@code WRITE} → only on {@code db.seed}/{@code db.cleanup}, only when {@code writeAllowed}, only to a
 *       schema-qualified table whose schema is in {@code allowedSchemas}, and an {@code UPDATE}/{@code DELETE}
 *       must scope rows through the SDK-declared {@code whereTestRunId} marker.</li>
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

    static SqlClassification classifyAndEnforce(String sql, DbOperation operation, DatasourceDefinition datasource, boolean testRunIdPredicateDeclared) {
        SqlClassification classification = SqlStatementClassifier.classify(sql);
        enforce(classification, operation, datasource, testRunIdPredicateDeclared);
        return classification;
    }

    static void enforce(SqlClassification classification, DbOperation operation, DatasourceDefinition datasource, boolean testRunIdPredicateDeclared) {
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
        // READ requires nothing further: reads are allowed on every operation.
    }

    private static void enforceWrite(SqlClassification classification, DbOperation operation, DatasourceDefinition datasource, boolean testRunIdPredicateDeclared) {
        if (!operation.isWrite()) {
            throw new StandTestException("A " + classification.leadingKeyword() + " write is only allowed on db.seed/db.cleanup, not "
                    + operation.stepType());
        }
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
        if (isUpdateOrDelete(classification)) {
            if (!testRunIdPredicateDeclared) {
                throw new StandTestException("A " + classification.leadingKeyword()
                        + " requires a declared testRunId predicate (DbStep.whereTestRunId(...)): the SDK-appended WHERE <column> = :testRunId is the single source of the predicate ["
                        + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code() + "]");
            }
            // Belt-and-suspenders: do not merely trust the declared flag — confirm the predicate is
            // actually present and effective in the SQL about to be executed. A trailing comment or
            // unterminated literal in the author SQL can neutralise the SDK-appended WHERE; in that case
            // the assembled SQL classifies with no WHERE and no :testRunId bind, and is refused here
            // rather than running unscoped (plan §8.8, fail-closed).
            if (!classification.containsWhereClause() || !classification.referencesTestRunIdBind()) {
                throw new StandTestException("A " + classification.leadingKeyword()
                        + " whose testRunId predicate was neutralised (e.g. by a trailing comment or unterminated literal) is refused as an unscoped mutation ["
                        + ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW.code() + "]");
            }
        }
    }

    private static boolean isUpdateOrDelete(SqlClassification classification) {
        return "UPDATE".equals(classification.leadingKeyword()) || "DELETE".equals(classification.leadingKeyword());
    }
}
