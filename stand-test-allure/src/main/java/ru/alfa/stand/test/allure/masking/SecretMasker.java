package ru.alfa.stand.test.allure.masking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secret-looking values before they are published to Allure.
 *
 * <p>The net covers every surface this adapter renders: step parameters and KEY_VALUE diagnostics via
 * the key/value methods, and free-form attachment bodies via {@link #maskText(String)} — the sink-side
 * second echelon behind the producers' pre-redaction contract. A non-sensitive entry's value is copied
 * into the result as-is. The key/value surface applies two checks:
 * <ul>
 *   <li><em>By key</em>: the value of any entry whose key contains a known secret marker
 *   (case-insensitive) is replaced — {@code password}, {@code secret}, {@code token},
 *   {@code authorization}, {@code apikey}, {@code cookie}. Matching ignores separators in the key, so
 *   {@code X-Api-Key}, {@code Set-Cookie}, {@code Proxy-Authorization} are caught too.</li>
 *   <li><em>By value shape</em>: a value that IS a single {@code Bearer}/{@code Basic} credential token
 *   (scheme prefix + one credential-shaped token of 8+ characters) is replaced even under an innocuous
 *   key. Prose that merely starts with those words ({@code "Basic authentication required"}) does not
 *   match.</li>
 * </ul>
 *
 * <p>{@link #maskText(String)} applies the same marker list to JSON scalar fields (a sensitive key's
 * string/number/boolean/null value becomes {@code "***"}) and masks embedded {@code Bearer}/{@code
 * Basic} credential tokens anywhere in the text. Limitations: an object or array nested under a
 * sensitive key is not masked wholesale (though sensitive keys inside it are), and non-JSON
 * {@code key=value} property lines are not rewritten.
 */
public final class SecretMasker {

    /** The placeholder substituted for a masked value. */
    public static final String MASK = "***";

    private static final List<String> SENSITIVE_MARKERS =
            List.of("password", "secret", "token", "authorization", "apikey", "cookie");

    /** Everything a key may spell a marker with — {@code X-Api-Key} and {@code apiKey} both reduce to {@code apikey}. */
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[^a-z0-9]");

    private static final Pattern CREDENTIAL_SHAPED_VALUE =
            Pattern.compile("^\\s*(?i:bearer|basic)\\s+[A-Za-z0-9+/=_.\\-]{8,}\\s*$");

    private static final Pattern JSON_SCALAR_FIELD = Pattern.compile(
            "\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"(\\s*+:\\s*+)"
                    + "(\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"|-?+[0-9][0-9eE+.\\-]*+|true|false|null)");

    private static final Pattern EMBEDDED_CREDENTIAL = Pattern.compile(
            "(?i)\\b(bearer|basic)([ \\t]++)([A-Za-z0-9+/=_.\\-]{8,}+)");

    /**
     * Returns whether the given key names a sensitive value. Matching is case-insensitive and ignores
     * non-alphanumeric separators, so {@code X-Api-Key} and {@code apiKey} both match {@code apikey}.
     *
     * @param key the entry key (may be null)
     * @return true if the key contains a known secret marker
     */
    public boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String normalized = KEY_SEPARATORS.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("");
        for (String marker : SENSITIVE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the value to publish for the given key: the {@link #MASK} placeholder when the key is
     * sensitive or the value itself is a single {@code Bearer}/{@code Basic} credential token,
     * otherwise the value unchanged.
     *
     * @param key the entry key
     * @param value the entry value
     * @return the masked or original value
     */
    public String mask(String key, String value) {
        if (isSensitive(key)) {
            return MASK;
        }
        if (value != null && CREDENTIAL_SHAPED_VALUE.matcher(value).matches()) {
            return MASK;
        }
        return value;
    }

    /**
     * Returns a copy of the given map with sensitive values masked, preserving iteration order. A
     * sensitive entry's value becomes {@link #MASK} regardless of whether it was null; a non-sensitive
     * value is copied as-is.
     *
     * @param entries the entries to mask (may be null)
     * @return an ordered, masked copy (empty when {@code entries} is null)
     */
    public Map<String, String> mask(Map<String, String> entries) {
        Map<String, String> masked = new LinkedHashMap<>();
        if (entries == null) {
            return masked;
        }
        entries.forEach((key, value) -> masked.put(key, mask(key, value)));
        return masked;
    }

    /**
     * Masks secret-looking content inside a free-form attachment body: the scalar value of any JSON
     * field whose key is {@linkplain #isSensitive(String) sensitive} becomes {@code "***"}, and any
     * embedded {@code Bearer}/{@code Basic} credential token (8+ token characters including at least
     * one non-letter, so prose like {@code "Basic authentication required"} is untouched) is replaced
     * with {@code ***} while keeping the scheme word. Non-secret content passes through unchanged.
     *
     * @param content the attachment body (may be null)
     * @return the masked body, or {@code content} itself when null or empty
     */
    public String maskText(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        return maskEmbeddedCredentials(maskJsonScalarFields(content));
    }

    private String maskJsonScalarFields(String content) {
        return replaceEach(JSON_SCALAR_FIELD, content, match -> isSensitive(match.group(1))
                ? "\"" + match.group(1) + "\"" + match.group(2) + "\"" + MASK + "\""
                : match.group());
    }

    private String maskEmbeddedCredentials(String content) {
        return replaceEach(EMBEDDED_CREDENTIAL, content, match -> looksLikeCredentialToken(match.group(3))
                ? match.group(1) + match.group(2) + MASK
                : match.group());
    }

    private static String replaceEach(Pattern pattern, String content, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(content);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(masked, Matcher.quoteReplacement(replacement.apply(matcher)));
        }
        matcher.appendTail(masked);
        return masked.toString();
    }

    private static boolean looksLikeCredentialToken(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isLetter(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
