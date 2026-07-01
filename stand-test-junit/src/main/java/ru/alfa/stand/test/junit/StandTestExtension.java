package ru.alfa.stand.test.junit;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.SearchOption;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.DefaultStandClient;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.execution.ScenarioRunner;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

/**
 * JUnit 5 extension that bridges the test lifecycle to the stand-test SDK (wired by {@link StandTest}).
 *
 * <p>It resolves these parameter types, without Spring:
 * <ul>
 *   <li>{@link StandClient} — assembled from the {@link StepExecutor}s discovered on the test classpath
 *   via {@link ServiceLoader} (the SPI wiring point: each adapter registers its executor), plus a
 *   {@link ReportingEventPublisher} and an {@link EnvironmentRegistry} discovered the same way (first
 *   provider wins; a {@link NoOpReportingEventPublisher} and an empty {@link InMemoryEnvironmentRegistry}
 *   when none is present), behind a {@link DefaultScenarioRunner}. The client is built once and cached
 *   for the engine run.</li>
 *   <li>{@link Awaiter} — a fresh system-backed awaiter for ad-hoc waits in a test.</li>
 *   <li>{@code String} annotated with {@link ScenarioId @ScenarioId} — the declared scenario id.</li>
 *   <li>{@code String} annotated with {@link StandEnv @StandEnv} — the declared logical environment.</li>
 * </ul>
 *
 * <p>For the two declared values, resolution is most-specific-first: the parameter annotation's own
 * non-blank value, then a method-level declaration, then a class-level declaration — also found on the
 * enclosing class of a {@code @Nested} test — and, for the environment only, {@link StandTest#env()}
 * as a final fallback. A missing declaration, a non-{@code String} annotated parameter, or a parameter
 * carrying both {@code @ScenarioId} and {@code @StandEnv} fails with a
 * {@link ParameterResolutionException}.
 *
 * <p>SDK failures need no translation here: {@code StandTestAssertionError} extends
 * {@link AssertionError} and {@code StandTestException} extends {@link RuntimeException}, so a failure
 * thrown by the runner inside {@code stand.run(...)} surfaces as a native JUnit test failure/error.
 */
public final class StandTestExtension implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(StandTestExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        if (type == StandClient.class || type == Awaiter.class) {
            return true;
        }
        // A @ScenarioId/@StandEnv parameter is claimed regardless of its type, so that a misuse (a
        // non-String parameter, or both annotations at once) fails with a clear message from
        // resolveParameter rather than JUnit's generic "no resolver registered" error.
        return parameterContext.isAnnotated(ScenarioId.class) || parameterContext.isAnnotated(StandEnv.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        if (type == Awaiter.class) {
            return Awaiter.create();
        }
        if (type == StandClient.class) {
            return standClient(extensionContext);
        }
        boolean asScenarioId = parameterContext.isAnnotated(ScenarioId.class);
        boolean asEnvironment = parameterContext.isAnnotated(StandEnv.class);
        if (asScenarioId && asEnvironment) {
            throw new ParameterResolutionException("A parameter must not carry both @ScenarioId and @StandEnv");
        }
        if (type != String.class) {
            throw new ParameterResolutionException(
                    "@ScenarioId and @StandEnv may only annotate a String parameter, but found " + type.getTypeName());
        }
        return asScenarioId
                ? resolveScenarioId(parameterContext, extensionContext)
                : resolveEnvironment(parameterContext, extensionContext);
    }

    private static String resolveScenarioId(ParameterContext parameterContext, ExtensionContext extensionContext) {
        String value = parameterValue(parameterContext, ScenarioId.class, ScenarioId::value);
        if (value == null) {
            value = declaredValue(extensionContext, ScenarioId.class, ScenarioId::value);
        }
        if (value == null) {
            throw new ParameterResolutionException(
                    "No @ScenarioId declared on the parameter, test method or test class");
        }
        return value;
    }

    private static String resolveEnvironment(ParameterContext parameterContext, ExtensionContext extensionContext) {
        String value = parameterValue(parameterContext, StandEnv.class, StandEnv::value);
        if (value == null) {
            value = declaredValue(extensionContext, StandEnv.class, StandEnv::value);
        }
        if (value == null) {
            value = declaredValue(extensionContext, StandTest.class, StandTest::env);
        }
        if (value == null) {
            throw new ParameterResolutionException(
                    "No environment declared: annotate the test or parameter with @StandEnv, or set @StandTest(env=...)");
        }
        return value;
    }

    private static <A extends Annotation> String parameterValue(
            ParameterContext parameterContext, Class<A> annotationType, Function<A, String> valueAccessor) {
        return parameterContext.findAnnotation(annotationType)
                .map(valueAccessor)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    private static <A extends Annotation> String declaredValue(
            ExtensionContext extensionContext, Class<A> annotationType, Function<A, String> valueAccessor) {
        String fromMethod = extensionContext.getTestMethod()
                .flatMap(method -> AnnotationSupport.findAnnotation(method, annotationType))
                .map(valueAccessor)
                .filter(value -> !value.isBlank())
                .orElse(null);
        if (fromMethod != null) {
            return fromMethod;
        }
        return extensionContext.getTestClass()
                .flatMap(testClass -> AnnotationSupport.findAnnotation(testClass, annotationType, SearchOption.INCLUDE_ENCLOSING_CLASSES))
                .map(valueAccessor)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    private static StandClient standClient(ExtensionContext extensionContext) {
        return extensionContext.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(StandClient.class, key -> buildStandClient(), StandClient.class);
    }

    private static StandClient buildStandClient() {
        List<StepExecutor> executors = new ArrayList<>();
        ServiceLoader.load(StepExecutor.class).forEach(executors::add);
        // Reporting and environment wiring are discovered through the same SPI as the executors, so junit
        // gains no compile-time edge to any adapter (plan §8.5/§17). First provider wins (single-provider
        // assumption — the iteration order is classpath-dependent, not prioritised; a composite/priority
        // policy is a later concern), and the defaults (NoOp publisher, empty registry) keep behaviour
        // unchanged when no provider is on the classpath.
        ReportingEventPublisher publisher = ServiceLoader.load(ReportingEventPublisher.class).findFirst().orElse(NoOpReportingEventPublisher.INSTANCE);
        EnvironmentRegistry registry = ServiceLoader.load(EnvironmentRegistry.class).findFirst().orElseGet(() -> new InMemoryEnvironmentRegistry(Map.of()));
        ScenarioRunner runner = new DefaultScenarioRunner(executors, new DefaultScenarioValidator(), registry, publisher);
        return new DefaultStandClient(runner);
    }
}
