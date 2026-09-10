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
 *
 * <p><strong>LITERAL contract (SDK-internal).</strong> A trusted configuration mapper that has
 * already resolved an endpoint value (the Spring starter, where Spring expands {@code ${VAR:}}
 * placeholders at context startup) may wrap the value with {@link #literal(String)} before building
 * the core {@code *Definition} records. {@link #resolve} returns the wrapped remainder verbatim —
 * including the empty string — without any environment lookup, so every adapter resolver accepts it
 * unchanged. The marker is an internal protocol, never a configuration spelling:
 * {@link #requireReferenceShape} rejects it fail-closed on both configuration front-ends, and
 * secret-bearing fields (auth, datasource user/password, SASL) are never wrapped — they stay pure
 * references.
 */
public final class SecretReferences {

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:{}\\s]+)(?::(.*))?\\}$");

    private static final String LITERAL_PREFIX = "literal://";

    private SecretReferences() {
    }

    /**
     * Wraps an already-resolved value with the SDK-internal literal marker so it rides through the
     * {@code *Ref} model fields and the adapters' default resolvers verbatim. The empty string is a
     * legal value: the marker keeps the wrapped form non-blank for the core record invariants while
     * {@link #resolve} still yields {@code ""}, deferring the failure to step execution.
     *
     * @param value the resolved value to carry verbatim (may be empty, never null)
     * @return the marker-wrapped value
     */
    public static String literal(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return LITERAL_PREFIX + value;
    }

    /**
     * Tells whether the given reference carries the SDK-internal literal marker.
     *
     * @param reference the reference to inspect (may be null)
     * @return true when the reference is a {@link #literal(String)}-wrapped value
     */
    public static boolean isLiteral(String reference) {
        return reference != null && reference.startsWith(LITERAL_PREFIX);
    }

    /**
     * Refuses a configured VALUE that already carries the SDK-internal marker, before a mapper wraps it.
     *
     * <p>{@link #requireReferenceShape} refuses the marker in a {@code *-ref} field. The value twins had
     * no such guard, and the marker is not self-cancelling: wrapping {@code literal://hunter2} yields
     * {@code literal://literal://hunter2}, and {@link #resolve} strips one prefix, so the adapter is
     * handed {@code literal://hunter2} as the credential. The sign-in then fails at the identity provider
     * talking about the credential, with nothing pointing back at the configuration — the failure mode
     * the marker's "never appears in configuration" rule exists to prevent. Both front-ends call this, so
     * the guard cannot cover one surface and miss the other.
     *
     * @param value the configured value (may be null — nothing to refuse)
     * @param field the surface field name, for the message
     * @param location the configuration location, for the message
     * @throws StandTestException if the value carries the marker
     */
    public static void rejectLiteralMarkerInValue(String value, String field, String location) {
        if (value != null && isLiteral(value.trim())) {
            throw new StandTestException("Field '" + field + "' at " + location
                    + " carries the SDK-internal literal marker — it must never appear in configuration."
                    + " Write the value itself here, without the marker.");
        }
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
        if (isLiteral(reference)) {
            return reference.substring(LITERAL_PREFIX.length());
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
        if (isLiteral(value.trim())) {
            throw new StandTestException("Field '" + field + "' at " + location
                    + " carries the SDK-internal literal marker — it must never appear in configuration; in the Spring starter use the "
                    + "sibling value field (base-url/url/target/bootstrap-servers/security-protocol) instead");
        }
        if (PLACEHOLDER.matcher(value.trim()).matches()) {
            return value;
        }
        if (value.trim().startsWith("${")) {
            throw new StandTestException("Field '" + field + "' at " + location
                    + " looks like a malformed placeholder — use ${ENV_VAR} or ${ENV_VAR:default} (no whitespace or nested "
                    + "braces in the variable name)");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.chars().anyMatch(Character::isWhitespace)
                || value.contains("://")
                || lower.startsWith("bearer ")
                || lower.startsWith("basic ")) {
            throw new StandTestException("Field '" + field + "' at " + location
                    + " must be a reference NAME (an env-var / secret entry) or a ${ENV_VAR:default} placeholder, but the value looks like "
                    + "a resolved endpoint or an inline secret — never put bare URLs, credentials or connection "
                    + "strings in the configuration");
        }
        return value;
    }
}
