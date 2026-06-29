package ru.alfa.stand.test.kafka;

/**
 * Resolves a {@link ru.alfa.stand.test.core.environment.KafkaClusterDefinition} reference (for example
 * an environment-variable name) to its value.
 *
 * <p>The core environment model deliberately stores references, never broker addresses or secret values
 * (plan §9). This seam turns a reference into a usable value at run time, and lets tests substitute a
 * resolver that points at a local broker, mirroring the REST adapter's {@code BaseUrlResolver}.
 */
@FunctionalInterface
public interface ReferenceResolver {

    /**
     * Resolves the given reference to its value.
     *
     * @param reference the reference taken from the cluster definition (never blank)
     * @return the resolved value
     */
    String resolve(String reference);
}
