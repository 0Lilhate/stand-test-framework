package ru.alfa.stand.test.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;

/**
 * A {@link UiLoginChallengeHandler} on the <em>test</em> classpath — the one place in this repository where
 * one exists, and deliberately so.
 *
 * <p>The SDK ships no handler: whether a second factor may be relaxed for test accounts is external gate
 * G-1, not a library feature. But an extension point nobody has ever extended is a claim rather than a
 * mechanism — a misspelled service file, a handler that is found and never called, or a request carrying a
 * budget of zero would all pass unnoticed while the interface sat in the sources looking correct. This class
 * is registered in {@code src/test/resources/META-INF/services/…} so the real {@link java.util.ServiceLoader}
 * lookup in {@code UiStepExecutor} is exercised end to end.
 *
 * <p>It supports {@link UiLoginChallenge#CAPTCHA} and nothing else, which is what lets the two halves of the
 * contract be proven in one suite: a CAPTCHA application finds it, and the MFA application of
 * {@code UiLoginStepExecutorTest} still gets the refusal — now additionally proving that selection goes
 * through {@link #supports(UiLoginChallenge)} rather than "the first provider wins".
 *
 * <p>It gets past the challenge by clicking {@link #PASSED}, so a driver programmed to sign in on that
 * control — and <em>not</em> on the form's submit button — makes the handler's action the only thing that can
 * complete the sign-in.
 */
public final class RecordingChallengeHandler implements UiLoginChallengeHandler {

    /** What this handler clicks to get past the challenge. */
    static final UiLocator PASSED = UiLocator.testId("challenge-passed");

    private static final List<UiLoginChallengeRequest> REQUESTS = new CopyOnWriteArrayList<>();

    /**
     * Required by {@link java.util.ServiceLoader}, which instantiates providers reflectively.
     */
    public RecordingChallengeHandler() {
    }

    static List<UiLoginChallengeRequest> requests() {
        return List.copyOf(REQUESTS);
    }

    static void forget() {
        REQUESTS.clear();
    }

    @Override
    public boolean supports(UiLoginChallenge challenge) {
        return challenge == UiLoginChallenge.CAPTCHA;
    }

    @Override
    public void resolve(UiLoginChallengeRequest request) {
        REQUESTS.add(request);
        request.driver().click(PASSED, request.timeout());
    }
}
