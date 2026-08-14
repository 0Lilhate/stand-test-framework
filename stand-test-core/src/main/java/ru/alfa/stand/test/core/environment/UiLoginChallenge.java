package ru.alfa.stand.test.core.environment;

/**
 * An interactive obstacle a UI sign-in puts between submitting the login form and being signed in.
 *
 * <p>This enum exists to <strong>declare</strong> the obstacle, not to defeat it. The SDK deliberately
 * ships no universal MFA / OTP / CAPTCHA bypass: defeating a second factor is an organisational
 * decision (external gate G-1 of the wave-1 BRD), not a library feature, and a bypass built into a test
 * SDK would be a standing invitation to weaken the real thing. What the SDK offers instead is an
 * extension point — the UI adapter looks for a handler able to resolve the declared challenge and, when
 * none is registered, refuses with a configuration error that names the gate and the sanctioned
 * alternative ({@link UiAuthScheme#STORAGE_STATE}, a session prepared outside the SDK).
 *
 * <p>Declaring the challenge is therefore useful even without a handler: it turns "the test hangs on a
 * one-time-password screen and eventually times out" into "this application is not automatable through
 * a login form; use a prepared session", which is exactly the answer BR-30 asks a team to record.
 */
public enum UiLoginChallenge {

    /** The login form signs in directly; nothing stands between submitting it and being signed in. */
    NONE,

    /** A second authentication factor (push confirmation, hardware token, authenticator app). */
    MFA,

    /** A one-time password delivered out of band (SMS, e-mail, authenticator app). */
    OTP,

    /** A human-presence challenge (CAPTCHA and the like). */
    CAPTCHA
}
