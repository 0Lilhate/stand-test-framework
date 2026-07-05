package ru.alfa.stand.test.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the classpath-safety structure of {@link StandTestAutoConfiguration}: a consumer that has only
 * SOME adapters on its classpath must still be able to start a context. Spring's condition evaluation
 * reflects over the declared methods of every configuration class it processes; resolving a method
 * signature whose return/parameter type is missing throws {@code NoClassDefFoundError} <em>before</em>
 * any method-level {@code @ConditionalOnClass} is consulted. The {@code ApplicationContextRunner} +
 * {@code FilteredClassLoader} tests cannot catch a violation (the filtered class is still resolvable
 * by the parent loader when signatures are reflected), so this structural test is the regression net:
 * optional-module types may appear only in nested {@code @Configuration} classes gated by a class-level
 * {@code @ConditionalOnClass}.
 */
class StandTestAutoConfigurationStructureTest {

    private static final List<String> OPTIONAL_MODULE_PACKAGES = List.of(
            "ru.alfa.stand.test.rest",
            "ru.alfa.stand.test.kafka",
            "ru.alfa.stand.test.db",
            "ru.alfa.stand.test.grpc",
            "ru.alfa.stand.test.allure",
            "ru.alfa.stand.test.await");

    @Test
    @DisplayName("outer auto-configuration methods reference no optional-module types in their signatures")
    void outerClassSignatures_referenceOnlyAlwaysPresentTypes() {
        List<String> violations = new ArrayList<>();
        for (Method method : StandTestAutoConfiguration.class.getDeclaredMethods()) {
            if (isOptionalModuleType(method.getReturnType())) {
                violations.add(method.getName() + " returns " + method.getReturnType().getName());
            }
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (isOptionalModuleType(parameterType)) {
                    violations.add(method.getName() + " takes " + parameterType.getName());
                }
            }
        }

        assertThat(violations)
                .withFailMessage("optional-module types leaked into outer auto-configuration signatures "
                        + "(move the bean into a nested @ConditionalOnClass configuration): %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("every nested configuration is class-level @ConditionalOnClass-gated")
    void nestedConfigurations_areClassConditionGated() {
        Class<?>[] nested = StandTestAutoConfiguration.class.getDeclaredClasses();

        assertThat(nested).isNotEmpty();
        for (Class<?> configuration : nested) {
            assertThat(configuration.isAnnotationPresent(Configuration.class))
                    .withFailMessage("%s must be a @Configuration", configuration.getSimpleName())
                    .isTrue();
            assertThat(configuration.isAnnotationPresent(ConditionalOnClass.class))
                    .withFailMessage("%s must be gated by a class-level @ConditionalOnClass", configuration.getSimpleName())
                    .isTrue();
        }
    }

    private static boolean isOptionalModuleType(Class<?> type) {
        Package typePackage = type.getPackage();
        if (typePackage == null) {
            return false;
        }
        String name = typePackage.getName();
        for (String optional : OPTIONAL_MODULE_PACKAGES) {
            if (name.equals(optional) || name.startsWith(optional + ".")) {
                return true;
            }
        }
        return false;
    }
}
