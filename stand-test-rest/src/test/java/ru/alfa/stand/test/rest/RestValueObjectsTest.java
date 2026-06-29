package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestValueObjectsTest {

    @Test
    @DisplayName("RestResponse exposes the first value of a header and a default empty body")
    void restResponseAccessors() {
        RestResponse response = new RestResponse(200, Map.of("X-A", List.of("1", "2")), "body");
        assertThat(response.header("X-A")).contains("1");
        assertThat(response.header("x-a")).contains("1");
        assertThat(response.header("missing")).isEmpty();
        assertThat(new RestResponse(204, Map.of(), null).body()).isEmpty();
    }

    @Test
    @DisplayName("RestRequest rejects a blank method and a blank base URL")
    void restRequestValidation() {
        assertThatThrownBy(() -> new RestRequest(" ", "http://x", "/p", Map.of(), Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestRequest("GET", " ", "/p", Map.of(), Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

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
