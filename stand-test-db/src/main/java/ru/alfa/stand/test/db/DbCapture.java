package ru.alfa.stand.test.db;

/**
 * Extraction of a column value from the first row of a {@code db.query} result into the run's
 * {@code VariableStore}, so a later step can reference it as {@code ${variableName}}.
 *
 * @param variableName the variable name to store the captured value under (never blank)
 * @param column the result-set column label to read (never blank)
 */
public record DbCapture(String variableName, String column) {

    public DbCapture {
        if (variableName == null || variableName.isBlank()) {
            throw new IllegalArgumentException("variableName must not be blank");
        }
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("column must not be blank");
        }
    }
}
