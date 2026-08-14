package ru.alfa.stand.test.ui;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The last line between a credential and a report.
 *
 * <p>The SDK never puts a login or a password into a message it writes — that much is a matter of writing
 * the messages carefully. What it cannot control is what somebody <em>else</em> writes: a browser
 * automation library that echoes the value it was asked to type, an HTTP layer that quotes a request body,
 * a stub driver in a consumer's test. Any of those can hand back an exception whose message carries the
 * secret, and re-throwing it as a cause publishes the secret to the log, the report and CI output.
 *
 * <p>So the call that types a credential runs through {@link #guard}: if any secret is found anywhere in
 * the thrown exception's message chain, the exception is <strong>not</strong> propagated and not attached
 * as a cause. A sanitised {@link StandTestException} is raised instead, saying what was being done, what
 * type failed, and that the original message was withheld because it contained credential material. The
 * stack trace of a withheld cause is lost, and that is the accepted trade: a lost stack trace costs one
 * debugging session, a leaked password costs a rotation.
 *
 * <p>When no secret is present the original exception passes through untouched, so the ordinary failure
 * path keeps its full diagnostics.
 */
final class UiSecrets {

    private UiSecrets() {
    }

    /**
     * Runs a call that handles credentials, refusing to propagate an exception that carries one.
     *
     * @param call what to run
     * @param secrets the values that must not escape
     * @param what a description of the action, for the sanitised message
     */
    static void guard(Runnable call, Collection<String> secrets, String what) {
        guard(() -> {
            call.run();
            return null;
        }, secrets, what);
    }

    /**
     * Runs a call that handles credentials, refusing to propagate an exception that carries one.
     *
     * @param call what to run
     * @param secrets the values that must not escape
     * @param what a description of the action, for the sanitised message
     * @param <T> the result type
     * @return the call's result
     */
    static <T> T guard(Supplier<T> call, Collection<String> secrets, String what) {
        try {
            return call.get();
        } catch (RuntimeException | AssertionError failure) {
            if (!leaks(failure, secrets)) {
                throw failure;
            }
            String sanitised = "Could not " + what + ": the browser driver failed with " + failure.getClass().getName()
                    + ", and its message is withheld because it contained the credential value."
                    + " The account's login or password is echoed by the driver; check the driver, not this message.";
            if (failure instanceof AssertionError) {
                throw new StandTestAssertionError(sanitised);
            }
            throw new StandTestException(sanitised);
        }
    }

    /**
     * Whether any of the secrets appears in the throwable's message chain.
     *
     * @param failure the throwable to inspect (may be null)
     * @param secrets the values that must not appear
     * @return true when at least one secret is present
     */
    static boolean leaks(Throwable failure, Collection<String> secrets) {
        if (failure == null || secrets == null || secrets.isEmpty()) {
            return false;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (carries(current.getMessage(), secrets) || carries(current.toString(), secrets)) {
                return true;
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return false;
    }

    private static boolean carries(String text, Collection<String> secrets) {
        if (text == null) {
            return false;
        }
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank() && text.contains(secret)) {
                return true;
            }
        }
        return false;
    }
}
