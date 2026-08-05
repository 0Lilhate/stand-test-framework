package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;

class UiSecretsTest {

    private static final List<String> SECRETS = List.of("portal.client.one", "s3cret-one-!");

    @Test
    @DisplayName("an exception that does not carry a secret passes through untouched, keeping its full diagnostics")
    void cleanFailuresPropagateUnchanged() {
        IllegalStateException original = new IllegalStateException("the element was not visible");

        assertThatThrownBy(() -> UiSecrets.guard(() -> {
            throw original;
        }, SECRETS, "fill the login field"))
                .isSameAs(original);
    }

    @Test
    @DisplayName("an exception whose message carries a credential is not propagated and not attached as a cause — a leaked password costs a rotation")
    void leakingFailuresAreReplaced() {
        RuntimeException echoing = new RuntimeException("Timeout filling input with value \"s3cret-one-!\"");

        assertThatThrownBy(() -> UiSecrets.guard(() -> {
            throw echoing;
        }, SECRETS, "fill the password field of application 'client-portal'"))
                .isInstanceOf(StandTestException.class)
                .hasMessageNotContaining("s3cret-one-!")
                .hasMessageContaining("fill the password field of application 'client-portal'")
                .hasMessageContaining("withheld")
                .hasMessageContaining(RuntimeException.class.getName())
                .hasNoCause();
    }

    @Test
    @DisplayName("a secret hidden deeper in the cause chain or in a suppressed exception is found just the same")
    void leaksAreFoundThroughTheWholeChain() {
        RuntimeException deep = new RuntimeException("outer", new IllegalStateException("inner: s3cret-one-!"));
        RuntimeException suppressing = new RuntimeException("outer");
        suppressing.addSuppressed(new IllegalStateException("suppressed: portal.client.one"));

        assertThat(UiSecrets.leaks(deep, SECRETS)).isTrue();
        assertThat(UiSecrets.leaks(suppressing, SECRETS)).isTrue();
        assertThat(UiSecrets.leaks(new RuntimeException("nothing sensitive"), SECRETS)).isFalse();
        assertThat(UiSecrets.leaks(null, SECRETS)).isFalse();
        assertThat(UiSecrets.leaks(new RuntimeException("s3cret-one-!"), List.of())).isFalse();
    }

    @Test
    @DisplayName("a cyclic cause chain terminates instead of looping — and the bound is enforced, so a regression fails the test rather than hanging the build")
    void cyclicChainsTerminate() throws InterruptedException {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.addSuppressed(second);
        second.addSuppressed(first);

        // Run on its own thread with a bound: without one, losing the visited-set guard would spin forever and
        // CI would report a killed job with no failing test named — the regression would read as flaky
        // infrastructure instead of as a broken leak check.
        AtomicBoolean leaked = new AtomicBoolean(true);
        Thread traversal = new Thread(() -> leaked.set(UiSecrets.leaks(first, SECRETS)), "leak-cycle-probe");
        traversal.setDaemon(true);
        traversal.start();
        traversal.join(5_000);

        assertThat(traversal.isAlive()).as("the traversal of a cyclic chain must terminate").isFalse();
        assertThat(leaked).isFalse();
    }

    @Test
    @DisplayName("an assertion failure carrying a credential is replaced too, and stays an assertion failure — withholding a message must not turn a failed test into a broken one")
    void leakingAssertionErrorsAreReplacedWithoutChangingTheirClassification() {
        StandTestAssertionError echoing = new StandTestAssertionError("could not type \"s3cret-one-!\" into the password field");

        assertThatThrownBy(() -> UiSecrets.guard(() -> {
            throw echoing;
        }, SECRETS, "fill the password field"))
                .isInstanceOf(StandTestAssertionError.class)
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("s3cret-one-!")
                .hasMessageContaining("withheld");
    }

    @Test
    @DisplayName("an assertion failure that carries no credential passes through untouched, cause and all")
    void cleanAssertionErrorsPropagateUnchanged() {
        StandTestAssertionError clean = new StandTestAssertionError("the element was not fillable within PT10S");

        assertThatThrownBy(() -> UiSecrets.guard(() -> {
            throw clean;
        }, SECRETS, "fill the password field"))
                .isSameAs(clean);
    }

    @Test
    @DisplayName("a blank secret never matches: an unset variable must not turn every message into a leak")
    void blankSecretsAreIgnored() {
        assertThat(UiSecrets.leaks(new RuntimeException("anything at all"), List.of("", "   "))).isFalse();
    }

    @Test
    @DisplayName("the credentials record masks itself, so careless string concatenation cannot print one")
    void credentialsMaskThemselves() {
        UiCredentials credentials = new UiCredentials("portal.client.one", "s3cret-one-!");

        assertThat(credentials.toString()).doesNotContain("portal.client.one", "s3cret-one-!").contains(UiCredentials.MASK);
        assertThat("" + credentials).doesNotContain("s3cret-one-!");
        assertThat(credentials.values()).containsExactly("portal.client.one", "s3cret-one-!");
        assertThat(new UiCredentials("user", null).values()).containsExactly("user");
    }
}
