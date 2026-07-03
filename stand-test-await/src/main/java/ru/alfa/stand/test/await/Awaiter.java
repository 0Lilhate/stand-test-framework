package ru.alfa.stand.test.await;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The single await mechanism of the SDK: polls a caller-supplied probe until a predicate holds or a
 * timeout elapses.
 *
 * <p>The awaiter is transport-agnostic — it never performs IO of its own and knows nothing about REST,
 * Kafka, DB or gRPC. The probe runs on the calling thread, so any thread-confined resource it touches
 * (a JDBC connection, a Kafka consumer) stays confined. Implementations must never use a fixed
 * {@code Thread.sleep} as the wait strategy (plan §2.5); they poll on the configured interval and
 * return an {@link AwaitResult} the caller inspects (they do not throw on timeout).
 *
 * <p><strong>The timeout bounds the polling loop, not an individual probe.</strong> A probe that
 * blocks indefinitely blocks the await with it — the probe must be non-blocking or carry its own
 * bounded timeout (a bounded Kafka poll, a JDBC query with a statement timeout), so the policy's
 * timeout stays the effective upper bound of the whole wait.
 */
public interface Awaiter {

    /**
     * Polls {@code probe} on the policy's interval until {@code condition} accepts an observed value or
     * the policy's timeout elapses. The probe is attempted at least once.
     *
     * @param <T> the awaited value type
     * @param policy the timing and behaviour of the await
     * @param probe supplies the value to test on each poll (runs on the calling thread)
     * @param condition accepts the value that satisfies the await
     * @return the satisfied result, or a timed-out result carrying {@link TimeoutDiagnostics}
     */
    <T> AwaitResult<T> await(AwaitPolicy policy, Supplier<T> probe, Predicate<? super T> condition);

    /**
     * Waits until the given boolean condition becomes {@code true} or the policy's timeout elapses.
     *
     * @param policy the timing and behaviour of the await
     * @param condition the boolean condition to wait for (runs on the calling thread)
     * @return the await result; {@link AwaitResult#satisfied()} reflects whether the condition held
     */
    default AwaitResult<Boolean> awaitCondition(AwaitPolicy policy, BooleanSupplier condition) {
        Objects.requireNonNull(condition, "condition must not be null");
        return await(policy, condition::getAsBoolean, Boolean::booleanValue);
    }

    /**
     * Creates an awaiter backed by the {@link TimeSource#system() system time source}.
     *
     * @return a new awaiter
     */
    static Awaiter create() {
        return new DefaultAwaiter();
    }
}
