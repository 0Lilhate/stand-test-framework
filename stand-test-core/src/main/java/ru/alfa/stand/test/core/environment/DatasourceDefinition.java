package ru.alfa.stand.test.core.environment;

import java.util.Set;

/**
 * Definition of a logical datasource alias.
 *
 * <p>{@code urlRef}/{@code userRef}/{@code passwordRef} are secret <em>references</em> (for example
 * environment-variable names), never secret values. {@code allowedSchemas} whitelists where seed and
 * cleanup may write; {@code writeAllowed} is the explicit opt-in required for any write — readonly is
 * the default. The schema set is defensively copied and exposed as immutable.
 *
 * @param alias the logical datasource alias (never blank)
 * @param urlRef a reference resolving to the JDBC URL (never blank)
 * @param userRef a reference resolving to the username (never blank)
 * @param passwordRef a reference resolving to the password (never blank)
 * @param allowedSchemas the immutable set of schemas writes are confined to
 * @param writeAllowed whether writes are explicitly allowed (default should be false)
 */
public record DatasourceDefinition(
        String alias,
        String urlRef,
        String userRef,
        String passwordRef,
        Set<String> allowedSchemas,
        boolean writeAllowed) {

    public DatasourceDefinition {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("datasource alias must not be blank");
        }
        if (urlRef == null || urlRef.isBlank()) {
            throw new IllegalArgumentException("urlRef must not be blank");
        }
        if (userRef == null || userRef.isBlank()) {
            throw new IllegalArgumentException("userRef must not be blank");
        }
        if (passwordRef == null || passwordRef.isBlank()) {
            throw new IllegalArgumentException("passwordRef must not be blank");
        }
        allowedSchemas = (allowedSchemas == null) ? Set.of() : Set.copyOf(allowedSchemas);
    }

    /**
     * Returns whether the given schema is within the write whitelist.
     *
     * @param schema the schema name
     * @return true if the schema is whitelisted
     */
    public boolean isSchemaAllowed(String schema) {
        return allowedSchemas.contains(schema);
    }
}
