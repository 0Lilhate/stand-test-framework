package ru.alfa.stand.test.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.AwaitResult;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.await.TimeoutDiagnostics;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.ResourceScope;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.validation.ForbiddenOperation;
import ru.alfa.stand.test.core.validation.SqlClassification;
import ru.alfa.stand.test.core.validation.SqlStatementClassifier;
import ru.alfa.stand.test.core.variable.VariableResolver;

/**
 * DB {@link StepExecutor}: the single point of real JDBC IO to a stand (step types {@code db.query} /
 * {@code db.expectEventually} / {@code db.seed} / {@code db.cleanup}).
 *
 * <p>Discovered via {@link java.util.ServiceLoader} (registered in {@code META-INF/services}); the class
 * is public with a public no-arg constructor for that reason. It is stateless and thread-safe — all
 * per-run state arrives through the {@link StepExecutionContext} (the variable store and the run-scoped
 * {@link ResourceScope}), so one instance is safely shared across concurrent runs.
 *
 * <p><strong>Run-scoped connection (plan §8.7).</strong> A JDBC connection is opened lazily on first use
 * and held in the {@code ResourceScope} keyed by datasource alias, so every DB step in a run shares one
 * connection (a {@code db.seed} is visible to a later {@code db.query}/{@code db.expectEventually}) and
 * the runner closes it in the run's {@code finally}. There is no pre-arm hazard as in Kafka, so
 * {@code prepare} stays a no-op.
 *
 * <p><strong>Safety (plan §8.8).</strong> Before any IO the executor runs the core
 * {@link SqlStatementClassifier} through {@link DbWriteGuard} on the exact SQL it is about to send
 * (defense-in-depth: readonly by default; writes only on seed/cleanup, only when {@code writeAllowed},
 * only into whitelisted schemas of schema-qualified tables; {@code UPDATE}/{@code DELETE} require the
 * declared {@code testRunId} predicate; anything unparseable is rejected). Values are bound through
 * {@link NamedParameterStatement} ({@code :name} → {@code ?}), never string-spliced.
 *
 * <p><strong>Failure semantics (plan §8.3).</strong> A {@code db.expectEventually} mismatch/timeout is a
 * {@link StandTestAssertionError} (a JUnit-native failed test); a guard rejection, a whitelist violation,
 * an ambiguous ({@code &gt;1}-row) expect, a {@link SQLException} or a reference-resolution failure is a
 * {@link StandTestException} (infrastructure/config).
 */
public final class DbStepExecutor implements StepExecutor {

    private static final String CONNECTION_KEY_PREFIX = "db.datasource:";
    private static final int TIMEOUT_RENDER_LIMIT = 200;

    private final ReferenceResolver referenceResolver;
    private final ConnectionFactory connectionFactory;
    private final Awaiter awaiter;

    /**
     * Creates an executor with the default environment reference resolver, a {@link DriverManager}-backed
     * connection factory and a system-backed awaiter.
     */
    public DbStepExecutor() {
        this(new EnvironmentReferenceResolver(), new DriverManagerConnectionFactory(), Awaiter.create());
    }

    /**
     * Creates an executor with explicit collaborators (for tests).
     *
     * @param referenceResolver the datasource reference resolver
     * @param connectionFactory the JDBC connection factory
     * @param awaiter the await engine used by the expect poll loop
     */
    DbStepExecutor(ReferenceResolver referenceResolver, ConnectionFactory connectionFactory, Awaiter awaiter) {
        this.referenceResolver = Objects.requireNonNull(referenceResolver, "referenceResolver must not be null");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter must not be null");
    }

    @Override
    public boolean supports(String stepType) {
        return stepType != null && stepType.startsWith(DbStepParameters.TYPE_PREFIX);
    }

    @Override
    public StepResult execute(ScenarioStep step, StepExecutionContext context) {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(context, "context must not be null");
        DbOperation operation = DbOperation.fromStepType(step.type());
        Map<String, Object> parameters = parameters(step);
        String datasourceAlias = DbStepParameters.requireString(parameters, DbStepParameters.DATASOURCE);
        DatasourceDefinition datasource = datasource(context, datasourceAlias);
        String finalSql = assembleSql(parameters);
        boolean testRunIdPredicateDeclared = DbStepParameters.optionalString(parameters, DbStepParameters.WHERE_TEST_RUN_ID_COLUMN).isPresent();
        DbWriteGuard.classifyAndEnforce(finalSql, operation, datasource, testRunIdPredicateDeclared);
        Map<String, Object> binds = binds(parameters, context);
        RunScopedConnection connection = connection(context, datasource, datasourceAlias);
        if (operation == DbOperation.QUERY) {
            return executeQuery(step, context, parameters, finalSql, binds, datasourceAlias, connection);
        }
        if (operation == DbOperation.EXPECT_EVENTUALLY) {
            return executeExpect(step, parameters, finalSql, binds, datasourceAlias, connection);
        }
        return executeWrite(step, operation, finalSql, binds, datasourceAlias, connection);
    }

    private StepResult executeQuery(
            ScenarioStep step,
            StepExecutionContext context,
            Map<String, Object> parameters,
            String finalSql,
            Map<String, Object> binds,
            String datasourceAlias,
            RunScopedConnection connection) {
        Instant startedAt = Instant.now();
        List<DbCapture> captures = DbStepParameters.captures(parameters);
        NamedParameterStatement statement = NamedParameterStatement.parse(finalSql);
        try (PreparedStatement prepared = statement.create(connection.connection(), binds); ResultSet rows = prepared.executeQuery()) {
            if (!captures.isEmpty()) {
                if (!rows.next()) {
                    throw new StandTestException("db.query on '" + datasourceAlias + "' captured columns but the SELECT returned no rows");
                }
                applyCaptures(captures, rows, context, datasourceAlias);
            }
        } catch (SQLException failure) {
            throw new StandTestException("db.query failed on datasource '" + datasourceAlias + "': " + failure.getMessage(), failure);
        }
        return querySuccess(step, startedAt, datasourceAlias, captures);
    }

    private StepResult executeExpect(
            ScenarioStep step,
            Map<String, Object> parameters,
            String finalSql,
            Map<String, Object> binds,
            String datasourceAlias,
            RunScopedConnection connection) {
        Instant startedAt = Instant.now();
        Object expected = DbStepParameters.requireExpectedValue(parameters);
        NamedParameterStatement statement = NamedParameterStatement.parse(finalSql);
        Duration timeout = Duration.ofMillis(DbStepParameters.positiveMillis(parameters, DbStepParameters.TIMEOUT_MILLIS, DbStepParameters.DEFAULT_TIMEOUT_MILLIS));
        Duration pollInterval = Duration.ofMillis(DbStepParameters.positiveMillis(parameters, DbStepParameters.POLL_INTERVAL_MILLIS, DbStepParameters.DEFAULT_POLL_INTERVAL_MILLIS));
        Object[] lastObserved = {"<no rows>"};
        AwaitPolicy policy = AwaitPolicy.builder("db.expectEventually " + datasourceAlias)
                .timeout(timeout)
                .pollInterval(pollInterval)
                .ignoreExceptions(false)
                .build();
        AwaitResult<Optional<Object>> result = this.awaiter.await(
                policy,
                () -> probe(connection, statement, binds, lastObserved),
                observed -> observed.isPresent() && DbValues.valuesMatch(expected, observed.get()));
        Object value = result
                .orElseThrow(diagnostics -> expectTimeout(diagnostics, datasourceAlias, finalSql, expected, lastObserved[0]))
                .orElseThrow();
        return expectSuccess(step, startedAt, datasourceAlias, value);
    }

    private StepResult executeWrite(
            ScenarioStep step,
            DbOperation operation,
            String finalSql,
            Map<String, Object> binds,
            String datasourceAlias,
            RunScopedConnection connection) {
        Instant startedAt = Instant.now();
        NamedParameterStatement statement = NamedParameterStatement.parse(finalSql);
        int rowsAffected;
        try (PreparedStatement prepared = statement.create(connection.connection(), binds)) {
            rowsAffected = prepared.executeUpdate();
        } catch (SQLException failure) {
            throw new StandTestException(operation.stepType() + " failed on datasource '" + datasourceAlias + "': " + failure.getMessage(), failure);
        }
        return writeSuccess(step, operation, startedAt, datasourceAlias, rowsAffected);
    }

    private Optional<Object> probe(RunScopedConnection connection, NamedParameterStatement statement, Map<String, Object> binds, Object[] lastObserved) {
        try (PreparedStatement prepared = statement.create(connection.connection(), binds); ResultSet rows = prepared.executeQuery()) {
            if (!rows.next()) {
                lastObserved[0] = "<no rows>";
                return Optional.empty();
            }
            Object value = rows.getObject(1);
            if (rows.next()) {
                throw new StandTestException("db.expectEventually expected a single row but the SELECT returned more than one (ambiguous)");
            }
            lastObserved[0] = DbValues.render(value);
            return Optional.ofNullable(value);
        } catch (SQLException failure) {
            throw new StandTestException("db.expectEventually query failed: " + failure.getMessage(), failure);
        }
    }

    private void applyCaptures(List<DbCapture> captures, ResultSet rows, StepExecutionContext context, String datasourceAlias) throws SQLException {
        for (DbCapture capture : captures) {
            Object value = rows.getObject(capture.column());
            if (value == null) {
                throw new StandTestException("db.query on '" + datasourceAlias + "' captured column '" + capture.column() + "' which is null; cannot store variable '" + capture.variableName() + "'");
            }
            context.variableStore().put(capture.variableName(), value);
        }
    }

    private RunScopedConnection connection(StepExecutionContext context, DatasourceDefinition datasource, String alias) {
        ResourceScope scope = context.resourceScope();
        String key = CONNECTION_KEY_PREFIX + alias;
        if (scope.contains(key)) {
            return scope.get(key)
                    .filter(RunScopedConnection.class::isInstance)
                    .map(RunScopedConnection.class::cast)
                    .orElseThrow(() -> new StandTestException("Run-scoped resource '" + key + "' is not a JDBC connection"));
        }
        ResolvedDatasource resolved = resolve(datasource);
        Connection raw;
        try {
            raw = this.connectionFactory.open(resolved);
        } catch (SQLException failure) {
            throw new StandTestException("Failed to open a JDBC connection for datasource '" + alias + "': " + failure.getMessage(), failure);
        }
        // Register the connection before configuring it, so the run-scoped ResourceScope owns it and
        // closeAll() releases it even if setAutoCommit below throws — otherwise the open connection would leak
        // (neither closed here nor tracked for the run's finally, plan §8.7).
        RunScopedConnection connection = new RunScopedConnection(raw, alias);
        scope.register(key, connection);
        try {
            // Make the seed -> query/expectEventually visibility contract explicit rather than relying on an
            // unenforced driver default: each statement commits on its own so a later step (and the test's own
            // verification connection) sees a seed/cleanup's effect.
            raw.setAutoCommit(true);
        } catch (SQLException failure) {
            throw new StandTestException("Failed to configure the JDBC connection for datasource '" + alias + "': " + failure.getMessage(), failure);
        }
        return connection;
    }

    private ResolvedDatasource resolve(DatasourceDefinition datasource) {
        // url/user must resolve to a non-blank value; a blank one is a stand misconfiguration (e.g. an empty
        // env var) and must surface as a StandTestException (config, plan §8.3), not as the record's
        // low-level IllegalArgumentException. The password may legitimately be empty, so it is not required.
        String url = resolveRequired(datasource, datasource.urlRef(), "urlRef");
        String user = resolveRequired(datasource, datasource.userRef(), "userRef");
        String password = this.referenceResolver.resolve(datasource.passwordRef());
        return new ResolvedDatasource(url, user, password);
    }

    private String resolveRequired(DatasourceDefinition datasource, String reference, String field) {
        String value = this.referenceResolver.resolve(reference);
        if (value == null || value.isBlank()) {
            throw new StandTestException("Datasource '" + datasource.alias() + "' " + field + " '" + reference
                    + "' resolved to a blank value");
        }
        return value;
    }

    private Map<String, Object> binds(Map<String, Object> parameters, StepExecutionContext context) {
        VariableResolver resolver = context.resolver();
        Map<String, Object> binds = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : DbStepParameters.bindValues(parameters).entrySet()) {
            Object value = entry.getValue();
            binds.put(entry.getKey(), (value instanceof String text) ? resolver.resolve(text) : value);
        }
        // Reserved bind (plan §8.8): :testRunId is always available and parameterized, so seed tagging and
        // the whereTestRunId predicate bind to the run's id rather than being string-spliced.
        binds.put("testRunId", context.scenarioContext().testRunId().value());
        return binds;
    }

    private static String assembleSql(Map<String, Object> parameters) {
        String base = readBaseSql(parameters);
        String whereColumn = DbStepParameters.optionalString(parameters, DbStepParameters.WHERE_TEST_RUN_ID_COLUMN).orElse(null);
        if (whereColumn == null) {
            return base;
        }
        if (!SqlIdentifiers.isPlainIdentifier(whereColumn)) {
            throw new StandTestException("whereTestRunId column must be a plain identifier, but was: '" + whereColumn + "'");
        }
        SqlClassification authorClassification = SqlStatementClassifier.classify(base);
        if (authorClassification.containsWhereClause()) {
            throw new StandTestException("A step using whereTestRunId(...) must not carry its own WHERE clause — the testRunId predicate is the single source of the WHERE (plan §8.8)");
        }
        String trimmed = stripTrailingSemicolon(base.strip());
        // Append the predicate on a fresh line: a trailing line comment (`--`) in the author SQL would
        // otherwise swallow a same-line WHERE and silently neutralise the testRunId scoping. The guard
        // re-checks the assembled SQL to fail closed on any remaining neutralisation (e.g. a trailing
        // unterminated block comment / string literal).
        return trimmed + "\nWHERE " + whereColumn + " = :testRunId";
    }

    private static String readBaseSql(Map<String, Object> parameters) {
        Optional<String> inline = DbStepParameters.optionalString(parameters, DbStepParameters.SQL);
        Optional<String> resource = DbStepParameters.optionalString(parameters, DbStepParameters.SQL_RESOURCE);
        if (inline.isPresent() && resource.isPresent()) {
            throw new StandTestException("A DB step must set either '" + DbStepParameters.SQL + "' or '" + DbStepParameters.SQL_RESOURCE + "', not both");
        }
        if (resource.isPresent()) {
            String content = readResource(resource.get());
            if (content.isBlank()) {
                throw new StandTestException("SQL resource '" + resource.get() + "' is empty");
            }
            return content;
        }
        return inline.orElseThrow(() -> new StandTestException("A DB step requires '" + DbStepParameters.SQL + "' or '" + DbStepParameters.SQL_RESOURCE + "'"));
    }

    private static String stripTrailingSemicolon(String sql) {
        String result = sql;
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).strip();
        }
        return result;
    }

    private static String readResource(String resourcePath) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = DbStepExecutor.class.getClassLoader();
        }
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new StandTestException("SQL resource not found on classpath: '" + resourcePath + "'");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StandTestException("Failed to read SQL resource '" + resourcePath + "'", failure);
        }
    }

    private static DatasourceDefinition datasource(StepExecutionContext context, String alias) {
        EnvironmentDefinition environment = environment(context);
        return environment.datasource(alias)
                .orElseThrow(() -> new StandTestException("Datasource '" + alias + "' is not whitelisted in environment '"
                        + context.scenarioContext().environment() + "' [" + ForbiddenOperation.NON_WHITELISTED_DATASOURCE.code() + "]"));
    }

    private static EnvironmentDefinition environment(StepExecutionContext context) {
        String environment = context.scenarioContext().environment();
        return context.environmentRegistry().environment(environment)
                .orElseThrow(() -> new StandTestException("Environment '" + environment + "' is not whitelisted"));
    }

    private static Map<String, Object> parameters(ScenarioStep step) {
        if (step instanceof GenericStep generic) {
            return generic.parameters();
        }
        throw new StandTestException("DbStepExecutor requires a GenericStep produced by DbStep, but got: " + step.getClass().getName());
    }

    private static StandTestAssertionError expectTimeout(TimeoutDiagnostics diagnostics, String datasourceAlias, String sql, Object expected, Object lastObserved) {
        return new StandTestAssertionError("db.expectEventually '" + datasourceAlias + "' did not observe the expected value: " + diagnostics.summary()
                + " (datasource=" + datasourceAlias + ", expected=" + DbValues.render(expected) + ", lastObserved=" + lastObserved
                + ", sql=" + truncate(sql) + ")");
    }

    private static String truncate(String sql) {
        String collapsed = sql.replaceAll("\\s+", " ").strip();
        return (collapsed.length() <= TIMEOUT_RENDER_LIMIT) ? collapsed : collapsed.substring(0, TIMEOUT_RENDER_LIMIT) + "...";
    }

    private static StepResult querySuccess(ScenarioStep step, Instant startedAt, String datasourceAlias, List<DbCapture> captures) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("db.operation", "query");
        diagnostics.put("db.datasource", datasourceAlias);
        diagnostics.put("db.captured", captures.stream().map(DbCapture::variableName).toList());
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, Instant.now(), null, diagnostics);
    }

    private static StepResult expectSuccess(ScenarioStep step, Instant startedAt, String datasourceAlias, Object value) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("db.operation", "expectEventually");
        diagnostics.put("db.datasource", datasourceAlias);
        diagnostics.put("db.value", DbValues.render(value));
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, Instant.now(), null, diagnostics);
    }

    private static StepResult writeSuccess(ScenarioStep step, DbOperation operation, Instant startedAt, String datasourceAlias, int rowsAffected) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("db.operation", operation == DbOperation.SEED ? "seed" : "cleanup");
        diagnostics.put("db.datasource", datasourceAlias);
        diagnostics.put("db.rowsAffected", rowsAffected);
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, Instant.now(), null, diagnostics);
    }
}
