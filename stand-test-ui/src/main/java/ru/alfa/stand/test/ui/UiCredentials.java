package ru.alfa.stand.test.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * A resolved login and password, held for the few milliseconds it takes to type them into a form.
 *
 * <p>Its {@code toString()} is masked. That is not decoration: a record's generated {@code toString}
 * prints every component, and this object passes through logging frames, debugger views and — the case
 * that actually bites — string concatenation in an exception message written months later by someone who
 * did not know what the object was. Masking here makes the careless spelling safe.
 *
 * @param username the resolved login
 * @param password the resolved password
 */
record UiCredentials(String username, String password) {

    /** What replaces a credential wherever the SDK would otherwise print one. */
    static final String MASK = "<masked>";

    /**
     * The values that must never appear in a message, a log line or a report — what
     * {@link UiSecrets#guard} checks against.
     *
     * @return the non-blank secret values
     */
    List<String> values() {
        List<String> secrets = new ArrayList<>(2);
        if (username != null && !username.isBlank()) {
            secrets.add(username);
        }
        if (password != null && !password.isBlank()) {
            secrets.add(password);
        }
        return List.copyOf(secrets);
    }

    @Override
    public String toString() {
        return "UiCredentials[username=" + MASK + ", password=" + MASK + "]";
    }
}
