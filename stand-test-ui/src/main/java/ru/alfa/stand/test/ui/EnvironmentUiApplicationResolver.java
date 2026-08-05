package ru.alfa.stand.test.ui;

import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.ViewportProfile;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;

/**
 * The production {@link UiApplicationResolver}: reads the {@code ui-applications} section of the
 * environment registry and resolves the application's {@code base-url-ref} — the name of an environment
 * variable — into the address the browser opens.
 *
 * <p>The indirection is the point: stand addresses never live in test source, and the registry stores a
 * reference rather than a URL. An alias absent from the registry is refused here, in addition to being
 * refused by the pre-flight validator before the run starts.
 */
public final class EnvironmentUiApplicationResolver implements UiApplicationResolver {

    private final UnaryOperator<String> lookup;

    /**
     * Creates a resolver backed by the process environment.
     */
    public EnvironmentUiApplicationResolver() {
        this(System::getenv);
    }

    /**
     * Creates a resolver backed by the given reference lookup (for tests).
     *
     * @param lookup resolves a reference name to its value, or null when unset
     */
    public EnvironmentUiApplicationResolver(UnaryOperator<String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
    }

    @Override
    public ResolvedUiApplication resolve(String applicationAlias, StepExecutionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (applicationAlias == null || applicationAlias.isBlank()) {
            throw new StandTestException("UI application alias must not be blank");
        }
        String environment = context.scenarioContext().environment();
        EnvironmentDefinition definition = context.environmentRegistry()
                .environment(environment)
                .orElseThrow(() -> new StandTestException("Environment '" + environment + "' is not whitelisted"));
        UiApplicationDefinition application = definition.uiApplication(applicationAlias)
                .orElseThrow(() -> new StandTestException("UI application '" + applicationAlias + "' is not whitelisted in environment '" + environment + "'"));
        return new ResolvedUiApplication(applicationAlias, resolveBaseUrl(application), viewport(application), application.auth());
    }

    private String resolveBaseUrl(UiApplicationDefinition application) {
        String reference = application.baseUrlRef();
        String resolved = SecretReferences.resolve(reference, this.lookup);
        if (resolved == null || resolved.isBlank()) {
            if (SecretReferences.isLiteral(reference)) {
                throw new StandTestException("UI application '" + application.alias() + "' is configured with a literal base URL but it is empty");
            }
            throw new StandTestException("Base URL reference '" + reference + "' of UI application '" + application.alias()
                    + "' did not resolve (environment variable not set)");
        }
        return resolved;
    }

    private static ViewportProfile viewport(UiApplicationDefinition application) {
        return application.defaultViewportProfile().orElse(null);
    }
}
