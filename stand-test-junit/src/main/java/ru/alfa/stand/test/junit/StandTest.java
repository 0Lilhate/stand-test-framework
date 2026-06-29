package ru.alfa.stand.test.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a JUnit 5 test as a stand test and wires the {@link StandTestExtension}.
 *
 * <p>Place it on a test class (or method) to have {@link StandTestExtension} resolve, as test
 * parameters (without Spring): a {@link ru.alfa.stand.test.core.StandClient}, an
 * {@link ru.alfa.stand.test.await.Awaiter}, and {@code String}s annotated with
 * {@link ScenarioId @ScenarioId} / {@link StandEnv @StandEnv}. The optional {@link #env()} is the
 * lowest-precedence source for the environment — a parameter/method/class {@code @StandEnv} overrides
 * it; full environment-config wiring is plan §9, a later iteration.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@ExtendWith(StandTestExtension.class)
public @interface StandTest {

    /**
     * The logical environment name for the test (for example {@code "ift"}).
     *
     * @return the environment name, empty if unspecified
     */
    String env() default "";
}
