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
 * <p><strong>Scope.</strong> This record is the configuration vocabulary; performing a sign-in belongs
 * to the UI adapter. What is enforced here is only what can be decided from the configuration alone:
 * that a scheme which signs in through the browser has been given the elements it needs
 * ({@link UiLoginFormConfig}) and somewhere to draw accounts from, and that a scheme which does not sign
 * in carries no credentials at all. Enforcing this here rather than at run time is what turns a
 * half-written {@code auth} section into a message when the registry is read, instead of a browser that
 * starts and then fails on a missing locator.
 *
 * @param scheme how the test signs in (never null)
 * @param credentialsPoolRef reference resolving to the pool of test accounts — the roster naming account
 *     ids, roles and the references holding their credentials (may be null only for a scheme that does
 *     not sign in through the browser)
 * @param roles the roles a scenario may request from that pool (never null, possibly empty); when
 *     non-empty a sign-in step must name one of them, because "any account" is not expressible
 * @param discoveryAccountRef reference resolving to the read-only account used for exploring the UI,
 *     kept apart from the pool so exploration cannot take an account able to perform irreversible actions
 *     (may be null)
 * @param login where the sign-in form is and what it is made of (may be null only for a scheme that does
 *     not sign in through the browser)
 * @param challenge the interactive obstacle the sign-in puts after the form, declared so the SDK can
 *     refuse with a speaking message instead of hanging (never null; defaults to
 *     {@link UiLoginChallenge#NONE})
 */
public record UiAuthConfig(
        UiAuthScheme scheme,
        String credentialsPoolRef,
        List<String> roles,
        String discoveryAccountRef,
        UiLoginFormConfig login,
        UiLoginChallenge challenge) {

    public UiAuthConfig {
        Objects.requireNonNull(scheme, "scheme must not be null");
        roles = (roles == null) ? List.of() : List.copyOf(roles);
        challenge = (challenge == null) ? UiLoginChallenge.NONE : challenge;
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
        if (scheme == UiAuthScheme.NONE) {
            if (credentialsPoolRef != null || !roles.isEmpty() || discoveryAccountRef != null) {
                throw new IllegalArgumentException("auth scheme NONE must not carry credentials — remove credentialsPoolRef/roles/discoveryAccountRef or declare a scheme that signs in");
            }
            if (login != null) {
                throw new IllegalArgumentException("auth scheme NONE must not declare a login form — remove the login section or declare a scheme that signs in");
            }
            if (challenge != UiLoginChallenge.NONE) {
                throw new IllegalArgumentException("auth scheme NONE cannot meet a sign-in challenge — remove 'challenge' or declare a scheme that signs in");
            }
        }
        if (!roles.isEmpty() && credentialsPoolRef == null) {
            throw new IllegalArgumentException("auth roles are requested from a credentials pool, so declaring roles requires a credentialsPoolRef");
        }
        if (scheme == UiAuthScheme.FORM || scheme == UiAuthScheme.STORAGE_STATE) {
            requireSigningInIsConfigured(scheme, credentialsPoolRef, login);
        }
    }

    /**
     * Creates a config without a login form or a challenge — the shape an application has before its
     * sign-in is described, and the shape every scheme that does not sign in through the browser keeps.
     *
     * @param scheme how the test signs in (never null)
     * @param credentialsPoolRef reference resolving to the pool of test accounts (may be null)
     * @param roles the roles a scenario may request (may be null, read as empty)
     * @param discoveryAccountRef reference resolving to the exploration account (may be null)
     */
    public UiAuthConfig(UiAuthScheme scheme, String credentialsPoolRef, List<String> roles, String discoveryAccountRef) {
        this(scheme, credentialsPoolRef, roles, discoveryAccountRef, null, UiLoginChallenge.NONE);
    }

    /**
     * Creates a config for an application that needs no sign-in.
     *
     * @return the no-authentication config
     */
    public static UiAuthConfig none() {
        return new UiAuthConfig(UiAuthScheme.NONE, null, List.of(), null, null, UiLoginChallenge.NONE);
    }

    /**
     * Whether a scenario requesting an account must name a role. "Any account" is deliberately not
     * expressible once roles are declared: a precondition of a case ("as a manager") and a negative check
     * of permissions are otherwise unwritable.
     *
     * @return true when the application declares the roles a scenario may request
     */
    public boolean rolesDeclared() {
        return !roles.isEmpty();
    }

    /**
     * The elements the adapter needs in order to sign in, when this scheme signs in at all.
     *
     * <p>Both schemes that reach the browser are checked here rather than at run time, so a registry that
     * cannot possibly work is rejected when it is read. {@code signedIn} is required by both: it is the
     * only evidence the SDK has that a sign-in completed, and the only way a reused session can be told
     * from an expired one.
     */
    private static void requireSigningInIsConfigured(UiAuthScheme scheme, String credentialsPoolRef, UiLoginFormConfig login) {
        if (credentialsPoolRef == null) {
            throw new IllegalArgumentException("auth scheme " + scheme + " signs in as a test account, so it requires a credentialsPoolRef naming the variable that holds the account roster");
        }
        // The keys are spelled exactly as both configuration surfaces accept them. A message that names a key
        // the loader would then reject costs the reader a second failed run for one mistake.
        if (login == null) {
            throw new IllegalArgumentException("auth scheme " + scheme
                    + " requires a login section — at least 'signed-in-locator', which is how a completed sign-in and a live session are recognised");
        }
        if (login.signedInLocator() == null) {
            throw new IllegalArgumentException("auth scheme " + scheme
                    + " requires login.signed-in-locator — the element present only once signed in, which is how rejected credentials and an expired session are told apart from success");
        }
        if (scheme == UiAuthScheme.FORM && !login.fillable()) {
            throw new IllegalArgumentException("auth scheme FORM fills a login form, so login.username-locator, login.password-locator and login.submit-locator are all required");
        }
    }

    private static void requireReferenceOrAbsent(String value, String message) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
