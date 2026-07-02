package ru.alfa.stand.test.grpc;

/**
 * Resolves a {@link ru.alfa.stand.test.core.environment.GrpcTargetDefinition} reference (for example an
 * environment-variable name) to its value.
 *
 * <p>The core environment model deliberately stores references, never target addresses (plan §9). This
 * seam turns a {@code targetRef} into a usable {@code host:port} at run time, and lets tests substitute a
 * resolver that points at a local (in-process) server, mirroring the Kafka adapter's
 * {@code ReferenceResolver} and the REST adapter's {@code BaseUrlResolver}.
 */
@FunctionalInterface
public interface ReferenceResolver {

    /**
     * Resolves the given reference to its value.
     *
     * @param reference the reference taken from the target definition (never blank)
     * @return the resolved value
     */
    String resolve(String reference);
}
