package ru.alfa.stand.test.core.context;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;

/**
 * Immutable metadata of a single scenario run.
 *
 * <p>This is metadata only — it must not be used as a mutable variable storage. Runtime variables
 * captured during execution belong in {@link ru.alfa.stand.test.core.variable.VariableStore}. The
 * {@code tags} set is defensively copied and exposed as an immutable set.
 *
 * @param scenarioId the scenario identifier
 * @param testRunId the run identifier
 * @param correlationId the SDK-owned correlation id
 * @param environment the logical environment name (never blank)
 * @param tags an immutable set of free-form labels
 * @param createdAt the instant this context was created
 */
public record ScenarioContext(
        ScenarioId scenarioId,
        TestRunId testRunId,
        CorrelationId correlationId,
        String environment,
        Set<String> tags,
        Instant createdAt) {

    public ScenarioContext {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(testRunId, "testRunId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("environment must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        tags = (tags == null) ? Set.of() : Set.copyOf(tags);
    }

    /**
     * Starts a new context for the given scenario and environment, generating a fresh
     * {@link TestRunId}, {@link CorrelationId} and creation instant.
     *
     * @param scenarioId the scenario identifier
     * @param environment the logical environment name (never blank)
     * @return a new scenario context
     */
    public static ScenarioContext start(ScenarioId scenarioId, String environment) {
        return start(scenarioId, environment, Set.of());
    }

    /**
     * Starts a new context for the given scenario, environment and tags, generating a fresh
     * {@link TestRunId}, {@link CorrelationId} and creation instant from the system UTC clock.
     *
     * @param scenarioId the scenario identifier
     * @param environment the logical environment name (never blank)
     * @param tags free-form labels for the run
     * @return a new scenario context
     */
    public static ScenarioContext start(ScenarioId scenarioId, String environment, Set<String> tags) {
        return start(scenarioId, environment, tags, Clock.systemUTC());
    }

    /**
     * Starts a new context with an explicit clock for the creation instant, so a runner driving its
     * timestamps from an injected {@link Clock} keeps {@code createdAt} consistent (and deterministic
     * under a fixed clock in tests).
     *
     * @param scenarioId the scenario identifier
     * @param environment the logical environment name (never blank)
     * @param tags free-form labels for the run
     * @param clock the clock supplying {@code createdAt}
     * @return a new scenario context
     */
    public static ScenarioContext start(ScenarioId scenarioId, String environment, Set<String> tags, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new ScenarioContext(
                scenarioId,
                TestRunId.generate(),
                CorrelationId.generate(),
                environment,
                tags,
                clock.instant());
    }
}
