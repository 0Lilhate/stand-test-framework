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
 * Forces a stand test to run on the parent's thread — a thin facade over JUnit's
 * {@link Execution @Execution(SAME_THREAD)}.
 *
 * <p>Placed on a class it keeps that class's methods serial (the default under the recommended
 * configuration); placed on a method it opts that method out of a class that otherwise runs its methods
 * concurrently. Unlike {@link StandIsolated} it does <strong>not</strong> stop <em>other</em> classes from
 * running concurrently — it only serialises the annotated scope. Reach for it when the methods of one class
 * share a mutable per-class fixture (an in-JVM double, a captured buffer) that concurrent methods would race,
 * yet the class is still safe to run alongside unrelated classes.
 *
 * <p>To serialise a test against everything else (a fixed port, a process-wide resource) use
 * {@link StandIsolated}; to serialise only against other tests that touch the <em>same named</em> resource use
 * JUnit's {@link org.junit.jupiter.api.parallel.ResourceLock @ResourceLock}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Execution(ExecutionMode.SAME_THREAD)
public @interface StandSerial {
}
