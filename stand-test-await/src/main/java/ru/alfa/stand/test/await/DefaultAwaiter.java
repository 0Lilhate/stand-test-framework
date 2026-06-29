package ru.alfa.stand.test.await;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link Awaiter}: a minimal, transport-agnostic polling loop with no third-party engine.
 *
 * <p>Time is read and advanced exclusively through an injected {@link TimeSource}, so timeout
 * behaviour is fully deterministic under test (a fake source advances on {@code sleep} instead of
 * blocking). The probe and predicate are evaluated on the calling thread. The deadline is computed
 * once from a monotonic reading; once past the optional initial {@code pollDelay}, each poll waits
 * the lesser of the poll interval and the remaining time, so the poll loop never overshoots the
 * timeout by more than one scheduling quantum. The initial {@code pollDelay} is honoured in full and
 * is therefore additive to the timeout budget. Deadline checks use overflow-safe difference
 * arithmetic, per the {@link System#nanoTime()} contract.
 */
public final class DefaultAwaiter implements Awaiter {

    private final TimeSource timeSource;

    /**
     * Creates an awaiter backed by the {@link TimeSource#system() system time source}.
     */
    public DefaultAwaiter() {
        this(TimeSource.system());
    }

    /**
     * Creates an awaiter backed by the given time source.
     *
     * @param timeSource the time source used for measuring and waiting
     */
    public DefaultAwaiter(TimeSource timeSource) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource must not be null");
    }

    @Override
    public <T> AwaitResult<T> await(AwaitPolicy policy, Supplier<T> probe, Predicate<? super T> condition) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(probe, "probe must not be null");
        Objects.requireNonNull(condition, "condition must not be null");

        long start = timeSource.nanoTime();
        long deadline = start + policy.timeout().toNanos();
        sleepNanos(policy.pollDelay().toNanos(), policy);

        int attempts = 0;
        T lastValue = null;
        Throwable lastError = null;
        while (true) {
            attempts++;
            try {
                T observed = probe.get();
                lastValue = observed;
                if (condition.test(observed)) {
                    return AwaitResult.satisfied(observed, attempts, elapsedSince(start));
                }
            } catch (RuntimeException ex) {
                if (!policy.ignoreExceptions()) {
                    throw ex;
                }
                lastError = ex;
            }
            long now = timeSource.nanoTime();
            if (now - deadline >= 0) {
                break;
            }
            sleepNanos(Math.min(policy.pollInterval().toNanos(), deadline - now), policy);
        }

        Duration elapsed = elapsedSince(start);
        TimeoutDiagnostics diagnostics = new TimeoutDiagnostics(
                policy.description(), policy.timeout(), policy.pollInterval(), attempts, elapsed, lastValue, render(lastError), Map.of());
        return AwaitResult.timedOut(lastValue, attempts, elapsed, lastError, diagnostics);
    }

    private Duration elapsedSince(long start) {
        return Duration.ofNanos(timeSource.nanoTime() - start);
    }

    private void sleepNanos(long nanos, AwaitPolicy policy) {
        if (nanos <= 0) {
            return;
        }
        try {
            timeSource.sleep(nanos);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StandTestException("await '" + policy.description() + "' was interrupted", ex);
        }
    }

    private static String render(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        return (message == null) ? error.getClass().getName() : error.getClass().getName() + ": " + message;
    }
}
