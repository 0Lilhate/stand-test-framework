package ru.alfa.stand.test.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Marks a stand test as safe to run concurrently — a thin, self-documenting facade over JUnit's
 * {@link Execution @Execution(CONCURRENT)}.
 *
 * <p>Under the recommended stand-test parallel configuration ({@code parallel.enabled=true},
 * {@code mode.classes.default=concurrent}, {@code mode.default=same_thread}) test <em>classes</em> already
 * run concurrently while the <em>methods</em> inside one class stay serial. Use this annotation to make the
 * intent explicit at the class level, or on a single method to opt that method into concurrent execution
 * with its siblings when the class otherwise runs its methods serially.
 *
 * <p>A stand scenario is parallel-safe by construction when it relies on the SDK's per-run isolation: a
 * unique {@code testRunId}/{@code correlationId} per run, a per-run {@code VariableStore}, a unique Kafka
 * consumer group, and {@code testRunId}-scoped test data (plan §15). Do <strong>not</strong> place it on a
 * class that binds a fixed port, mutates shared global state or shares a fixture across methods that a
 * concurrent sibling could observe — use {@link StandSerial}, {@link StandIsolated} or JUnit's
 * {@link org.junit.jupiter.api.parallel.ResourceLock @ResourceLock} instead.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Execution(ExecutionMode.CONCURRENT)
public @interface StandParallelSafe {
}
