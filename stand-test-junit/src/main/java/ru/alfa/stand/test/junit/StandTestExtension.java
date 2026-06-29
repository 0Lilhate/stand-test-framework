package ru.alfa.stand.test.junit;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.DefaultStandClient;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.execution.ScenarioRunner;
import ru.alfa.stand.test.core.execution.StepExecutor;

/**
 * JUnit 5 extension that bridges the test lifecycle to the stand-test SDK (wired by {@link StandTest}).
 *
 * <p>It resolves two parameter types, without Spring:
 * <ul>
 *   <li>{@link StandClient} — assembled from the {@link StepExecutor}s discovered on the test classpath
 *   via {@link ServiceLoader} (the SPI wiring point: each adapter registers its executor), behind a
 *   {@link DefaultScenarioRunner}. The client is built once and cached for the engine run.</li>
 *   <li>{@link Awaiter} — a fresh system-backed awaiter for ad-hoc waits in a test.</li>
 * </ul>
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
        return type == StandClient.class || type == Awaiter.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        if (type == Awaiter.class) {
            return Awaiter.create();
        }
        return standClient(extensionContext);
    }

    private static StandClient standClient(ExtensionContext extensionContext) {
        return extensionContext.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(StandClient.class, key -> buildStandClient(), StandClient.class);
    }

    private static StandClient buildStandClient() {
        List<StepExecutor> executors = new ArrayList<>();
        ServiceLoader.load(StepExecutor.class).forEach(executors::add);
        ScenarioRunner runner = new DefaultScenarioRunner(executors);
        return new DefaultStandClient(runner);
    }
}
