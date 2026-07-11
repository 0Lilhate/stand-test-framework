package ru.alfa.stand.test.db;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * Lazy builder for a DB step ({@code db.query}, {@code db.expectEventually}, {@code db.seed} or
 * {@code db.cleanup}).
 *
 * <p>It assembles configuration only and performs no IO — {@link #build()} materialises an immutable core
 * {@link GenericStep} whose parameter map follows the {@link DbStepParameters} schema. The real JDBC IO,
 * the SQL classification/write-guard and the {@code :name} binding happen later, inside
 * {@link DbStepExecutor}, so the builder can never bypass the validator or the run pipeline (plan §8.1).
 * Reading a {@code sqlFromResource(...)} classpath resource is likewise deferred to execution.
 *
 * <p>Typical use (each {@code .build()} result is passed to {@code Scenario.Builder.step(...)}):
 * <pre>{@code
 * DbStep.seed("mainDb")
 *         .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)")
 *         .param("id", "${entityId}")
 *         .build()
 *
 * DbStep.expectEventually("mainDb")
 *         .sql("SELECT status FROM test_data.orders WHERE id = :id")
 *         .param("id", "${entityId}")
 *         .expectValue("DONE")
 *         .withinSeconds(30)
 *         .build()
 *
 * DbStep.cleanup("mainDb")
 *         .sql("DELETE FROM test_data.orders")
 *         .whereTestRunId("test_run_id")          // appends WHERE test_run_id = :testRunId
 *         .build()
 * }</pre>
 */
public final class DbStep {

    private final DbOperation operation;
    private final String datasource;
    private final Map<String, Object> params = new LinkedHashMap<>();
    private final List<DbCapture> captures = new ArrayList<>();
    private final List<String> identifiedBy = new ArrayList<>();
    private String id;
    private String sql;
    private String sqlResource;
    private Object expectedValue;
    private boolean expectedValueSet;
    private String whereTestRunIdColumn;
    private String seedTestRunIdColumn;
    private Long timeoutMillis;
    private Long pollIntervalMillis;

    private DbStep(DbOperation operation, String datasource) {
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.datasource = requireNonBlank(datasource, "datasource");
    }

    /**
     * Starts a {@code db.query} step (a one-shot read that captures column values) against the given
     * datasource alias.
     *
     * @param datasource the logical datasource alias
     * @return a new builder
     */
    public static DbStep query(String datasource) {
        return new DbStep(DbOperation.QUERY, datasource);
    }

    /**
     * Starts a {@code db.expectEventually} step (polls a read until its single value matches) against the
     * given datasource alias.
     *
     * @param datasource the logical datasource alias
     * @return a new builder
     */
    public static DbStep expectEventually(String datasource) {
        return new DbStep(DbOperation.EXPECT_EVENTUALLY, datasource);
    }

    /**
     * Starts a {@code db.seed} step (constrained write that prepares test data) against the given
     * datasource alias.
     *
     * @param datasource the logical datasource alias
     * @return a new builder
     */
    public static DbStep seed(String datasource) {
        return new DbStep(DbOperation.SEED, datasource);
    }

    /**
     * Starts a {@code db.cleanup} step (delete-by-{@code testRunId}) against the given datasource alias.
     *
     * @param datasource the logical datasource alias
     * @return a new builder
     */
    public static DbStep cleanup(String datasource) {
        return new DbStep(DbOperation.CLEANUP, datasource);
    }

    /**
     * Starts a {@code db.write} step: a business {@code INSERT} whose effect is undone automatically after
     * the run by a primary-key-scoped compensation registered into the run's undo-log. No {@code testRunId}
     * marker column is required; the row is identified by its primary key ({@link #identifiedBy(String...)}).
     * Compensation is applied per the scenario's {@code CleanupPolicy} (default: on failure). See
     * {@code docs/arch/stand-test-db-rollback-design.md}.
     *
     * @param datasource the logical datasource alias
     * @return a new builder
     */
    public static DbStep write(String datasource) {
        return new DbStep(DbOperation.WRITE, datasource);
    }

    /**
     * Sets an explicit step id (otherwise a readable {@code "<OPERATION> <datasource>"} id is derived).
     *
     * @param id the unique step id
     * @return this builder
     */
    public DbStep id(String id) {
        this.id = requireNonBlank(id, "id");
        return this;
    }

    /**
     * Sets the inline SQL (a single statement). Bind values must use {@code :name} placeholders, never
     * string interpolation.
     *
     * @param inlineSql the SQL statement
     * @return this builder
     */
    public DbStep sql(String inlineSql) {
        this.sql = requireNonBlank(inlineSql, "sql");
        return this;
    }

    /**
     * Sets the SQL (a single statement) from a classpath resource; it is read at execution time.
     *
     * @param classpathResource the classpath resource path
     * @return this builder
     */
    public DbStep sqlFromResource(String classpathResource) {
        this.sqlResource = requireNonBlank(classpathResource, "classpathResource");
        return this;
    }

    /**
     * Adds a named bind value; a {@code String} value may contain {@code ${...}} placeholders, resolved
     * before binding. The value is bound through {@link java.sql.PreparedStatement}, never spliced into
     * the SQL.
     *
     * @param name the bind name (the {@code :name} in the SQL)
     * @param value the value (never null)
     * @return this builder
     */
    public DbStep param(String name, Object value) {
        this.params.put(requireNonBlank(name, "param name"), Objects.requireNonNull(value, "param value must not be null"));
        return this;
    }

    /**
     * Captures a result-set column from the first row into a run variable ({@code db.query} only).
     *
     * @param variableName the variable name
     * @param column the result-set column label
     * @return this builder
     */
    public DbStep capture(String variableName, String column) {
        this.captures.add(new DbCapture(variableName, column));
        return this;
    }

    /**
     * Sets the single value the SELECT's first column must eventually equal ({@code db.expectEventually}
     * only). The comparison is type-aware (numbers by value).
     *
     * @param value the expected value (never null)
     * @return this builder
     */
    public DbStep expectValue(Object value) {
        this.expectedValue = Objects.requireNonNull(value, "expected value must not be null");
        this.expectedValueSet = true;
        return this;
    }

    /**
     * Declares the {@code testRunId} predicate marker: the executor appends {@code WHERE <column> =
     * :testRunId} to the statement (plan §8.8). Valid only on the write steps {@code db.seed} /
     * {@code db.cleanup} (a read needs no {@code testRunId} predicate), and required for {@code db.cleanup};
     * the statement must not carry its own {@code WHERE}.
     *
     * @param column the column the {@code testRunId} predicate binds (a plain identifier)
     * @return this builder
     */
    public DbStep whereTestRunId(String column) {
        String identifier = requireNonBlank(column, "whereTestRunId column");
        if (!SqlIdentifiers.isPlainIdentifier(identifier)) {
            throw new IllegalArgumentException("whereTestRunId column must be a plain identifier, but was: '" + identifier + "'");
        }
        this.whereTestRunIdColumn = identifier;
        return this;
    }

    /**
     * Declares the column a {@code db.seed} INSERT tags with the reserved {@code :testRunId} bind so its
     * rows are reaped by the run's own {@code testRunId}-scoped {@code db.cleanup} — the parallel-isolation
     * guarantee (plan §15). Required on {@code db.seed}: the SQL must list this column in its INSERT column
     * list bound to {@code :testRunId} (for example {@code INSERT INTO test_data.orders(id, test_run_id)
     * VALUES (:id, :testRunId)} with {@code taggedByTestRunId("test_run_id")}); the write-guard verifies the
     * declared column is present, so a seed that references {@code :testRunId} in some other (non-reaped)
     * column is refused rather than silently leaking rows across concurrent runs. Pass the SAME column the
     * paired cleanup filters with {@link #whereTestRunId(String)}.
     *
     * @param column the tag column (a plain identifier)
     * @return this builder
     */
    public DbStep taggedByTestRunId(String column) {
        String identifier = requireNonBlank(column, "taggedByTestRunId column");
        if (!SqlIdentifiers.isPlainIdentifier(identifier)) {
            throw new IllegalArgumentException("taggedByTestRunId column must be a plain identifier, but was: '" + identifier + "'");
        }
        this.seedTestRunIdColumn = identifier;
        return this;
    }

    /**
     * Declares the primary-key column(s) that identify the row written by a {@code db.write} step, so the
     * undo-log can compensate it with {@code DELETE FROM <table> WHERE <pk> = ...}. Each column's value must
     * be supplied as a {@code :<column>} bind in the INSERT (for example {@code identifiedBy("id")} with
     * {@code VALUES(:id, ...)}). Valid only on {@code db.write} and required there in the MVP (a write with
     * no resolvable key is refused, so no un-undoable data reaches the stand).
     *
     * @param columns the primary-key column names (plain identifiers)
     * @return this builder
     */
    public DbStep identifiedBy(String... columns) {
        Objects.requireNonNull(columns, "columns must not be null");
        for (String column : columns) {
            String identifier = requireNonBlank(column, "identifiedBy column");
            if (!SqlIdentifiers.isPlainIdentifier(identifier)) {
                throw new IllegalArgumentException("identifiedBy column must be a plain identifier, but was: '" + identifier + "'");
            }
            this.identifiedBy.add(identifier);
        }
        return this;
    }

    /**
     * Sets the maximum time to wait for a match, in seconds ({@code db.expectEventually} only).
     *
     * @param seconds the timeout in seconds
     * @return this builder
     */
    public DbStep withinSeconds(long seconds) {
        return within(Duration.ofSeconds(seconds));
    }

    /**
     * Sets the maximum time to wait for a match ({@code db.expectEventually} only).
     *
     * @param timeout the timeout (strictly positive)
     * @return this builder
     */
    public DbStep within(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be strictly positive");
        }
        this.timeoutMillis = timeout.toMillis();
        return this;
    }

    /**
     * Sets the poll interval between probes ({@code db.expectEventually} only); defaults to
     * {@link DbStepParameters#DEFAULT_POLL_INTERVAL_MILLIS} when not set.
     *
     * @param pollInterval the poll interval (strictly positive)
     * @return this builder
     */
    public DbStep pollInterval(Duration pollInterval) {
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be strictly positive");
        }
        this.pollIntervalMillis = pollInterval.toMillis();
        return this;
    }

    /**
     * Materialises the immutable core step. Performs no IO.
     *
     * @return the assembled scenario step
     */
    public ScenarioStep build() {
        if (this.sql != null && this.sqlResource != null) {
            throw new IllegalStateException("Set either sql(...) or sqlFromResource(...), not both");
        }
        if (this.sql == null && this.sqlResource == null) {
            throw new IllegalStateException("A DB step requires sql(...) or sqlFromResource(...)");
        }
        validateOperationOptions();
        return new GenericStep(resolveId(), this.operation.stepType(), description(), toParameterMap());
    }

    private void validateOperationOptions() {
        if (this.operation != DbOperation.QUERY && !this.captures.isEmpty()) {
            throw new IllegalStateException("capture(...) applies to db.query, not " + this.operation.stepType());
        }
        if (this.operation != DbOperation.EXPECT_EVENTUALLY && this.expectedValueSet) {
            throw new IllegalStateException("expectValue(...) applies to db.expectEventually, not " + this.operation.stepType());
        }
        if (this.operation != DbOperation.EXPECT_EVENTUALLY && (this.timeoutMillis != null || this.pollIntervalMillis != null)) {
            throw new IllegalStateException("within(...) / pollInterval(...) apply to db.expectEventually, not " + this.operation.stepType());
        }
        if (this.operation == DbOperation.EXPECT_EVENTUALLY && !this.expectedValueSet) {
            throw new IllegalStateException("A db.expectEventually step requires expectValue(...)");
        }
        if (this.operation != DbOperation.SEED && this.operation != DbOperation.CLEANUP && this.whereTestRunIdColumn != null) {
            throw new IllegalStateException("whereTestRunId(...) applies to db.seed/db.cleanup (db.write is undone by primary key, not a testRunId predicate), not " + this.operation.stepType());
        }
        if (this.operation == DbOperation.CLEANUP && this.whereTestRunIdColumn == null) {
            throw new IllegalStateException("A db.cleanup step requires whereTestRunId(...) so it only deletes the run's own data");
        }
        if (this.operation != DbOperation.SEED && this.seedTestRunIdColumn != null) {
            throw new IllegalStateException("taggedByTestRunId(...) applies to db.seed, not " + this.operation.stepType());
        }
        if (this.operation != DbOperation.WRITE && !this.identifiedBy.isEmpty()) {
            throw new IllegalStateException("identifiedBy(...) applies to db.write, not " + this.operation.stepType());
        }
        if (this.operation == DbOperation.WRITE && this.identifiedBy.isEmpty()) {
            throw new IllegalStateException("A db.write step requires identifiedBy(...) so its INSERT can be undone by primary key");
        }
    }

    private String resolveId() {
        return (this.id != null) ? this.id : this.operation.name() + " " + this.datasource;
    }

    private String description() {
        return this.operation.name() + " " + this.datasource;
    }

    private Map<String, Object> toParameterMap() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(DbStepParameters.DATASOURCE, this.datasource);
        if (this.sql != null) {
            parameters.put(DbStepParameters.SQL, this.sql);
        } else {
            parameters.put(DbStepParameters.SQL_RESOURCE, this.sqlResource);
        }
        parameters.put(DbStepParameters.PARAMS, Map.copyOf(this.params));
        if (this.whereTestRunIdColumn != null) {
            parameters.put(DbStepParameters.WHERE_TEST_RUN_ID_COLUMN, this.whereTestRunIdColumn);
        }
        if (this.seedTestRunIdColumn != null) {
            parameters.put(DbStepParameters.SEED_TEST_RUN_ID_COLUMN, this.seedTestRunIdColumn);
        }
        if (!this.identifiedBy.isEmpty()) {
            parameters.put(DbStepParameters.IDENTIFIED_BY, List.copyOf(this.identifiedBy));
        }
        if (this.operation == DbOperation.QUERY) {
            parameters.put(DbStepParameters.CAPTURES, captureMaps());
        }
        if (this.operation == DbOperation.EXPECT_EVENTUALLY) {
            parameters.put(DbStepParameters.EXPECTED_VALUE, this.expectedValue);
            if (this.timeoutMillis != null) {
                parameters.put(DbStepParameters.TIMEOUT_MILLIS, this.timeoutMillis);
            }
            if (this.pollIntervalMillis != null) {
                parameters.put(DbStepParameters.POLL_INTERVAL_MILLIS, this.pollIntervalMillis);
            }
        }
        return parameters;
    }

    private List<Map<String, Object>> captureMaps() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DbCapture capture : this.captures) {
            list.add(Map.of(DbStepParameters.VARIABLE_NAME, capture.variableName(), DbStepParameters.COLUMN, capture.column()));
        }
        return List.copyOf(list);
    }

    private static String requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }
}
