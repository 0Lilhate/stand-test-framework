package ru.alfa.stand.test.ui;

import ru.alfa.stand.test.core.environment.UiLoginChallenge;

/**
 * The extension point for getting past a second factor, a one-time password or a human-presence check
 * during {@code ui.login}.
 *
 * <p><strong>The SDK ships no implementation, and that is the decision, not an omission.</strong> A
 * universal MFA / OTP / CAPTCHA bypass is not a library feature: whether a second factor may be weakened
 * for test accounts, and how, is an organisational answer that belongs to the people who own the factor —
 * external gate G-1 of the wave-1 BRD. A bypass built into a test SDK would be a standing invitation to
 * weaken the real thing, and it would be wrong for every application in a different way.
 *
 * <p>What the SDK does instead is refuse honestly. An application declaring a challenge with no handler on
 * the classpath fails the sign-in step with a configuration error naming the gate and the sanctioned
 * alternative — {@code auth.scheme: storage-state}, a session prepared once outside the SDK and reused —
 * rather than filling a form and then timing out on a screen it was never going to pass.
 *
 * <p>A team that has a sanctioned way through its own challenge implements this interface and registers it
 * in {@code META-INF/services/ru.alfa.stand.test.ui.UiLoginChallengeHandler} on the test classpath. The
 * handler is found by {@link java.util.ServiceLoader}, so it needs a public no-argument constructor. It
 * receives the browsing session and the identity of the account — never the credentials.
 *
 * <p>A handler must respect {@link UiLoginChallengeRequest#timeout()}: it runs inside the sign-in step's
 * budget, and every wait in this SDK is bounded.
 */
public interface UiLoginChallengeHandler {

    /**
     * Whether this handler can resolve the given kind of challenge.
     *
     * @param challenge the challenge the application declared
     * @return true if {@link #resolve(UiLoginChallengeRequest)} can be called with it
     */
    boolean supports(UiLoginChallenge challenge);

    /**
     * Gets past the challenge, leaving the browser on the signed-in application.
     *
     * <p>Failure semantics follow the rest of the adapter: an obstacle the handler could not pass because
     * the product behaved unexpectedly is a {@code StandTestAssertionError}; a missing token service, an
     * unreachable mailbox or any other infrastructure problem is a {@code StandTestException}.
     *
     * @param request the browsing session, the account identity and the remaining budget
     */
    void resolve(UiLoginChallengeRequest request);
}
