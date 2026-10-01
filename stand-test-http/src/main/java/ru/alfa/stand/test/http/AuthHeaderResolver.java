package ru.alfa.stand.test.http;

import ru.alfa.stand.test.core.environment.AuthConfig;

/**
 * Resolves a service's {@link AuthConfig} to the value of the outbound {@code Authorization} header.
 *
 * <p>The core environment model stores secret <em>references</em> (for example environment-variable
 * names), never credential values (plan §9). This seam turns those references into a ready header
 * value at run time — the sanctioned alternative to inline {@code Authorization} headers, which the
 * scenario validator rejects — and lets tests substitute a resolver with canned credentials.
 */
@FunctionalInterface
public interface AuthHeaderResolver {

    /**
     * Resolves the given auth config to the {@code Authorization} header value.
     *
     * @param auth the service auth config taken from the environment registry
     * @return the header value (for example {@code Basic dXNlcjpwYXNz} or {@code Bearer token})
     */
    String resolve(AuthConfig auth);
}
