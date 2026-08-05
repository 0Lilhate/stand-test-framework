package ru.alfa.stand.test.ui;

import java.nio.file.Path;

/**
 * What a {@code ui.login} step did, as the step's diagnostics report it.
 *
 * @param accountId the account signed in
 * @param role the role of that account
 * @param sessionReused whether a previously saved browser session was restored and found alive, so no
 *     login form was filled — the difference between a two-second step and a ten-second one, and the thing
 *     a reader of a slow report wants to know first
 * @param storageState the file the session state was saved to, or null when this sign-in involved none.
 *     The path only: the file itself holds live cookies and is never attached, logged or printed
 */
record UiLoginOutcome(String accountId, String role, boolean sessionReused, Path storageState) {
}
