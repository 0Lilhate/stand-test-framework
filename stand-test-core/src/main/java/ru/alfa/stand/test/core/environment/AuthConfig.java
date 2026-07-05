package ru.alfa.stand.test.core.environment;

import java.util.Objects;

/**
 * Service-level authentication configuration carried by a {@link ServiceEndpointDefinition}.
 *
 * <p>Every field is a <em>secret reference</em> (for example an environment-variable name) that the
 * executing adapter resolves at run time — never a credential value. This is the sanctioned way to
 * authenticate REST calls: scenarios themselves must not carry {@code Authorization} headers (the
 * validator rejects them as {@code SECRET_IN_SOURCE}); the adapter injects the header from this
 * config instead. Reference-shape enforcement (name, not value) is the configuration front-ends'
 * job, matching the other {@code *Ref} fields of the environment model.
 *
 * @param scheme the authentication scheme (never null)
 * @param usernameRef reference resolving to the username (required for {@link AuthScheme#BASIC}, absent otherwise)
 * @param passwordRef reference resolving to the password (required for {@link AuthScheme#BASIC}, absent otherwise)
 * @param tokenRef reference resolving to the bearer token (required for {@link AuthScheme#BEARER}, absent otherwise)
 */
public record AuthConfig(AuthScheme scheme, String usernameRef, String passwordRef, String tokenRef) {

    public AuthConfig {
        Objects.requireNonNull(scheme, "scheme must not be null");
        if (scheme == AuthScheme.BASIC) {
            requirePresent(usernameRef, "basic auth requires a usernameRef");
            requirePresent(passwordRef, "basic auth requires a passwordRef");
            requireAbsent(tokenRef, "basic auth must not carry a tokenRef");
        }
        if (scheme == AuthScheme.BEARER) {
            requirePresent(tokenRef, "bearer auth requires a tokenRef");
            requireAbsent(usernameRef, "bearer auth must not carry a usernameRef");
            requireAbsent(passwordRef, "bearer auth must not carry a passwordRef");
        }
    }

    /**
     * Creates a basic-auth config from username and password references.
     *
     * @param usernameRef reference resolving to the username (never blank)
     * @param passwordRef reference resolving to the password (never blank)
     * @return the basic-auth config
     */
    public static AuthConfig basic(String usernameRef, String passwordRef) {
        return new AuthConfig(AuthScheme.BASIC, usernameRef, passwordRef, null);
    }

    /**
     * Creates a bearer-token config from a token reference.
     *
     * @param tokenRef reference resolving to the bearer token (never blank)
     * @return the bearer-auth config
     */
    public static AuthConfig bearer(String tokenRef) {
        return new AuthConfig(AuthScheme.BEARER, null, null, tokenRef);
    }

    private static void requirePresent(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireAbsent(String value, String message) {
        if (value != null && !value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
