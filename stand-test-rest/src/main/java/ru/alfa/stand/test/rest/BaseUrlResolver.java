package ru.alfa.stand.test.rest;

/**
 * Resolves a {@code ServiceEndpointDefinition.baseUrlRef()} reference to an absolute base URL.
 *
 * <p>The core environment model deliberately stores a reference (for example an environment-variable
 * name), never a hardcoded URL or a secret value (plan §9). This seam turns that reference into a
 * usable URL at run time, and lets tests substitute a resolver that points at a local server.
 */
@FunctionalInterface
public interface BaseUrlResolver {

    /**
     * Resolves the given reference to an absolute base URL.
     *
     * @param baseUrlRef the reference taken from the environment registry
     * @return the absolute base URL
     */
    String resolve(String baseUrlRef);
}
