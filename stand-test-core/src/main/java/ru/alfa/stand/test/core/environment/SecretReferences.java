package ru.alfa.stand.test.core.environment;

import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The {@code *-ref} reference syntax shared by the configuration front-ends (the Spring starter's
 * properties mapper and {@code stand-test-config}'s YAML mapper) and by the adapters' default
 * environment resolvers. A reference identifies the environment variable holding the value — the
 * value itself stays out of the configuration. Three spellings are accepted:
 *
 * <ul>
 *   <li>{@code NAME} — the plain environment-variable name (the original form);</li>
 *   <li>{@code ${NAME}} — the same, in the application.yml-familiar placeholder spelling;</li>
 *   <li>{@code ${NAME:default}} — resolve {@code NAME}; when the variable is <em>missing</em> the
 *   inline {@code default} is used (a variable set to an empty value wins over the default, matching
 *   Spring's semantics).</li>
 * </ul>
 *
 * <p><strong>Deliberate guardrail relaxation.</strong> An inline default IS a value in the
 * configuration file — including, if a team chooses so, a stand URL or credential. The SDK allows it
 * for the familiar developer experience, but the trade-off is the consumer's: anything written as a
 * default lives in the repository. Prefer defaults for non-secret DEV endpoints and keep credentials
 * as pure references.
 *
 * <p>{@link #requireReferenceShape} stays fail-closed for the non-placeholder form: a bare value
 * carrying whitespace, a {@code ://} scheme separator or a leading {@code Bearer }/{@code Basic }
 * prefix is rejected as "obviously a value, not a name". It is deliberately NOT enforced by the core
 * {@code *Definition} records: broker-free tests legitimately store resolved addresses in the ref
 * fields together with an identity resolver.
 */
public final class SecretReferences {

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:{}\\s]+)(?::(.*))?\\}$");

    private SecretReferences() {
    }

    /**
     * Resolves a reference through the given lookup, honouring the placeholder syntax. A bare
     * {@code NAME} and {@code ${NAME}} return {@code lookup(NAME)} as-is (possibly null when the
     * variable is missing — the caller owns that error, as before); {@code ${NAME:default}} returns
     * the default only when the variable is missing.
     *
     * @param reference the configured reference (may be null)
     * @param lookup resolves a variable name to its value, or null when unset
     * @return the resolved value, the inline default, or null when unresolved without a default
     */
    public static String resolve(String reference, UnaryOperator<String> lookup) {
        Objects.requireNonNull(lookup, "lookup must not be null");
        if (reference == null) {
            return null;
        }
        Matcher placeholder = PLACEHOLDER.matcher(reference.trim());
        if (!placeholder.matches()) {
            return lookup.apply(reference);
        }
        String value = lookup.apply(placeholder.group(1));
        if (value != null) {
            return value;
        }
        return placeholder.group(2);
    }

    /**
     * Validates that the given {@code *-ref} value is plausibly a reference (a bare name or a
     * {@code ${NAME}}/{@code ${NAME:default}} placeholder) and returns it verbatim — resolution
     * happens later, at the point of use.
     *
     * @param value the configured reference value
     * @param field the field name for the error message (for example {@code url-ref})
     * @param location the configuration location for the error message
     * @return the validated value
     * @throws StandTestException if the value looks like a resolved endpoint or an inline secret
     */
    public static String requireReferenceShape(String value, String field, String location) {
        if (value == null || value.isBlank()) {
            throw new StandTestException("Field '" + field + "' at " + location + " must be a non-blank reference name");
        }
        if (PLACEHOLDER.matcher(value.trim()).matches()) {
            return value;
        }
        if (value.trim().startsWith("${")) {
            throw new StandTestException("Field '" + field + "' at " + location
                    + " looks like a malformed placeholder — use ${ENV_VAR} or ${ENV_VAR:default} (no whitespace or nested braces in the variable name)");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.chars().anyMatch(Character::isWhitespace)
                || value.contains("://")
                || lower.startsWith("bearer ")
                || lower.startsWith("basic ")) {
            throw new StandTestException("Field '" + field + "' at " + location
                    + " must be a reference NAME (an env-var / secret entry) or a ${ENV_VAR:default} placeholder, but the value looks like a resolved endpoint or an inline secret — never put bare URLs, credentials or connection strings in the configuration");
        }
        return value;
    }
}
