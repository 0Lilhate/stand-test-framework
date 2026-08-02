package ru.alfa.stand.test.core.environment;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Sign-in configuration of a {@link UiApplicationDefinition}.
 *
 * <p>Naming follows the service-level {@link AuthConfig} deliberately: the scheme is spelled
 * {@code scheme} (never {@code type}), and every credential is a <em>reference</em> — the name of an
 * environment variable or secret entry — never a credential value. A test therefore names a role, and
 * the SDK resolves an account for it; no login or password ever enters the registry, the scenario or
 * the repository.
 *
 * <p><strong>Scope.</strong> This record is the configuration vocabulary only. Which fields a given
 * scheme <em>requires</em> in order to actually sign in, and the sign-in step itself, belong to the UI
 * adapter and are settled together with it; what is enforced here are the invariants that hold
 * regardless of how sign-in is later performed.
 *
 * @param scheme how the test signs in (never null)
 * @param credentialsPoolRef reference resolving to the pool of test accounts (may be null)
 * @param roles the roles a scenario may request from that pool (never null, possibly empty)
 * @param discoveryAccountRef reference resolving to the read-only account used for exploring the UI,
 *     kept apart from the pool so exploration cannot take an account able to perform irreversible actions
 *     (may be null)
 */
public record UiAuthConfig(UiAuthScheme scheme, String credentialsPoolRef, List<String> roles, String discoveryAccountRef) {

    public UiAuthConfig {
        Objects.requireNonNull(scheme, "scheme must not be null");
        roles = (roles == null) ? List.of() : List.copyOf(roles);
        requireReferenceOrAbsent(credentialsPoolRef, "credentialsPoolRef must not be blank when declared");
        requireReferenceOrAbsent(discoveryAccountRef, "discoveryAccountRef must not be blank when declared");
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("auth roles must not be blank");
            }
        }
        if (roles.size() != Set.copyOf(roles).size()) {
            throw new IllegalArgumentException("auth roles must not contain duplicates, but were " + roles);
        }
        if (scheme == UiAuthScheme.NONE && (credentialsPoolRef != null || !roles.isEmpty() || discoveryAccountRef != null)) {
            throw new IllegalArgumentException("auth scheme NONE must not carry credentials — remove credentialsPoolRef/roles/discoveryAccountRef or declare a scheme that signs in");
        }
        if (!roles.isEmpty() && credentialsPoolRef == null) {
            throw new IllegalArgumentException("auth roles are requested from a credentials pool, so declaring roles requires a credentialsPoolRef");
        }
    }

    /**
     * Creates a config for an application that needs no sign-in.
     *
     * @return the no-authentication config
     */
    public static UiAuthConfig none() {
        return new UiAuthConfig(UiAuthScheme.NONE, null, List.of(), null);
    }

    private static void requireReferenceOrAbsent(String value, String message) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
