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
 * the SDK resolves an account for it.
 *
 * <p><strong>Two ways to give an application its accounts, and they exclude each other.</strong> A
 * {@code credentialsPoolRef} points at a roster of several accounts, keyed by role — the original model
 * of ADR-UI-006. {@code credentialsUsername}/{@code credentialsPassword} name one account directly, for
 * an application that has exactly one; that pair answers <em>every</em> declared role, since there is
 * nothing to choose between. Both are still references: a bare value is the NAME of an environment
 * variable, and the {@code ${VAR:value}} spelling is what puts a value in the file. The pair is
 * therefore what makes an inline credential expressible at all — a deliberate relaxation of
 * ADR-UI-006 §5, accepted by the line owner, with the same standing rule the datasource password
 * carries: never give a default to a password.
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
 * @param credentialsUsername reference resolving to the login of the application's single technical
 *     account — the alternative to a roster for an application that has exactly one (may be null; when
 *     present, {@code credentialsPassword} is required and {@code credentialsPoolRef} must be absent)
 * @param credentialsPassword reference resolving to that account's password (may be null; required
 *     together with {@code credentialsUsername})
 */
public record UiAuthConfig(
        UiAuthScheme scheme,
        String credentialsPoolRef,
        List<String> roles,
        String discoveryAccountRef,
        UiLoginFormConfig login,
        UiLoginChallenge challenge,
        String credentialsUsername,
        String credentialsPassword) {

    public UiAuthConfig {
        Objects.requireNonNull(scheme, "scheme must not be null");
        roles = (roles == null) ? List.of() : List.copyOf(roles);
        challenge = (challenge == null) ? UiLoginChallenge.NONE : challenge;
        requireReferenceOrAbsent(credentialsPoolRef, "credentialsPoolRef must not be blank when declared");
        requireReferenceOrAbsent(discoveryAccountRef, "discoveryAccountRef must not be blank when declared");
        requireReferenceOrAbsent(credentialsUsername, "credentialsUsername must not be blank when declared");
        requireReferenceOrAbsent(credentialsPassword, "credentialsPassword must not be blank when declared");
        requireCredentialPairIsWhole(credentialsUsername, credentialsPassword);
        if (credentialsPoolRef != null && credentialsUsername != null) {
            throw new IllegalArgumentException("auth declares both a credentials-pool-ref and a direct "
                    + "credentials-username/credentials-password pair,"
                    + " and which of the two the accounts come from would be a coin toss — keep the roster for several "
                    + "accounts, or the pair for exactly one");
        }
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("auth roles must not be blank");
            }
        }
        if (roles.size() != Set.copyOf(roles).size()) {
            throw new IllegalArgumentException("auth roles must not contain duplicates, but were " + roles);
        }
        if (scheme == UiAuthScheme.NONE) {
            if (credentialsPoolRef != null || !roles.isEmpty() || discoveryAccountRef != null || credentialsUsername != null) {
                throw new IllegalArgumentException("auth scheme NONE must not carry credentials —"
                        + " remove credentialsPoolRef/credentialsUsername/credentialsPassword/roles/discoveryAccountRef or "
                        + "declare a scheme that signs in");
            }
            if (login != null) {
                throw new IllegalArgumentException("auth scheme NONE must not declare a login form — remove the login section or "
                        + "declare a scheme that signs in");
            }
            if (challenge != UiLoginChallenge.NONE) {
                throw new IllegalArgumentException("auth scheme NONE cannot meet a sign-in challenge — remove 'challenge' or "
                        + "declare a scheme that signs in");
            }
        }
        if (!roles.isEmpty() && credentialsPoolRef == null && credentialsUsername == null) {
            throw new IllegalArgumentException("auth roles are requested from a credentials pool, so declaring roles "
                    + "requires a credentialsPoolRef"
                    + " — or a direct credentials-username/credentials-password pair, which answers every "
                    + "declared role with the same account");
        }
        if (scheme == UiAuthScheme.FORM || scheme == UiAuthScheme.STORAGE_STATE) {
            requireSigningInIsConfigured(scheme, credentialsPoolRef, credentialsUsername, login);
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
        this(scheme, credentialsPoolRef, roles, discoveryAccountRef, null, UiLoginChallenge.NONE, null, null);
    }

    /**
     * Creates a config that draws its accounts from a roster — the shape every application had before a
     * single application could name its one account directly.
     *
     * @param scheme how the test signs in (never null)
     * @param credentialsPoolRef reference resolving to the pool of test accounts (may be null)
     * @param roles the roles a scenario may request (may be null, read as empty)
     * @param discoveryAccountRef reference resolving to the exploration account (may be null)
     * @param login where the sign-in form is and what it is made of (may be null)
     * @param challenge the interactive obstacle after the form (may be null, read as NONE)
     */
    public UiAuthConfig(
            UiAuthScheme scheme,
            String credentialsPoolRef,
            List<String> roles,
            String discoveryAccountRef,
            UiLoginFormConfig login,
            UiLoginChallenge challenge) {
        this(scheme, credentialsPoolRef, roles, discoveryAccountRef, login, challenge, null, null);
    }

    /**
     * Creates a config for an application that needs no sign-in.
     *
     * @return the no-authentication config
     */
    public static UiAuthConfig none() {
        return new UiAuthConfig(UiAuthScheme.NONE, null, List.of(), null, null, UiLoginChallenge.NONE, null, null);
    }

    /**
     * Whether the application names its single technical account directly instead of pointing at a roster.
     *
     * <p>The two are mutually exclusive by construction, so this is also the answer to "where do the
     * accounts come from": the pair, or the pool.
     *
     * @return true when a credentials-username/credentials-password pair is declared
     */
    public boolean hasDirectCredentials() {
        return credentialsUsername != null;
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
    private static void requireSigningInIsConfigured(UiAuthScheme scheme, String credentialsPoolRef, String credentialsUsername,
            UiLoginFormConfig login) {
        if (credentialsPoolRef == null && credentialsUsername == null) {
            throw new IllegalArgumentException("auth scheme " + scheme
                    + " signs in as a test account, so it requires somewhere to draw one from:"
                    + " a credentialsPoolRef naming the variable that holds the account roster, or a "
                    + "credentials-username/credentials-password pair for an application with exactly one account");
        }
        // The keys are spelled exactly as both configuration surfaces accept them. A message that names a key
        // the loader would then reject costs the reader a second failed run for one mistake.
        if (login == null) {
            throw new IllegalArgumentException("auth scheme " + scheme
                    + " requires a login section — at least 'signed-in-locator', which is how a completed sign-in and "
                    + "a live session are recognised");
        }
        if (login.signedInLocator() == null) {
            throw new IllegalArgumentException("auth scheme " + scheme
                    + " requires login.signed-in-locator — the element present only once signed in, which is how rejected credentials and "
                    + "an expired session are told apart from success");
        }
        if (scheme == UiAuthScheme.FORM && !login.fillable()) {
            throw new IllegalArgumentException("auth scheme FORM fills a login form, so login.username-locator, login.password-locator and "
                    + "login.submit-locator are all required");
        }
    }

    private static void requireReferenceOrAbsent(String value, String message) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Refuses half a credential pair. Half is worse than none: an application that names a login and no
     * password would start a browser, fill one field and fail on a screen, when the registry could have
     * said so as it was read.
     */
    private static void requireCredentialPairIsWhole(String credentialsUsername, String credentialsPassword) {
        if (credentialsUsername != null && credentialsPassword == null) {
            throw new IllegalArgumentException("auth declares credentialsUsername without credentialsPassword — an "
                    + "account needs both, or neither");
        }
        if (credentialsPassword != null && credentialsUsername == null) {
            throw new IllegalArgumentException("auth declares credentialsPassword without credentialsUsername — an "
                    + "account needs both, or neither");
        }
    }
}
