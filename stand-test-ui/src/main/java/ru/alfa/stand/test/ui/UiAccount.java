package ru.alfa.stand.test.ui;

/**
 * One technical account of a UI application's pool, as the account roster declares it.
 *
 * <p>It carries <strong>references</strong>, never credentials: {@code usernameRef} and
 * {@code passwordRef} are the names of environment variables (or {@code ${VAR}} placeholders), resolved
 * only at the moment the sign-in form is filled and never held anywhere else. That is the same discipline
 * the rest of the SDK applies to service credentials, and it is what lets an account inventory be printed
 * in a diagnostic message without printing a secret.
 *
 * <p>The {@code accountId} is the identity everything else is keyed by — most importantly the browser
 * storage state, which belongs to an <em>account</em> and not to a test suite (BR-29). It must therefore
 * be stable across runs and unique within an application.
 *
 * @param accountId the stable identity of the account within its application
 * @param role the role this account plays, the thing a scenario asks for
 * @param usernameRef reference resolving to the login
 * @param passwordRef reference resolving to the password
 */
public record UiAccount(String accountId, String role, String usernameRef, String passwordRef) {

    /**
     * Validates the account. Every field is mandatory: an account without an id cannot own a session
     * state, and an account without a role cannot be requested.
     */
    public UiAccount {
        accountId = requireNonBlank(accountId, "accountId");
        role = requireNonBlank(role, "role");
        usernameRef = requireNonBlank(usernameRef, "usernameRef");
        passwordRef = requireNonBlank(passwordRef, "passwordRef");
    }

    /**
     * A description safe to print: the id and the role, plus the <em>names</em> of the variables holding
     * the credentials. No value is resolved here, so this never renders a secret.
     *
     * @return the printable description
     */
    public String describe() {
        return accountId + " (role '" + role + "', credentials from " + usernameRef + " / " + passwordRef + ")";
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("UI account " + field + " must not be blank");
        }
        return value.trim();
    }
}
