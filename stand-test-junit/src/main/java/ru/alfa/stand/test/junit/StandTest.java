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
 * <p>Place it on a test class (or method) to have the extension resolve a
 * {@link ru.alfa.stand.test.core.StandClient} and an {@link ru.alfa.stand.test.await.Awaiter} as test
 * parameters, without Spring. The optional {@link #env()} records the logical environment for the
 * test; the environment actually executed is the one carried by the {@code Scenario} model (full
 * environment-config wiring is plan §9, a later iteration).
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
