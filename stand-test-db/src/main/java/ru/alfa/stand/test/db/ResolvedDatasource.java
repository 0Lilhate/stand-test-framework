package ru.alfa.stand.test.db;

import java.util.Objects;

/**
 * A datasource whose {@code urlRef}/{@code userRef}/{@code passwordRef} references have been resolved to
 * concrete connection values by a {@link ReferenceResolver} (plan §9 — references resolve to values only
 * at run time, in the adapter, never hardcoded). The password may be empty (some stands use an empty
 * password); url and user must be present.
 *
 * @param url the resolved JDBC URL (never blank)
 * @param user the resolved username (never blank)
 * @param password the resolved password (never null; may be empty)
 */
public record ResolvedDatasource(String url, String user, String password) {

    public ResolvedDatasource {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("resolved JDBC url must not be blank");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("resolved JDBC user must not be blank");
        }
        Objects.requireNonNull(password, "resolved JDBC password must not be null");
    }
}
