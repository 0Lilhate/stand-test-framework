package ru.alfa.stand.test.rest;

import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link BaseUrlResolver}: treats {@code baseUrlRef} as a reference (the name of an
 * environment variable holding the base URL) and resolves it indirectly; the
 * {@code ${NAME}}/{@code ${NAME:default}} placeholder spellings are supported via
 * {@link SecretReferences#resolve}.
 *
 * <p>This keeps stand URLs out of source (plan §9 / {@code ServiceEndpointDefinition}: the registry
 * stores a reference, never a hardcoded URL). Tests that need a literal URL inject their own
 * {@link BaseUrlResolver} via {@link RestStepExecutor}'s constructor rather than embedding URLs in the
 * registry. Literal-wrapped values produced by a trusted mapper (the Spring starter's endpoint value
 * fields) resolve verbatim without an environment lookup — see {@link SecretReferences}.
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
        String resolved = SecretReferences.resolve(baseUrlRef, this.lookup);
        if (resolved == null || resolved.isBlank()) {
            if (SecretReferences.isLiteral(baseUrlRef)) {
                throw new StandTestException("Base URL is configured as a literal value but it is empty — an unset environment variable behind a ${VAR:} placeholder resolves to the empty default");
            }
            throw new StandTestException("Base URL reference '" + baseUrlRef + "' did not resolve (environment variable not set)");
        }
        return resolved;
    }
}
