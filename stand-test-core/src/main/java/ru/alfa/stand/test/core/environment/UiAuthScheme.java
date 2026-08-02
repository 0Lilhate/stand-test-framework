package ru.alfa.stand.test.core.environment;

/**
 * How a test signs in to a UI application. Spelled {@code scheme} in configuration — the same key the
 * service-level {@link AuthScheme} uses, so one registry file has one word for one concept.
 *
 * <p>This enum is the <strong>configuration vocabulary</strong>. Performing a sign-in is the UI
 * adapter's concern and is not part of the registry.
 */
public enum UiAuthScheme {

    /** The application needs no sign-in. */
    NONE,

    /** Sign in by filling the application's own login form with credentials taken from secret references. */
    FORM,

    /** Reuse a previously issued browser storage state instead of passing the login form. */
    STORAGE_STATE,

    /** Single sign-on through an external identity provider. */
    SSO
}
