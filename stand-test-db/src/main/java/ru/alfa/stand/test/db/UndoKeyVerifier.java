package ru.alfa.stand.test.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Arm-time verification that a {@code db.write}'s declared {@code identifiedBy(...)} columns actually form
 * the PRIMARY KEY or a UNIQUE key of the target table (via {@link DatabaseMetaData}), so the
 * primary-key-scoped undo {@code DELETE} can only ever match the single row this test inserted.
 *
 * <p>Without this, an author could declare a non-unique column: the compensator's pre-count net
 * ({@code matching > 1 -> FAILED, delete nothing}, see {@link DbCompensator}) already prevents deleting
 * foreign rows, but that fails the cleanup rather than the write and leaves the wrong key armed. This
 * verifier fails the WRITE closed up-front instead, so a semantically-wrong undo key is caught before any
 * row is committed (plan §15; the CONFIRMED-HIGH from the 2026-07 review).
 *
 * <p>Unquoted-identifier case differs across databases (H2 stores names upper-case, PostgreSQL lower-case,
 * and H2's {@code MODE=PostgreSQL} lower-cases too), so the metadata lookup tries the schema/table name as
 * written plus its upper- and lower-case variants and takes the first that resolves; column-set comparison
 * is case-insensitive.
 */
final class UndoKeyVerifier {

    private UndoKeyVerifier() {
    }

    /**
     * Fails closed unless {@code identifiedBy} equals (as a case-insensitive column set) the table's primary
     * key or one of its unique keys.
     *
     * @param connection the run-scoped connection (its {@link DatabaseMetaData} is queried, no rows read)
     * @param schema the write target schema (as written in the SQL)
     * @param table the write target table (as written in the SQL)
     * @param identifiedBy the declared undo key columns
     * @param datasourceAlias the logical datasource alias, for the error message
     * @throws StandTestException if the declared columns are not a provable unique key, or metadata fails
     */
    static void verifyUniqueKey(Connection connection, String schema, String table, List<String> identifiedBy, String datasourceAlias) {
        Set<String> declared = upperSet(identifiedBy);
        try {
            DatabaseMetaData meta = connection.getMetaData();
            Set<String> primaryKey = firstNonEmptySet(
                    primaryKeyColumns(meta, schema, table),
                    primaryKeyColumns(meta, schema.toUpperCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)),
                    primaryKeyColumns(meta, schema.toLowerCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)));
            if (!primaryKey.isEmpty() && declared.equals(primaryKey)) {
                return;
            }
            List<Set<String>> uniqueKeys = firstNonEmptyList(
                    uniqueColumnSets(meta, schema, table),
                    uniqueColumnSets(meta, schema.toUpperCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)),
                    uniqueColumnSets(meta, schema.toLowerCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)));
            for (Set<String> unique : uniqueKeys) {
                if (declared.equals(unique)) {
                    return;
                }
            }
            throw new StandTestException("db.write on '" + datasourceAlias + "' cannot arm a safe undo: identifiedBy(" + identifiedBy
                    + ") is neither the primary key " + render(primaryKey) + " nor any unique key " + render(uniqueKeys) + " of " + schema
                            + "." + table
                    + " — a non-unique undo key could delete rows this test did not create; declare the "
                    + "table's primary key or a unique key");
        } catch (SQLException failure) {
            throw new StandTestException("db.write on '" + datasourceAlias + "' could not verify that identifiedBy(" + identifiedBy
                    + ") is a unique key of " + schema + "." + table + " via database metadata: " + failure.getMessage(), failure);
        }
    }

    private static Set<String> primaryKeyColumns(DatabaseMetaData meta, String schema, String table) throws SQLException {
        Set<String> columns = new TreeSet<>();
        try (ResultSet rows = meta.getPrimaryKeys(null, schema, table)) {
            while (rows.next()) {
                String column = rows.getString("COLUMN_NAME");
                if (column != null) {
                    columns.add(column.toUpperCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    private static List<Set<String>> uniqueColumnSets(DatabaseMetaData meta, String schema, String table) throws SQLException {
        Map<String, Set<String>> byIndex = new LinkedHashMap<>();
        try (ResultSet rows = meta.getIndexInfo(null, schema, table, true, false)) {
            while (rows.next()) {
                String indexName = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                // tableIndexStatistic rows carry a null index/column name and must be skipped.
                if (indexName == null || column == null) {
                    continue;
                }
                byIndex.computeIfAbsent(indexName, key -> new TreeSet<>()).add(column.toUpperCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(byIndex.values());
    }

    private static Set<String> upperSet(List<String> columns) {
        Set<String> upper = new TreeSet<>();
        for (String column : columns) {
            upper.add(column.toUpperCase(Locale.ROOT));
        }
        return upper;
    }

    @SafeVarargs
    private static Set<String> firstNonEmptySet(Set<String>... candidates) {
        for (Set<String> candidate : candidates) {
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return Set.of();
    }

    @SafeVarargs
    private static List<Set<String>> firstNonEmptyList(List<Set<String>>... candidates) {
        for (List<Set<String>> candidate : candidates) {
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return List.of();
    }

    private static String render(Set<String> columns) {
        return columns.isEmpty() ? "(none)" : columns.toString();
    }

    private static String render(List<Set<String>> uniqueKeys) {
        return uniqueKeys.isEmpty() ? "(none)" : uniqueKeys.toString();
    }
}
