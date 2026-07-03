package ru.alfa.stand.test.allure.masking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks secret-looking values before they are published to Allure.
 *
 * <p>The net covers the key/value surfaces this adapter renders — step parameters and KEY_VALUE
 * diagnostics. It is NOT a blanket guarantee: core {@code Attachment}s (request/response payloads) are
 * published verbatim per their pre-redaction contract, and a non-sensitive entry's value is copied into
 * the result as-is. Within its surface the masker applies two checks:
 * <ul>
 *   <li><em>By key</em>: the value of any entry whose key contains a known secret marker
 *   (case-insensitive) is replaced — {@code password}, {@code secret}, {@code token},
 *   {@code authorization}, {@code apikey}, {@code cookie}. Matching ignores separators in the key, so
 *   {@code X-Api-Key}, {@code Set-Cookie}, {@code Proxy-Authorization} are caught too.</li>
 *   <li><em>By value shape</em>: a value that IS a single {@code Bearer}/{@code Basic} credential token
 *   (scheme prefix + one credential-shaped token of 8+ characters) is replaced even under an innocuous
 *   key. Prose that merely starts with those words ({@code "Basic authentication required"}) does not
 *   match. Free-form values are otherwise never rewritten (that could corrupt a JSON/XML body).</li>
 * </ul>
 */
public final class SecretMasker {

    /** The placeholder substituted for a masked value. */
    public static final String MASK = "***";

    private static final List<String> SENSITIVE_MARKERS =
            List.of("password", "secret", "token", "authorization", "apikey", "cookie");

    private static final Pattern CREDENTIAL_SHAPED_VALUE =
            Pattern.compile("^\\s*(?i:bearer|basic)\\s+[A-Za-z0-9+/=_.\\-]{8,}\\s*$");

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
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
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
}
