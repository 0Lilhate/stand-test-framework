package ru.alfa.stand.test.rest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.environment.AuthScheme;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link AuthHeaderResolver}: treats every {@link AuthConfig} field as a reference (the name
 * of an environment variable holding the credential) and resolves it indirectly at call time; the
 * {@code ${NAME}}/{@code ${NAME:default}} placeholder spellings are supported via
 * {@link SecretReferences#resolve}.
 *
 * <p>BASIC builds {@code Basic base64(username:password)} with UTF-8 bytes (RFC 7617); a username
 * containing {@code ':'} is rejected. BEARER builds {@code Bearer token} from the trimmed resolved
 * value; embedded whitespace or control characters are rejected because the token travels into the
 * header verbatim (header-injection guard). Failure messages carry the reference NAME only — a
 * resolved credential value never appears in any exception message.
 */
public final class EnvironmentAuthHeaderResolver implements AuthHeaderResolver {

    private final UnaryOperator<String> lookup;

    /**
     * Creates a resolver backed by the process environment ({@link System#getenv(String)}).
     */
    public EnvironmentAuthHeaderResolver() {
        this(System::getenv);
    }

    /**
     * Creates a resolver backed by the given reference lookup (for tests).
     *
     * @param lookup resolves a reference name to its value (or null if unset)
     */
    EnvironmentAuthHeaderResolver(UnaryOperator<String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
    }

    @Override
    public String resolve(AuthConfig auth) {
        Objects.requireNonNull(auth, "auth must not be null");
        if (auth.scheme() == AuthScheme.BASIC) {
            return basicValue(auth);
        }
        return bearerValue(auth);
    }

    private String basicValue(AuthConfig auth) {
        String username = resolveReference(auth.usernameRef(), auth.scheme());
        String password = resolveReference(auth.passwordRef(), auth.scheme());
        if (username.indexOf(':') >= 0) {
            throw new StandTestException("Basic auth username resolved from '" + auth.usernameRef() + "' must not contain ':' (RFC 7617)");
        }
        requireNoControlCharacters(username, auth.usernameRef());
        requireNoControlCharacters(password, auth.passwordRef());
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String bearerValue(AuthConfig auth) {
        String token = resolveReference(auth.tokenRef(), auth.scheme()).trim();
        for (int i = 0; i < token.length(); i++) {
            char symbol = token.charAt(i);
            if (Character.isWhitespace(symbol) || Character.isISOControl(symbol)) {
                throw new StandTestException("Bearer token resolved from auth reference '" + auth.tokenRef() + "' contains whitespace or control characters — refusing to build the Authorization header");
            }
        }
        return "Bearer " + token;
    }

    private String resolveReference(String reference, AuthScheme scheme) {
        String resolved = SecretReferences.resolve(reference, this.lookup);
        if (resolved == null || resolved.isBlank()) {
            throw new StandTestException("Auth reference '" + reference + "' for service auth (scheme " + scheme + ") did not resolve (environment variable not set)");
        }
        return resolved;
    }

    private static void requireNoControlCharacters(String value, String reference) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new StandTestException("Value resolved from auth reference '" + reference + "' contains control characters — refusing to build the Authorization header");
            }
        }
    }
}
