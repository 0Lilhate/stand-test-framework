package ru.alfa.stand.test.junit;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;
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
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.execution.ScenarioRunner;
import ru.alfa.stand.test.core.exception.StandTestException;
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
 *   provider wins; with no provider, reporting falls back to the {@link NoOpReportingEventPublisher} and
 *   environment lookups fail with a distinct "no EnvironmentRegistry provider on the test classpath"
 *   diagnostic), behind a {@link DefaultScenarioRunner}. The client is built once and cached
 *   for the engine run.</li>
 *   <li>{@link Awaiter} — a fresh system-backed awaiter for ad-hoc waits in a test.</li>
 *   <li>{@code String} annotated with {@link StandScenarioId @StandScenarioId} — the declared scenario id.</li>
 *   <li>{@code String} annotated with {@link StandEnv @StandEnv} — the declared logical environment.</li>
 * </ul>
 *
 * <p>For the two declared values, resolution is most-specific-first: the parameter annotation's own
 * non-blank value, then a method-level declaration, then a class-level declaration — also found on the
 * enclosing class of a {@code @Nested} test — and, for the environment only, {@link StandTest#env()}
 * as a final fallback. A missing declaration, a non-{@code String} annotated parameter, or a parameter
 * carrying both {@code @StandScenarioId} and {@code @StandEnv} fails with a
 * {@link ParameterResolutionException}.
 *
 * <p>SDK failures need no translation here: {@code StandTestAssertionError} extends
 * {@link AssertionError} and {@code StandTestException} extends {@link RuntimeException}, so a failure
 * thrown by the runner inside {@code stand.run(...)} surfaces as a native JUnit test failure/error.
 *
 * <p><strong>Parallel execution (plan §15).</strong> The single {@link StandClient} is cached at the
 * <em>engine-root</em> store, so under JUnit parallel execution ({@code junit.jupiter.execution.parallel.enabled=true})
 * <em>the same</em> client instance — and therefore the one {@link DefaultScenarioRunner} and the one instance
 * of each SPI-discovered {@link StepExecutor}/{@link ReportingEventPublisher} it wraps — is invoked
 * concurrently by every test thread. This is safe because the runner keeps all per-run state
 * ({@code ScenarioContext}, {@code VariableStore}, {@code ResourceScope}) thread-confined to a single
 * {@code run(...)} call; it is <strong>conditional</strong> on every registered {@link StepExecutor} and the
 * {@link ReportingEventPublisher} being safe for concurrent {@code execute(...)}/{@code prepare(...)}/{@code publish(...)}
 * — the SPIs mandate exactly that (an executor must hold no run-scoped mutable state in its fields; run-scoped
 * resources belong in the per-run {@code ResourceScope}). A scenario run is driven on one thread, so parallelise
 * at the scenario/class level (concurrent classes, serial methods), never the steps of one scenario. Mark a
 * class that cannot be {@code testRunId}-isolated with {@link StandIsolated}/{@link StandSerial} or
 * {@link org.junit.jupiter.api.parallel.ResourceLock @ResourceLock}.
 */
public final class StandTestExtension implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(StandTestExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        if (type == StandClient.class || type == Awaiter.class) {
            return true;
        }
        // A @StandScenarioId/@StandEnv parameter is claimed regardless of its type, so that a misuse (a
        // non-String parameter, or both annotations at once) fails with a clear message from
        // resolveParameter rather than JUnit's generic "no resolver registered" error.
        return parameterContext.isAnnotated(StandScenarioId.class) || parameterContext.isAnnotated(StandEnv.class);
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
        boolean asStandScenarioId = parameterContext.isAnnotated(StandScenarioId.class);
        boolean asEnvironment = parameterContext.isAnnotated(StandEnv.class);
        if (asStandScenarioId && asEnvironment) {
            throw new ParameterResolutionException("A parameter must not carry both @StandScenarioId and @StandEnv");
        }
        if (type != String.class) {
            throw new ParameterResolutionException(
                    "@StandScenarioId and @StandEnv may only annotate a String parameter, but found " + type.getTypeName());
        }
        return asStandScenarioId
                ? resolveStandScenarioId(parameterContext, extensionContext)
                : resolveEnvironment(parameterContext, extensionContext);
    }

    private static String resolveStandScenarioId(ParameterContext parameterContext, ExtensionContext extensionContext) {
        String value = parameterValue(parameterContext, StandScenarioId.class, StandScenarioId::value);
        if (value == null) {
            value = declaredValue(extensionContext, StandScenarioId.class, StandScenarioId::value);
        }
        if (value == null) {
            throw new ParameterResolutionException(
                    "No @StandScenarioId declared on the parameter, test method or test class");
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
        // gains no compile-time edge to any adapter (plan §8.5/§17). Exactly ONE provider is allowed per
        // SPI: with more than one the pick would be silently classpath-order-dependent, so the build of
        // the client fails loudly instead. With no reporting provider the NoOp publisher keeps behaviour
        // unchanged; with no registry provider the fallback raises a distinct "no provider on the test
        // classpath" diagnostic at first lookup instead of a misleading "not whitelisted" failure.
        ReportingEventPublisher publisher = uniqueProvider(providers(ReportingEventPublisher.class), ReportingEventPublisher.class)
                .orElse(NoOpReportingEventPublisher.INSTANCE);
        EnvironmentRegistry registry = uniqueProvider(providers(EnvironmentRegistry.class), EnvironmentRegistry.class)
                .orElseGet(NoProviderEnvironmentRegistry::new);
        ScenarioRunner runner = new DefaultScenarioRunner(executors, new DefaultScenarioValidator(), registry, publisher);
        return new DefaultStandClient(runner);
    }

    private static <T> List<T> providers(Class<T> spi) {
        List<T> found = new ArrayList<>();
        ServiceLoader.load(spi).forEach(found::add);
        return found;
    }

    /**
     * Returns the single discovered provider, empty when none is present, and fails loudly when more
     * than one is on the classpath — a silent classpath-order-dependent pick would make runs
     * environment-dependent in a way that is invisible until it misbehaves.
     */
    static <T> Optional<T> uniqueProvider(List<T> providers, Class<T> spi) {
        if (providers.size() > 1) {
            String names = providers.stream().map(provider -> provider.getClass().getName()).collect(Collectors.joining(", "));
            throw new StandTestException("Multiple " + spi.getSimpleName() + " providers on the test classpath: [" + names
                    + "] — the selection would be classpath-order-dependent; keep exactly one provider");
        }
        return providers.isEmpty() ? Optional.empty() : Optional.of(providers.get(0));
    }
}
