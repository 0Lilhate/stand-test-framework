package ru.alfa.stand.test.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the logical environment alias (the §9 whitelist key) for a stand test.
 *
 * <p>It may annotate a test class, a test method, or a {@code String} test parameter. When a
 * {@code String} parameter carries {@code @StandEnv}, {@link StandTestExtension} injects the effective
 * environment, chosen most-specific-first: the parameter's own non-blank value, then a method-level
 * {@code @StandEnv}, then a class-level {@code @StandEnv} (also on the enclosing class of a
 * {@code @Nested} test), then {@link StandTest#env()}. If none is declared, resolution fails with a
 * clear error. The injected {@code String} feeds {@code Scenario.builder(...).environment(env)}.
 *
 * <p>On a parameter it may annotate only a {@code String}, and must not be combined with
 * {@link StandScenarioId @StandScenarioId} on the same parameter; either misuse fails with a clear error.
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface StandEnv {

    /**
     * The logical environment name (for example {@code "ift"}); empty when used only as an injection
     * marker that inherits the value from the method/class declaration.
     *
     * @return the environment name, or empty to inherit
     */
    String value() default "";
}
