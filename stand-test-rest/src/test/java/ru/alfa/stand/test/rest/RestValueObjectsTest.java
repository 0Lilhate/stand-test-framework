package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestValueObjectsTest {

    @Test
    @DisplayName("RestAssertion rejects a null expected value; RestCapture rejects a blank name")
    void valueObjectValidation() {
        assertThatThrownBy(() -> new RestAssertion("$.x", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RestCapture(" ", "$.x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RestMethod maps to its rest.<method> step type")
    void restMethodStepType() {
        assertThat(RestMethod.GET.stepType()).isEqualTo("rest.get");
        assertThat(RestMethod.POST.stepType()).isEqualTo("rest.post");
        assertThat(RestMethod.PUT.stepType()).isEqualTo("rest.put");
        assertThat(RestMethod.DELETE.stepType()).isEqualTo("rest.delete");
    }
}
