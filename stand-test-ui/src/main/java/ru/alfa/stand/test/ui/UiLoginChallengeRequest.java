package ru.alfa.stand.test.ui;

import java.time.Duration;
import java.util.Objects;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;

/**
 * What a {@link UiLoginChallengeHandler} is given when the sign-in form has been submitted and the
 * application's declared challenge stands between the run and being signed in.
 *
 * <p>It carries the browser and the identity of the account, and deliberately <strong>no credentials</strong>:
 * a handler that fetches a one-time password from a test mailbox, or clicks a confirmation in a stub, has
 * no business seeing the password, and not passing it is cheaper than trusting every handler not to log it.
 *
 * @param challenge the challenge the application declared
 * @param application the UI application alias
 * @param role the role of the account signing in (never null)
 * @param accountId the account signing in — what a handler needs to look up the right mailbox or token
 * @param driver the browsing session, positioned on whatever screen the challenge presented
 * @param timeout what is left of the sign-in step's budget; a handler must not exceed it
 */
public record UiLoginChallengeRequest(
        UiLoginChallenge challenge,
        String application,
        String role,
        String accountId,
        UiDriver driver,
        Duration timeout) {

    /**
     * Validates the request.
     */
    public UiLoginChallengeRequest {
        Objects.requireNonNull(challenge, "challenge must not be null");
        Objects.requireNonNull(application, "application must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the challenge timeout must be strictly positive");
        }
    }
}
