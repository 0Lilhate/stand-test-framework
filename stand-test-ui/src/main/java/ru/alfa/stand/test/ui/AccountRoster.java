package ru.alfa.stand.test.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Reads the account roster a UI application's {@code credentials-pool-ref} points at.
 *
 * <p>The reference names an environment variable; its value is the roster. What the roster contains is
 * <strong>names</strong> — account ids, roles and the names of the variables holding the credentials —
 * and never a login or a password. Two levels of indirection therefore separate a registry file from a
 * secret, and the invariant of ADR-UI-006 §5 holds by construction: there is no field a credential could
 * be written into.
 *
 * <p>Grammar. Entries are separated by {@code ;} or a newline; fields inside an entry by {@code :}. An
 * entry has either two fields or four:
 *
 * <pre>
 * portal-client-1:client
 * portal-client-2:client:PORTAL_CLIENT_2_USERNAME:PORTAL_CLIENT_2_PASSWORD
 * </pre>
 *
 * <p>The two-field form derives the credential variables from the account id
 * ({@code portal-client-1} → {@code PORTAL_CLIENT_1_USERNAME} / {@code PORTAL_CLIENT_1_PASSWORD}), which
 * is what makes a roster of ten accounts writable. The four-field form spells them out for the case where
 * the variables were named by somebody else.
 *
 * <p>A roster field must be a <em>bare</em> variable name — no {@code ${VAR}} placeholder. That is a
 * deliberate tightening of the SDK's usual reference spelling: {@code :} separates the fields here, and a
 * {@code ${VAR:default}} placeholder would make the entry ambiguous. The roster itself already lives in an
 * environment variable, so a placeholder inside it would be indirection without a purpose.
 *
 * <p><strong>No rejected value is ever echoed.</strong> A parse error names the entry's position and the
 * shape expected, never the text that failed the check: the one way a secret could reach this parser is a
 * credential typed where a variable name belongs, and an error message that printed it back would carry
 * that mistake into the log and the report. Values that <em>passed</em> a shape check (an account id, a
 * role, a variable name) are safe to name and are named.
 */
final class AccountRoster {

    private static final Pattern ENTRY_SEPARATOR = Pattern.compile("[;\\r\\n]+");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Pattern NON_VARIABLE_CHARACTER = Pattern.compile("[^A-Za-z0-9]+");

    private AccountRoster() {
    }

    /**
     * Parses a roster.
     *
     * @param roster the resolved value of the credentials-pool variable
     * @param application the application alias, for the diagnostics
     * @param poolRef the name of the variable the roster came from, for the diagnostics
     * @param discoveryAccountRef the exploration account's reference, which must not appear in the pool
     *     (may be null when the application declares none)
     * @return the parsed accounts, in roster order
     * @throws StandTestException if the roster is empty or an entry is malformed
     */
    static List<UiAccount> parse(String roster, String application, String poolRef, String discoveryAccountRef) {
        if (roster == null || roster.isBlank()) {
            throw new StandTestException("The account roster of UI application '" + application + "' is empty: variable '" + poolRef
                    + "' is unset or blank. It must list the test accounts as '<accountId>:<role>' entries separated by ';', for example"
                    + " 'portal-client-1:client;portal-manager-1:manager'.");
        }
        List<UiAccount> accounts = new ArrayList<>();
        int position = 0;
        for (String rawEntry : ENTRY_SEPARATOR.split(roster)) {
            String entry = rawEntry.trim();
            if (!entry.isEmpty()) {
                position++;
                accounts.add(account(entry, position, application, poolRef));
            }
        }
        if (accounts.isEmpty()) {
            throw new StandTestException("The account roster of UI application '" + application + "' in variable '" + poolRef
                    + "' contains no entries");
        }
        rejectDiscoveryAccount(accounts, application, discoveryAccountRef);
        return List.copyOf(accounts);
    }

    private static UiAccount account(String entry, int position, String application, String poolRef) {
        String[] fields = entry.split(":", -1);
        if (fields.length != 2 && fields.length != 4) {
            throw new StandTestException(malformed(position, application, poolRef) + ": an entry has either 2 fields ('<accountId>:<role>')"
                    + " or 4 ('<accountId>:<role>:<usernameVariable>:<passwordVariable>'), but this one has " + fields.length);
        }
        String accountId = identifier(fields[0], "accountId", position, application, poolRef);
        String role = identifier(fields[1], "role", position, application, poolRef);
        if (fields.length == 2) {
            String prefix = derivedPrefix(accountId);
            return new UiAccount(accountId, role, prefix + "_USERNAME", prefix + "_PASSWORD");
        }
        return new UiAccount(
                accountId,
                role,
                variableName(fields[2], "usernameVariable", position, application, poolRef),
                variableName(fields[3], "passwordVariable", position, application, poolRef));
    }

    /**
     * SEC-10, enforced where it can be: exploration and execution must not share accounts. The SDK cannot
     * police the rights of an account — that is granted outside it — but it can refuse a configuration in
     * which the exploration account is also in the working pool, which is the one half that is checkable
     * from here.
     */
    private static void rejectDiscoveryAccount(List<UiAccount> accounts, String application, String discoveryAccountRef) {
        if (discoveryAccountRef == null) {
            return;
        }
        for (UiAccount account : accounts) {
            if (discoveryAccountRef.equals(account.usernameRef()) || discoveryAccountRef.equals(account.accountId())) {
                throw new StandTestException("The discovery account of UI application '" + application + "' ('" + discoveryAccountRef
                        + "') also appears in the working account pool, as account '" + account.accountId()
                        + "'. Exploration runs under an account without the right to perform irreversible actions and must "
                        + "stay outside the pool (SEC-10);"
                        + " give the pool its own accounts.");
            }
        }
    }

    private static String derivedPrefix(String accountId) {
        return NON_VARIABLE_CHARACTER.matcher(accountId.toUpperCase(Locale.ROOT)).replaceAll("_");
    }

    private static String identifier(String value, String field, int position, String application, String poolRef) {
        String trimmed = value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new StandTestException(malformed(position, application, poolRef) + ": field '" + field
                    + "' must be a name of letters, digits, '.', '_' or '-' (the offending value is not repeated here — it "
                    + "may be a mistyped credential)");
        }
        return trimmed;
    }

    private static String variableName(String value, String field, int position, String application, String poolRef) {
        String trimmed = value.trim();
        if (!VARIABLE_NAME.matcher(trimmed).matches()) {
            throw new StandTestException(malformed(position, application, poolRef) + ": field '" + field
                    + "' must be a bare environment-variable NAME (letters, digits and '_'), and the roster never holds a credential value."
                    + " It does not accept ${...} placeholders either: ':' separates its fields."
                    + " (The offending value is not repeated here — it may be the credential itself.)");
        }
        return trimmed;
    }

    private static String malformed(int position, String application, String poolRef) {
        return "Entry #" + position + " of the account roster of UI application '" + application + "' (variable '" + poolRef
                + "') is malformed";
    }
}
