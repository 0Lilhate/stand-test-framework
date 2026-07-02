package ru.alfa.stand.test.grpc;

import java.util.Locale;

/**
 * Guardrail for secret-bearing gRPC metadata keys.
 *
 * <p>Secrets must never be written inline in a scenario (plan §16 / security): the SDK injects the
 * SDK-owned correlation id and any real credentials come from the environment's secret references, not
 * the step. This helper recognises the common secret-bearing metadata names so the builder can reject
 * them up front and the executor can mask their values in diagnostics/attachments.
 */
final class SecretMetadata {

    private static final String MASK = "***";

    private SecretMetadata() {
    }

    static boolean isSecret(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("authorization")
                || lower.contains("token")
                || lower.contains("password")
                || lower.contains("secret")
                || lower.contains("cookie")
                || lower.contains("api-key")
                || lower.contains("apikey")
                || lower.contains("api_key");
    }

    static String maskIfSecret(String name, String value) {
        return isSecret(name) ? MASK : value;
    }
}
