package ru.alfa.stand.test.core.compensation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Per-run, append-only ordered registry of {@link Compensator}s.
 *
 * <p>Owned by the runner and threaded through {@code StepExecutionContext} exactly like the run's
 * {@code VariableStore}/{@code ResourceScope} — a plain instance, <strong>not</strong> static, global or
 * {@code ThreadLocal}, so parallel runs stay isolated. It is not itself thread-safe: a single run is
 * driven on one thread. Adapters append during step execution; the runner drains it once, in reverse
 * registration order, in its {@code finally}.
 */
public final class UndoLog {

    private final List<Compensator> compensators = new ArrayList<>();

    /**
     * Registers a compensator. The last-registered is compensated first (reverse order), so a later
     * write is undone before the earlier write it may depend on.
     *
     * @param compensator the compensator to register
     */
    public void register(Compensator compensator) {
        compensators.add(Objects.requireNonNull(compensator, "compensator must not be null"));
    }

    /**
     * @return {@code true} if nothing has been registered
     */
    public boolean isEmpty() {
        return compensators.isEmpty();
    }

    /**
     * @return the number of registered compensators
     */
    public int size() {
        return compensators.size();
    }

    /**
     * Returns a snapshot of the registered compensators in reverse registration order (drain order).
     * Uses a copy + {@link Collections#reverse} (no {@code List.reversed()} — the build targets
     * {@code --release 17}).
     *
     * @return a new list, last-registered first
     */
    public List<Compensator> inReverseOrder() {
        List<Compensator> copy = new ArrayList<>(compensators);
        Collections.reverse(copy);
        return copy;
    }
}
