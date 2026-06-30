package ru.alfa.stand.test.db;

/**
 * Resolves a datasource secret <em>reference</em> (for example an environment-variable name) to its
 * value.
 *
 * <p>The registry stores references, never values (plan §9 / {@code DatasourceDefinition}); this seam is
 * where the adapter turns {@code urlRef}/{@code userRef}/{@code passwordRef} into the concrete connection
 * values at run time, mirroring the REST {@code BaseUrlResolver} and the Kafka cluster reference
 * resolver. Tests inject a resolver that returns literal values rather than embedding values in the
 * registry.
 */
public interface ReferenceResolver {

    /**
     * Resolves a reference to its value.
     *
     * @param reference the reference (for example {@code MAIN_DB_URL})
     * @return the resolved value
     */
    String resolve(String reference);
}
