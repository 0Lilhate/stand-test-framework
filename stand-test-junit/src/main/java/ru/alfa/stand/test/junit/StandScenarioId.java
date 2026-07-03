package ru.alfa.stand.test.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the scenario id for a stand test.
 *
 * <p>It may annotate a test class, a test method, or a {@code String} test parameter. When a
 * {@code String} parameter carries {@code @StandScenarioId}, {@link StandTestExtension} injects the
 * effective id, chosen most-specific-first: the parameter's own non-blank value, then a method-level
 * {@code @StandScenarioId}, then a class-level {@code @StandScenarioId} (also on the enclosing class
 * of a {@code @Nested} test). If none is declared, resolution fails with a clear error. The injected
 * {@code String} feeds {@code Scenario.builder(id)}. Note there is no {@link StandTest#env()}-style
 * fallback for the id.
 *
 * <p>On a parameter it may annotate only a {@code String}, and must not be combined with
 * {@link StandEnv @StandEnv} on the same parameter; either misuse fails with a clear error.
 *
 * <p>The {@code Stand} prefix matches {@link StandTest @StandTest}/{@link StandEnv @StandEnv} and
 * keeps the simple name distinct from the core
 * {@link ru.alfa.stand.test.core.identifier.ScenarioId} value type, so a consumer can use both
 * without an import clash. The annotation injects a plain {@code String}, not the core value type.
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface StandScenarioId {

    /**
     * The scenario id value; empty when used only as an injection marker that inherits the value from
     * the method/class declaration.
     *
     * @return the scenario id value, or empty to inherit
     */
    String value() default "";
}
