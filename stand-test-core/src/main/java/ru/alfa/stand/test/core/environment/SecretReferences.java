package ru.alfa.stand.test.core.environment;

import java.util.Locale;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Shape check shared by the configuration front-ends (the Spring starter's properties mapper and
 * {@code stand-test-config}'s YAML mapper) for {@code *-ref} fields: a reference is the NAME of an
 * environment variable / secret entry, never the resolved value.
 *
 * <p>The check is a deny-list of "this is obviously a value, not a name" shapes — whitespace, a
 * {@code ://} scheme separator, a leading {@code Bearer }/{@code Basic } credential prefix — rather
 * than an allow-list regex, so unconventional but legitimate reference names (mixed case, dots,
 * dashes) still pass. It is deliberately NOT enforced by the core {@code *Definition} records:
 * broker-free tests legitimately store resolved addresses in the ref fields together with an
 * identity resolver.
 */
public final class SecretReferences {

    private SecretReferences() {
    }

    /**
     * Validates that the given {@code *-ref} value is plausibly a reference name and returns it.
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
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.chars().anyMatch(Character::isWhitespace)
                || value.contains("://")
                || lower.startsWith("bearer ")
                || lower.startsWith("basic ")) {
            throw new StandTestException("Field '" + field + "' at " + location
                    + " must be a reference NAME (an env-var / secret entry), but the value looks like a resolved endpoint or an inline secret — never put URLs, credentials or connection strings in the configuration");
        }
        return value;
    }
}
