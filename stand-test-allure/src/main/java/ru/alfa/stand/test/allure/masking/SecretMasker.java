package ru.alfa.stand.test.allure.masking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Masks secret-looking values before they are published to Allure.
 *
 * <p>A defence-in-depth net at the reporting sink: even though adapters are expected to redact evidence
 * at the source (the core {@code Attachment} contract), the diagnostics and parameter maps that this
 * adapter renders as Allure parameters/attachments may still carry a sensitive entry. The masker
 * replaces the <em>value</em> of any entry whose <em>key</em> contains a known secret marker
 * (case-insensitive): {@code password}, {@code secret}, {@code token}, {@code authorization},
 * {@code apikey}, {@code cookie}. Matching ignores separators in the key, so hyphenated/underscored
 * header names ({@code X-Api-Key}, {@code Set-Cookie}, {@code Proxy-Authorization}) are caught too. The
 * real value is never read into the result and never logged.
 *
 * <p>Masking is key-based by design. It does not inspect or rewrite free-form values (which could
 * corrupt a JSON/XML body), so it is safe to apply to ordered key/value maps only.
 */
public final class SecretMasker {

    /** The placeholder substituted for a masked value. */
    public static final String MASK = "***";

    private static final List<String> SENSITIVE_MARKERS =
            List.of("password", "secret", "token", "authorization", "apikey", "cookie");

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
     * sensitive, otherwise the value unchanged.
     *
     * @param key the entry key
     * @param value the entry value
     * @return the masked or original value
     */
    public String mask(String key, String value) {
        return isSensitive(key) ? MASK : value;
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
