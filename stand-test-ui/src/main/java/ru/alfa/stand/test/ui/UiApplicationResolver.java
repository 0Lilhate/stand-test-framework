package ru.alfa.stand.test.ui;

import ru.alfa.stand.test.core.execution.StepExecutionContext;

/**
 * Turns an application alias into a {@link ResolvedUiApplication}.
 *
 * <p>The seam to the environment registry, mirroring the REST adapter's {@code BaseUrlResolver}: in a
 * unit test it is a lambda returning a loopback address, in production it is
 * {@link EnvironmentUiApplicationResolver}, which is also the point at which a non-whitelisted alias is
 * refused.
 */
@FunctionalInterface
public interface UiApplicationResolver {

    /**
     * Resolves an alias against the run's environment.
     *
     * @param applicationAlias the alias named by the step
     * @param context the step execution context (environment, registry)
     * @return the resolved application
     */
    ResolvedUiApplication resolve(String applicationAlias, StepExecutionContext context);
}
