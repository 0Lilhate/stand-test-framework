package ru.alfa.stand.test.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExceptionHierarchyTest {

    @Test
    @DisplayName("StandTestException is an unchecked runtime exception carrying message and cause")
    void standTestException_isRuntimeException() {
        Throwable cause = new IllegalStateException("root");
        StandTestException withCause = new StandTestException("boom", cause);

        assertThat(new StandTestException("boom")).isInstanceOf(RuntimeException.class).hasMessage("boom");
        assertThat(withCause).hasMessage("boom").hasCause(cause);
    }

    @Test
    @DisplayName("StandTestAssertionError extends AssertionError so JUnit treats it as a failure")
    void standTestAssertionError_isAssertionError() {
        Throwable cause = new IllegalStateException("root");
        StandTestAssertionError withCause = new StandTestAssertionError("expected SUCCESS", cause);

        assertThat(new StandTestAssertionError("expected SUCCESS"))
                .isInstanceOf(AssertionError.class)
                .hasMessage("expected SUCCESS");
        assertThat(withCause).hasCause(cause);
    }
}
