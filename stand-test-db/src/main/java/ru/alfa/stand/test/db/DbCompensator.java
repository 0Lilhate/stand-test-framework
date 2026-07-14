package ru.alfa.stand.test.db;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.compensation.CompensationOutcome;
import ru.alfa.stand.test.core.compensation.Compensator;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;

/**
 * DB-side {@link Compensator} for a {@code db.write} INSERT: undoes the row by primary key with
 * {@code DELETE FROM <schema.table> WHERE <pk> = ...} over the run-scoped connection.
 *
 * <p>Registered into the run's undo-log at execution time (capture is always-on); applied by the runner in
 * its {@code finally}, in reverse registration order, per the scenario's {@code CleanupPolicy}. Per the
 * {@link Compensator} contract it <strong>never throws</strong>: a {@link SQLException} or any other error
 * is folded into a {@link CompensationOutcome} of status FAILED. The generated DELETE is re-checked by the
 * {@link DbWriteGuard} compensation lane before it runs, and every value is bound through
 * {@link NamedParameterStatement}. The undo is idempotent: a DELETE that affects zero rows (the row was
 * already gone) is reported SKIPPED rather than failed.
 *
 * <p>Diagnostics carry the table and primary-key column NAMES only — never the key values — so no test
 * data leaks into the report.
 */
final class DbCompensator implements Compensator {

    private static final String PK_BIND_PREFIX = "__pk_";

    private final String actionId;
    private final String datasourceAlias;
    private final RunScopedConnection connection;
    private final DatasourceDefinition datasource;
    private final String qualifiedTable;
    private final List<String> pkColumns;
    private final Map<String, Object> pkValues;

    DbCompensator(
            String actionId,
            String datasourceAlias,
            RunScopedConnection connection,
            DatasourceDefinition datasource,
            String qualifiedTable,
            List<String> pkColumns,
            Map<String, Object> pkValues) {
        this.actionId = Objects.requireNonNull(actionId, "actionId must not be null");
        this.datasourceAlias = Objects.requireNonNull(datasourceAlias, "datasourceAlias must not be null");
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.datasource = Objects.requireNonNull(datasource, "datasource must not be null");
        this.qualifiedTable = Objects.requireNonNull(qualifiedTable, "qualifiedTable must not be null");
        this.pkColumns = List.copyOf(Objects.requireNonNull(pkColumns, "pkColumns must not be null"));
        this.pkValues = Map.copyOf(Objects.requireNonNull(pkValues, "pkValues must not be null"));
    }

    @Override
    public String actionId() {
        return actionId;
    }

    @Override
    public String target() {
        return datasourceAlias;
    }

    @Override
    public CompensationOutcome compensate() {
        String whereClause = buildWhereClause();
        String deleteSql = "DELETE FROM " + qualifiedTable + " WHERE " + whereClause;
        String countSql = "SELECT COUNT(*) FROM " + qualifiedTable + " WHERE " + whereClause;
        Map<String, Object> binds = new LinkedHashMap<>();
        for (String column : pkColumns) {
            binds.put(PK_BIND_PREFIX + column, pkValues.get(column));
        }
        Map<String, Object> diagnostics = Map.of(
                "db.table", qualifiedTable,
                "db.pkColumns", pkColumns,
                "db.operation", "compensate");
        try {
            DbWriteGuard.classifyAndEnforceCompensationDelete(deleteSql, datasource);
            // Pre-count BEFORE deleting (autoCommit=true means a DELETE is irreversible): if the key matches
            // more than one row the declared identifiedBy is not unique, so the undo would remove foreign
            // rows — report FAILED and delete NOTHING, rather than losing data we did not create.
            long matching = countMatching(countSql, binds);
            if (matching == 0) {
                return CompensationOutcome.skipped(actionId, datasourceAlias, "row already absent (idempotent no-op)", diagnostics);
            }
            if (matching > 1) {
                return CompensationOutcome.failed(actionId, datasourceAlias,
                        "undo of " + qualifiedTable + " matched " + matching + " rows by " + pkColumns + " (expected 1): the identifiedBy column(s) are not unique — nothing was deleted",
                        null, diagnostics);
            }
            NamedParameterStatement statement = NamedParameterStatement.parse(deleteSql);
            int rowsAffected;
            try (PreparedStatement prepared = statement.create(connection.connection(), binds, NamedParameterStatement.DEFAULT_STATEMENT_TIMEOUT_SECONDS)) {
                rowsAffected = prepared.executeUpdate();
            }
            if (rowsAffected <= 0) {
                return CompensationOutcome.skipped(actionId, datasourceAlias, "row vanished between count and delete (idempotent no-op)", diagnostics);
            }
            return CompensationOutcome.applied(actionId, datasourceAlias, rowsAffected, diagnostics);
        } catch (Throwable failure) {
            // Honour the never-throw Compensator contract even against an Error: any failure is folded into
            // a FAILED outcome so the runner's drain can attempt the remaining actions and run its tail.
            return CompensationOutcome.failed(actionId, datasourceAlias, "undo of " + qualifiedTable + " failed: " + failure.getMessage(), failure, diagnostics);
        }
    }

    private long countMatching(String countSql, Map<String, Object> binds) throws java.sql.SQLException {
        NamedParameterStatement statement = NamedParameterStatement.parse(countSql);
        try (PreparedStatement prepared = statement.create(connection.connection(), binds, NamedParameterStatement.DEFAULT_STATEMENT_TIMEOUT_SECONDS);
                java.sql.ResultSet rows = prepared.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    private String buildWhereClause() {
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < pkColumns.size(); index++) {
            if (index > 0) {
                sql.append(" AND ");
            }
            String column = pkColumns.get(index);
            sql.append(column).append(" = :").append(PK_BIND_PREFIX).append(column);
        }
        return sql.toString();
    }
}
