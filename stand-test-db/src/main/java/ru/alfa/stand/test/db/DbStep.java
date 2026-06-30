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
    private String id;
    private String sql;
    private String sqlResource;
    private Object expectedValue;
    private boolean expectedValueSet;
    private String whereTestRunIdColumn;
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
        if (!this.operation.isWrite() && this.whereTestRunIdColumn != null) {
            throw new IllegalStateException("whereTestRunId(...) applies to db.seed/db.cleanup (a read needs no testRunId predicate), not " + this.operation.stepType());
        }
        if (this.operation == DbOperation.CLEANUP && this.whereTestRunIdColumn == null) {
            throw new IllegalStateException("A db.cleanup step requires whereTestRunId(...) so it only deletes the run's own data");
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
