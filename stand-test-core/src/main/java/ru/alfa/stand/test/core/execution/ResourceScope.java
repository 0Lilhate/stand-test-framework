package ru.alfa.stand.test.core.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Run-scoped, keyed registry of {@link AutoCloseable} resources owned by the {@link ScenarioRunner}
 * (plan §8.7).
 *
 * <p>It is deliberately separate from the {@code VariableStore}: the variable store holds value
 * objects (coerced to {@code String}), whereas a {@code ResourceScope} holds live {@code AutoCloseable}
 * resources bound to the run — for example a Kafka consumer pre-armed in
 * {@link StepExecutor#prepare(ru.alfa.stand.test.core.scenario.ScenarioStep, StepExecutionContext)
 * prepare} and reused (and advanced) across the {@code execute} of every {@code kafka.expect} step on
 * the same topic. The key is adapter-chosen and, by convention, namespaced with a module prefix
 * ({@code db.datasource:}, {@code kafka.consumer:}, {@code grpc.channel:} + the logical alias) so
 * {@code prepare} and all matching {@code execute} calls deterministically share one resource while
 * identically-named aliases of different adapters can never collide within one run.
 *
 * <p>One {@code ResourceScope} belongs to exactly one scenario run; the runner calls {@link #closeAll()}
 * in a {@code finally} block, so consumers and connections never leak even when a step throws. It is a
 * plain instance — not static, not global, not thread-local — so parallel runs stay isolated. It is not
 * itself thread-safe: a single run is driven on one thread, consistent with the thread-confinement of
 * the awaiter and of {@code KafkaConsumer}.
 */
public final class ResourceScope implements AutoCloseable {

    private final Map<String, AutoCloseable> resources = new LinkedHashMap<>();

    /**
     * Registers a resource under the given key.
     *
     * @param key the non-blank resource key (for example a topic alias)
     * @param resource the resource to own and later close
     * @throws StandTestException if a resource is already registered under the key
     */
    public void register(String key, AutoCloseable resource) {
        String validKey = requireValidKey(key);
        Objects.requireNonNull(resource, "resource must not be null");
        if (this.resources.containsKey(validKey)) {
            throw new StandTestException("A resource is already registered under key '" + validKey + "'");
        }
        this.resources.put(validKey, resource);
    }

    /**
     * Returns the resource registered under the given key, if any.
     *
     * @param key the non-blank resource key
     * @return the resource, or empty if none is registered
     */
    public Optional<AutoCloseable> get(String key) {
        return Optional.ofNullable(this.resources.get(requireValidKey(key)));
    }

    /**
     * Returns whether a resource is registered under the given key.
     *
     * @param key the non-blank resource key
     * @return true if a resource is registered
     */
    public boolean contains(String key) {
        return this.resources.containsKey(requireValidKey(key));
    }

    /**
     * Closes all registered resources and clears the scope. Every resource is attempted even if an
     * earlier close throws; the first failure is rethrown (with the rest attached as suppressed) after
     * all have been attempted, so a single faulty close cannot leak the others.
     */
    public void closeAll() {
        List<Throwable> failures = new ArrayList<>();
        for (AutoCloseable resource : this.resources.values()) {
            try {
                resource.close();
            } catch (Exception failure) {
                failures.add(failure);
            }
        }
        this.resources.clear();
        if (!failures.isEmpty()) {
            StandTestException aggregate = new StandTestException("Failed to close " + failures.size() + " run-scoped resource(s)",
                    failures.get(0));
            for (int index = 1; index < failures.size(); index++) {
                aggregate.addSuppressed(failures.get(index));
            }
            throw aggregate;
        }
    }

    /**
     * Equivalent to {@link #closeAll()}, so a {@code ResourceScope} can be used in a
     * try-with-resources block.
     */
    @Override
    public void close() {
        closeAll();
    }

    private static String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("resource key must not be blank");
        }
        return key;
    }
}
