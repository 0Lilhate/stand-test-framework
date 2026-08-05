package ru.alfa.stand.test.ui;

import java.util.function.Supplier;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The single point where the browser's exceptions become the SDK's two kinds of failure.
 *
 * <p>An element that never became actionable is an unmet expectation about the product and is re-raised as
 * an assertion failure; anything else the driver throws is infrastructure and stays a
 * {@link StandTestException}. Keeping the rule in one place is what stops the sign-in step and the ordinary
 * steps from classifying the same browser error differently — an infrastructure failure recorded as a
 * product failure poisons the flaky-rate measurement, which is why this is a contract rather than a detail.
 */
final class UiDriverCalls {

    private UiDriverCalls() {
    }

    static void run(Runnable call, String what) {
        call(() -> {
            call.run();
            return null;
        }, what);
    }

    static <T> T call(Supplier<T> call, String what) {
        try {
            return call.get();
        } catch (UiElementNotActionableException notActionable) {
            throw new StandTestAssertionError("Could not " + what + ": " + notActionable.getMessage(), notActionable);
        } catch (StandTestAssertionError alreadyClassified) {
            throw alreadyClassified;
        } catch (StandTestException infrastructure) {
            throw infrastructure;
        } catch (RuntimeException unexpected) {
            throw new StandTestException("The UI driver failed to " + what + ": " + unexpected, unexpected);
        }
    }
}
