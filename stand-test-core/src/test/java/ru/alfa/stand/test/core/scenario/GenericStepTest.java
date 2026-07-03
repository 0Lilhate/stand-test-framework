package ru.alfa.stand.test.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GenericStepTest {

    @Test
    @DisplayName("nested parameter collections are deeply copied — mutating the caller's originals never changes a built step")
    void parametersAreDeeplyCopied() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        Map<String, Object> assertion = new HashMap<>();
        assertion.put(StepParameterKeys.JSON_PATH, "$.status");
        assertion.put(StepParameterKeys.EXPECTED_VALUE, "OK");
        List<Map<String, Object>> assertions = new ArrayList<>();
        assertions.add(assertion);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(StepParameterKeys.HEADERS, headers);
        parameters.put(StepParameterKeys.ASSERTIONS, assertions);

        GenericStep step = new GenericStep("s1", "rest.get", "", parameters);

        headers.put("Authorization", "smuggled");
        assertion.put(StepParameterKeys.EXPECTED_VALUE, "TAMPERED");
        assertions.clear();
        parameters.clear();

        Map<?, ?> stepHeaders = (Map<?, ?>) step.parameters().get(StepParameterKeys.HEADERS);
        assertThat(stepHeaders).hasSize(1);
        assertThat(stepHeaders.get("Accept")).isEqualTo("application/json");
        List<?> stepAssertions = (List<?>) step.parameters().get(StepParameterKeys.ASSERTIONS);
        assertThat(stepAssertions).hasSize(1);
        assertThat(((Map<?, ?>) stepAssertions.get(0)).get(StepParameterKeys.EXPECTED_VALUE)).isEqualTo("OK");
    }

    @Test
    @DisplayName("nested parameter collections are exposed as immutable")
    void nestedCollectionsAreImmutable() {
        GenericStep step = new GenericStep("s1", "rest.get", "",
                Map.of(StepParameterKeys.HEADERS, new HashMap<>(Map.of("Accept", "application/json"))));

        Map<?, ?> stepHeaders = (Map<?, ?>) step.parameters().get(StepParameterKeys.HEADERS);

        assertThatThrownBy(() -> stepHeaders.remove("Accept")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a null value nested inside a parameter collection is rejected")
    void nestedNullValueRejected() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", null);

        assertThatThrownBy(() -> new GenericStep("s1", "rest.get", "", Map.of(StepParameterKeys.HEADERS, headers)))
                .isInstanceOf(NullPointerException.class);
    }
}
