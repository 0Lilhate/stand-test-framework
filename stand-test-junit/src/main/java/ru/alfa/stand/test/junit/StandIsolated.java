package ru.alfa.stand.test.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Runs a stand test class in isolation — a thin facade over JUnit's {@link Isolated @Isolated}: no other
 * test class executes concurrently while this one runs.
 *
 * <p>This is the primary opt-out under the default-on-for-the-safe-subset model: annotate the (rare) class
 * that cannot be isolated by {@code testRunId}/{@code correlationId} — one that binds a fixed local port, a
 * shared file, a shared embedded broker, or mutates a process-wide singleton — so the rest of the suite keeps
 * running concurrently while this class runs alone. It is the strongest guard; prefer the narrower
 * {@link org.junit.jupiter.api.parallel.ResourceLock @ResourceLock} when only tests that share the
 * <em>same named</em> resource must be mutually excluded (for example two classes binding the same port), and
 * {@link StandSerial} when only the methods of one class need to be serial.
 *
 * <p>Like the underlying {@link Isolated}, it applies at the class level only.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Isolated
public @interface StandIsolated {
}
