package ru.alfa.stand.test.db;

import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link ReferenceResolver}: treats a reference as the name of an environment variable and
 * resolves it indirectly; the {@code ${NAME}}/{@code ${NAME:default}} placeholder spellings are
 * supported via {@link SecretReferences#resolve}.
 *
 * <p>This keeps JDBC URLs and credentials out of source (plan §9 / {@code DatasourceDefinition}: the
 * registry stores references, never values). An <em>unset</em> reference (the lookup returns null) is a
 * configuration error; a reference that resolves to an <em>empty</em> value is allowed, because some
 * stands legitimately use an empty password. Tests that need literal values inject their own
 * {@link ReferenceResolver} via {@link DbStepExecutor}'s constructor rather than embedding values in the
 * registry.
 */
public final class EnvironmentReferenceResolver implements ReferenceResolver {

    private final UnaryOperator<String> lookup;

    /**
     * Creates a resolver backed by the process environment ({@link System#getenv(String)}).
     */
    public EnvironmentReferenceResolver() {
        this(System::getenv);
    }

    /**
     * Creates a resolver backed by the given reference lookup (for tests).
     *
     * @param lookup resolves a reference name to its value (or null if unset)
     */
    EnvironmentReferenceResolver(UnaryOperator<String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
    }

    @Override
    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new StandTestException("Datasource reference must not be blank");
        }
        String resolved = SecretReferences.resolve(reference, this.lookup);
        if (resolved == null) {
            throw new StandTestException("Datasource reference '" + reference + "' did not resolve (environment variable not set)");
        }
        return resolved;
    }
}
