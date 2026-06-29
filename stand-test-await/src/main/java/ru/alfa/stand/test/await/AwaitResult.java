package ru.alfa.stand.test.await;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Immutable outcome of an await.
 *
 * <p>An await never throws on timeout by itself: it returns a result the caller inspects, so the await
 * primitive stays agnostic about whether a missed effect is an assertion failure or an infrastructure
 * problem (the adapter assigns that meaning). When {@link #satisfied()} is {@code true}, {@link #value()}
 * is the value that satisfied the predicate; when {@code false}, {@link #value()} is the last value
 * observed (may be null) and {@link #timeoutDiagnostics()} explains the timeout.
 *
 * @param <T> the awaited value type
 * @param satisfied whether the predicate was satisfied before the timeout
 * @param value the satisfying value, or the last observed value on timeout (may be null)
 * @param attempts the number of probe attempts performed (at least one)
 * @param elapsed the elapsed wall time
 * @param lastError the last error thrown by the probe/predicate, if any (may be null)
 * @param timeoutDiagnostics the timeout diagnostics, non-null when not satisfied, null otherwise
 */
public record AwaitResult<T>(
        boolean satisfied,
        T value,
        int attempts,
        Duration elapsed,
        Throwable lastError,
        TimeoutDiagnostics timeoutDiagnostics) {

    public AwaitResult {
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
        if (satisfied) {
            if (timeoutDiagnostics != null) {
                throw new IllegalArgumentException("a satisfied result must not carry timeout diagnostics");
            }
            if (lastError != null) {
                throw new IllegalArgumentException("a satisfied result must not carry a last error");
            }
        } else {
            Objects.requireNonNull(timeoutDiagnostics, "timeoutDiagnostics must not be null for a timed-out result");
        }
    }

    /**
     * Creates a satisfied result.
     *
     * @param <T> the awaited value type
     * @param value the value that satisfied the predicate
     * @param attempts the number of probe attempts performed
     * @param elapsed the elapsed wall time
     * @return a satisfied result
     */
    public static <T> AwaitResult<T> satisfied(T value, int attempts, Duration elapsed) {
        return new AwaitResult<>(true, value, attempts, elapsed, null, null);
    }

    /**
     * Creates a timed-out result.
     *
     * @param <T> the awaited value type
     * @param lastValue the last value observed (may be null)
     * @param attempts the number of probe attempts performed
     * @param elapsed the elapsed wall time
     * @param lastError the last error thrown by the probe/predicate, if any (may be null)
     * @param diagnostics the timeout diagnostics
     * @return a timed-out result
     */
    public static <T> AwaitResult<T> timedOut(T lastValue, int attempts, Duration elapsed, Throwable lastError, TimeoutDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        return new AwaitResult<>(false, lastValue, attempts, elapsed, lastError, diagnostics);
    }

    /**
     * Returns the satisfying value, or throws the throwable produced by the given mapper if the await
     * timed out. The probe's last error, when present and the produced throwable has no cause of its
     * own, is attached as the cause.
     *
     * <p>The mapper may produce any throwable type — typically a {@code StandTestAssertionError} (so
     * JUnit reports a failed assertion) or a {@link StandTestException} (for an infrastructure
     * problem).
     *
     * @param <X> the type of throwable raised on timeout
     * @param onTimeout maps the timeout diagnostics to the throwable to raise
     * @return the satisfying value
     * @throws X if the await timed out
     */
    public <X extends Throwable> T orElseThrow(Function<TimeoutDiagnostics, ? extends X> onTimeout) throws X {
        Objects.requireNonNull(onTimeout, "onTimeout must not be null");
        if (satisfied) {
            return value;
        }
        X exception = onTimeout.apply(timeoutDiagnostics);
        if (lastError != null && exception != lastError && exception.getCause() == null) {
            exception.initCause(lastError);
        }
        throw exception;
    }

    /**
     * Returns the satisfying value, or throws a {@link StandTestException} carrying the timeout
     * {@link TimeoutDiagnostics#summary() summary} if the await timed out.
     *
     * <p>Adapters expecting a business effect should prefer
     * {@link #orElseThrow(Function)} with a {@code StandTestAssertionError}, so that JUnit reports the
     * timeout as a failed assertion rather than an infrastructure error.
     *
     * @return the satisfying value
     */
    public T orElseThrow() {
        return orElseThrow(diagnostics -> new StandTestException(diagnostics.summary()));
    }
}
