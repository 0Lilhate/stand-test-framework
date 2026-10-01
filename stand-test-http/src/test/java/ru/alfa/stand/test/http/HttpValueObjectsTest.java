package ru.alfa.stand.test.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpValueObjectsTest {

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
        assertThatThrownBy(() -> new RestRequest(" ", "http://x", "/p", Map.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestRequest("GET", " ", "/p", Map.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RestRequest.toString never carries header or query values — only their names")
    void restRequestToString_redactsValues() {
        RestRequest request = new RestRequest(
                "POST", "http://x", "/p",
                Map.of("clientCode", "pin-1"),
                Map.of("Authorization", "Basic c2VjcmV0"),
                "{\"token\":\"body-secret\"}");

        String rendered = request.toString();

        assertThat(rendered)
                .contains("POST")
                .contains("/p")
                .contains("Authorization")
                .contains("clientCode")
                .doesNotContain("Basic c2VjcmV0")
                .doesNotContain("pin-1")
                .doesNotContain("body-secret");
    }
}
