package ru.alfa.stand.test.core.result;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StepStatusTest {

    @Test
    @DisplayName("isFailure is true for FAILED, BROKEN and TIMEOUT, false for SUCCESS and SKIPPED")
    void isFailure_coversEveryStatus() {
        assertThat(StepStatus.SUCCESS.isFailure()).isFalse();
        assertThat(StepStatus.SKIPPED.isFailure()).isFalse();
        assertThat(StepStatus.FAILED.isFailure()).isTrue();
        assertThat(StepStatus.BROKEN.isFailure()).isTrue();
        assertThat(StepStatus.TIMEOUT.isFailure()).isTrue();
    }
}
