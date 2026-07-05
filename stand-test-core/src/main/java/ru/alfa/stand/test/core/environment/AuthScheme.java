package ru.alfa.stand.test.core.environment;

/**
 * Authentication scheme applied by an adapter when calling a service whose endpoint definition
 * carries an {@link AuthConfig}.
 */
public enum AuthScheme {

    /** HTTP basic authentication: {@code Authorization: Basic base64(username:password)}. */
    BASIC,

    /** Bearer-token authentication: {@code Authorization: Bearer token}. */
    BEARER
}
