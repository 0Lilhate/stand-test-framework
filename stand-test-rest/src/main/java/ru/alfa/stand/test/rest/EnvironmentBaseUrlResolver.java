package ru.alfa.stand.test.rest;

import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link BaseUrlResolver}: treats {@code baseUrlRef} strictly as a reference (the name of an
 * environment variable holding the base URL) and resolves it indirectly.
 *
 * <p>This keeps stand URLs out of source (plan §9 / {@code ServiceEndpointDefinition}: the registry
 * stores a reference, never a hardcoded URL). Tests that need a literal URL inject their own
 * {@link BaseUrlResolver} via {@link RestStepExecutor}'s constructor rather than embedding URLs in the
 * registry.
 */
public final class EnvironmentBaseUrlResolver implements BaseUrlResolver {

    private final UnaryOperator<String> lookup;

    /**
     * Creates a resolver backed by the process environment ({@link System#getenv(String)}).
     */
    public EnvironmentBaseUrlResolver() {
        this(System::getenv);
    }

    /**
     * Creates a resolver backed by the given reference lookup (for tests).
     *
     * @param lookup resolves a reference name to its value (or null if unset)
     */
    EnvironmentBaseUrlResolver(UnaryOperator<String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
    }

    @Override
    public String resolve(String baseUrlRef) {
        if (baseUrlRef == null || baseUrlRef.isBlank()) {
            throw new StandTestException("baseUrlRef must not be blank");
        }
        String resolved = this.lookup.apply(baseUrlRef);
        if (resolved == null || resolved.isBlank()) {
            throw new StandTestException("Base URL reference '" + baseUrlRef + "' did not resolve (environment variable not set)");
        }
        return resolved;
    }
}
