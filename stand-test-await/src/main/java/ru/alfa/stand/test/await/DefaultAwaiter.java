package ru.alfa.stand.test.await;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link Awaiter}: a minimal, transport-agnostic polling loop with no third-party engine.
 *
 * <p>Time is read and advanced exclusively through an injected {@link TimeSource}, so timeout
 * behaviour is fully deterministic under test (a fake source advances on {@code sleep} instead of
 * blocking). The probe and predicate are evaluated on the calling thread. The deadline is computed
 * once from a monotonic reading; each poll then waits the lesser of the poll interval and the
 * remaining time, so the loop never overshoots the timeout by more than one scheduling quantum.
 * Deadline checks use overflow-safe difference arithmetic, per the {@link System#nanoTime()} contract.
 *
 * <p><strong>The {@code pollDelay} is spent out of the timeout, not added to it.</strong> The deadline
 * is fixed before the initial delay is slept, so a policy of 10 s with a 2 s delay waits 10 s in total
 * and polls for 8 of them — not 12. The delay itself is always honoured in full, so one exceeding the
 * timeout yields exactly one probe, performed after the deadline has already passed.
 */
public final class DefaultAwaiter implements Awaiter {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAwaiter.class);

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
                    LOG.debug("await '{}' resolved in {} attempt(s)", policy.description(), attempts);
                    return AwaitResult.satisfied(observed, attempts, elapsedSince(start));
                }
            } catch (RuntimeException ex) {
                if (!policy.ignoreExceptions()) {
                    throw ex;
                }
                lastError = ex;
            }
            // Metadata only: the polled value and any error can carry sensitive payloads, so they are never logged.
            LOG.debug("await '{}' attempt {}: not satisfied", policy.description(), attempts);
            long now = timeSource.nanoTime();
            if (now - deadline >= 0) {
                break;
            }
            sleepNanos(Math.min(policy.pollInterval().toNanos(), deadline - now), policy);
        }

        Duration elapsed = elapsedSince(start);
        LOG.debug("await '{}' timed out: {} attempt(s), elapsed {}, pollInterval {}, lastError {}", policy.description(), attempts,
                elapsed, policy.pollInterval(), lastError == null ? "none" : lastError.getClass().getSimpleName());
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
