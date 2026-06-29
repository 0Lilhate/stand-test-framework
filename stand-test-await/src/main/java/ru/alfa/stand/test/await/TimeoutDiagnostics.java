package ru.alfa.stand.test.await;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable diagnostics describing why an await did not succeed in time.
 *
 * <p>Captures everything needed to explain a timeout without re-running it: the await description, the
 * configured {@code timeout}/{@code pollInterval}, how many times the probe was attempted, the elapsed
 * wall time, the last value observed (may be null) and the last error message (may be null when no
 * probe threw). The free-form {@code attributes} map carries additional reporting context that the
 * await primitive itself does not own — typically {@code scenarioId}/{@code testRunId}/
 * {@code correlationId} and any probe-supplied details — so identity flows into reports without
 * coupling the engine to scenario metadata.
 *
 * <p>{@link #toMap()} renders the diagnostics as a flat map suitable for a
 * {@code StepResult}/{@code StepEvent} diagnostics map; {@link #summary()} renders a single-line
 * message for exceptions.
 *
 * @param description the human-readable name of what was being awaited
 * @param timeout the configured maximum total wait
 * @param pollInterval the configured wait between probes
 * @param attempts the number of probe attempts performed (at least one)
 * @param elapsed the elapsed wall time when the await gave up
 * @param lastValue the last value observed from the probe (may be null)
 * @param lastError the last error message captured from a throwing probe (may be null)
 * @param attributes an immutable map of additional reporting context
 */
public record TimeoutDiagnostics(
        String description,
        Duration timeout,
        Duration pollInterval,
        int attempts,
        Duration elapsed,
        Object lastValue,
        String lastError,
        Map<String, Object> attributes) {

    public TimeoutDiagnostics {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
        attributes = (attributes == null) ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * Returns a copy of these diagnostics with an additional reporting attribute.
     *
     * @param key the attribute key (non-blank)
     * @param value the attribute value (non-null)
     * @return a new diagnostics instance carrying the extra attribute
     */
    public TimeoutDiagnostics withAttribute(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("attribute key must not be blank");
        }
        Objects.requireNonNull(value, "attribute value must not be null");
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new TimeoutDiagnostics(description, timeout, pollInterval, attempts, elapsed, lastValue, lastError, merged);
    }

    /**
     * Renders the diagnostics as a flat, ordered, immutable map for reporting. Null {@code lastValue}
     * and {@code lastError} are omitted; {@code attributes} are appended last and can never overwrite
     * an engine-owned intrinsic key (a colliding attribute is dropped).
     *
     * @return an immutable map view of the diagnostics
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("await", description);
        map.put("timeout", timeout.toString());
        map.put("pollInterval", pollInterval.toString());
        map.put("attempts", attempts);
        map.put("elapsed", elapsed.toString());
        if (lastValue != null) {
            map.put("lastValue", String.valueOf(lastValue));
        }
        if (lastError != null) {
            map.put("lastError", lastError);
        }
        attributes.forEach(map::putIfAbsent);
        return Collections.unmodifiableMap(map);
    }

    /**
     * Renders a single-line human-readable summary, suitable for an exception message.
     *
     * @return a one-line summary of the timeout
     */
    public String summary() {
        StringBuilder builder = new StringBuilder()
                .append("await '").append(description).append("' not satisfied within ").append(timeout)
                .append(" (attempts=").append(attempts)
                .append(", elapsed=").append(elapsed)
                .append(", pollInterval=").append(pollInterval);
        if (lastValue != null) {
            builder.append(", lastValue=").append(lastValue);
        }
        if (lastError != null) {
            builder.append(", lastError=").append(lastError);
        }
        return builder.append(')').toString();
    }
}
