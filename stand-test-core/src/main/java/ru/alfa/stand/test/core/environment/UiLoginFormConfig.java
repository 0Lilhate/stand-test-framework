package ru.alfa.stand.test.core.environment;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where the sign-in form of a {@link UiApplicationDefinition} is, and which elements it is made of.
 *
 * <p>Every element is a <strong>locator expression</strong> spelled {@code <strategy>=<value>} — an
 * opaque string in this record, parsed by the UI adapter into its own locator type. The indirection is on
 * purpose: {@code stand-test-core} must stay free of browser vocabulary (it has no {@code UiLocator}, no
 * locator strategies and no Playwright), and a registry section is configuration, not a model. The adapter
 * owns the grammar and documents it; core only guarantees the {@code strategy=} shape — which is also what
 * stops a credential from being written into {@code password-locator}, the one plausible way this section
 * could grow a secret.
 *
 * <p>Which of these fields a given {@link UiAuthScheme} <em>requires</em> is enforced by
 * {@link UiAuthConfig}: a {@code FORM} sign-in needs the three form elements, and both {@code FORM} and
 * {@code STORAGE_STATE} need {@code signedIn} — the element whose presence proves the browser is signed
 * in. That one element carries two jobs, which is why it is mandatory rather than a nicety: it is how a
 * completed sign-in is distinguished from rejected credentials (an assertion about the screen, hence a
 * test failure), and it is how a reused browser session is checked for still being alive before a
 * scenario builds on it.
 *
 * <p>Nothing here is a credential. Logins and passwords live behind the references of
 * {@link UiAuthConfig#credentialsPoolRef()} and never appear in a registry file.
 *
 * @param path the relative path of the sign-in page (may be null — then the sign-in happens on whatever
 *     page the run is already on, which is what an application redirecting to its login screen needs)
 * @param usernameLocator locator expression of the login field (may be null unless the scheme signs in
 *     by form)
 * @param passwordLocator locator expression of the password field (may be null unless the scheme signs
 *     in by form)
 * @param submitLocator locator expression of the submit control (may be null unless the scheme signs in
 *     by form)
 * @param signedInLocator locator expression of the element that is present only once signed in (may be
 *     null only for a scheme that never signs in through the browser)
 */
public record UiLoginFormConfig(
        String path,
        String usernameLocator,
        String passwordLocator,
        String submitLocator,
        String signedInLocator) {

    private static final Pattern LOCATOR_EXPRESSION = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*=\\S.*$", Pattern.DOTALL);

    public UiLoginFormConfig {
        requireRelativePathOrAbsent(path);
        requireLocatorExpressionOrAbsent(usernameLocator, "username-locator");
        requireLocatorExpressionOrAbsent(passwordLocator, "password-locator");
        requireLocatorExpressionOrAbsent(submitLocator, "submit-locator");
        requireLocatorExpressionOrAbsent(signedInLocator, "signed-in-locator");
        if (path == null && usernameLocator == null && passwordLocator == null && submitLocator == null && signedInLocator == null) {
            throw new IllegalArgumentException("a login section that declares nothing is a configuration "
                    + "mistake — remove it or fill it in");
        }
    }

    /**
     * Whether this configuration describes a form the adapter can actually fill and submit. A scheme that
     * reuses a prepared session declares only {@code signedIn}; when it also declares the three form
     * elements, the adapter may fall back to signing in by form after the prepared session expires.
     *
     * @return true when the login field, the password field and the submit control are all declared
     */
    public boolean fillable() {
        return usernameLocator != null && passwordLocator != null && submitLocator != null;
    }

    /**
     * Shape check for a locator expression: it must carry a {@code strategy=} prefix.
     *
     * <p>This is the same device {@code SecretReferences.requireReferenceShape} uses, and for the same
     * reason. A field spelled {@code password-locator} sits one careless edit away from holding an actual
     * password; requiring the prefix turns that edit into a message when the registry is read, rather than
     * a credential committed to a configuration file. Core checks only the shape — which strategy names
     * exist is the UI adapter's grammar, and the adapter rejects an unknown one when it parses the
     * expression.
     */
    private static void requireLocatorExpressionOrAbsent(String value, String field) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("login form field '" + field + "' must not be blank when declared");
        }
        if (!LOCATOR_EXPRESSION.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("login form field '" + field
                    + "' must be a locator expression '<strategy>=<value>' (for example testId=login-submit or role=button:Sign in)."
                    + " This field addresses an element on the page and never holds a credential; logins and passwords come from the "
                    + "account roster behind credentials-pool-ref."
                    + " (The offending value is not repeated here — it may be the credential itself.)");
        }
    }

    private static void requireRelativePathOrAbsent(String path) {
        if (path == null) {
            return;
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("login form field 'path' must not be blank when declared");
        }
        String lower = path.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")) {
            throw new IllegalArgumentException("login form field 'path' must be relative to the application's base URL, "
                    + "but was the absolute address '"
                    + path + "'");
        }
    }
}
