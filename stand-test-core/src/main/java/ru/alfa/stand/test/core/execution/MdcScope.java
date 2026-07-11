package ru.alfa.stand.test.core.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * A scoped set of SLF4J {@link MDC} keys applied for the duration of a try-with-resources block and
 * <strong>restored</strong> (not blindly cleared) on {@link #close()}.
 *
 * <p>The runner stamps run identity ({@code scenarioId}/{@code testRunId}/{@code correlationId}/
 * {@code environment}) and per-step identity ({@code stepId}/{@code stepType}/{@code stepIndex}) into
 * the MDC so every log line emitted while a step runs — the SDK's own, the adapters', and the
 * system-under-test client code on the same thread — carries the correlation context (plan §17).
 *
 * <p>Because {@code MDC} is thread-local and the {@code ScenarioRunner} is a shared singleton invoked
 * concurrently, each scope snapshots the previous value of every key it touches and restores it on
 * close. Restoring (rather than removing) keeps a caller-supplied MDC intact and makes nested scopes
 * (scenario → step) compose correctly.
 */
final class MdcScope implements AutoCloseable {

    static final String SCENARIO_ID = "scenarioId";

    static final String TEST_RUN_ID = "testRunId";

    static final String CORRELATION_ID = "correlationId";

    static final String ENVIRONMENT = "environment";

    static final String STEP_ID = "stepId";

    static final String STEP_TYPE = "stepType";

    static final String STEP_INDEX = "stepIndex";

    private final Map<String, String> previousValues;

    private MdcScope(Map<String, String> previousValues) {
        this.previousValues = previousValues;
    }

    /**
     * Applies the given key/value pairs to the MDC and returns a scope that restores the prior state
     * on {@link #close()}. A {@code null} value removes the key for the scope's duration.
     *
     * @param values the MDC keys to apply for this scope
     * @return an open scope that restores the previous MDC state when closed
     */
    static MdcScope of(Map<String, String> values) {
        Objects.requireNonNull(values, "values must not be null");
        Map<String, String> previousValues = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            previousValues.put(key, MDC.get(key));
            String value = entry.getValue();
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
        return new MdcScope(previousValues);
    }

    @Override
    public void close() {
        for (Map.Entry<String, String> entry : previousValues.entrySet()) {
            String priorValue = entry.getValue();
            if (priorValue == null) {
                MDC.remove(entry.getKey());
            } else {
                MDC.put(entry.getKey(), priorValue);
            }
        }
    }
}
