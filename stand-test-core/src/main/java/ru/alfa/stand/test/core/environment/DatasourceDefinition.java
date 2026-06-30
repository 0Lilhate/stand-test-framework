package ru.alfa.stand.test.core.environment;

import java.util.Locale;
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
     * Returns whether the given target schema is within the write whitelist, after folding the target the way
     * PostgreSQL folds an unquoted identifier (to lower case).
     *
     * <p>The DB adapter only ever passes an unqualified, unquoted schema name extracted from the statement
     * (a quoted, case-distinct schema is blanked by the SQL classifier and never reaches here), and that
     * extractor yields ASCII identifiers, so {@link Locale#ROOT} lower-casing exactly mirrors how the
     * database resolves the target schema. Only the <em>target</em> is folded, never the whitelist: an entry
     * must be the physical (lower-case) schema name, and a non-lower-case entry stays inert (fail-closed,
     * plan §8.8) rather than leniently matching a different physical schema. Case-insensitivity is applied
     * here <strong>only</strong> to schema names — datasource/service/topic aliases stay exact-match.
     *
     * @param schema the target schema name (may be null)
     * @return true if the folded schema is whitelisted
     */
    public boolean isSchemaAllowed(String schema) {
        return schema != null && allowedSchemas.contains(schema.toLowerCase(Locale.ROOT));
    }
}
