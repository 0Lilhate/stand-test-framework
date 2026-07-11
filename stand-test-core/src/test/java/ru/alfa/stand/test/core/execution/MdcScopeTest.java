package ru.alfa.stand.test.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcScopeTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("of() applies the keys for the scope and removes them on close when none existed before")
    void applies_andRemovesWhenAbsentBefore() {
        try (MdcScope scope = MdcScope.of(Map.of(MdcScope.SCENARIO_ID, "flow", MdcScope.STEP_ID, "s1"))) {
            assertThat(MDC.get(MdcScope.SCENARIO_ID)).isEqualTo("flow");
            assertThat(MDC.get(MdcScope.STEP_ID)).isEqualTo("s1");
        }
        assertThat(MDC.get(MdcScope.SCENARIO_ID)).isNull();
        assertThat(MDC.get(MdcScope.STEP_ID)).isNull();
    }

    @Test
    @DisplayName("close() restores a pre-existing value instead of clearing it")
    void restoresPreviousValue() {
        MDC.put(MdcScope.SCENARIO_ID, "outer");
        try (MdcScope scope = MdcScope.of(Map.of(MdcScope.SCENARIO_ID, "inner"))) {
            assertThat(MDC.get(MdcScope.SCENARIO_ID)).isEqualTo("inner");
        }
        assertThat(MDC.get(MdcScope.SCENARIO_ID)).isEqualTo("outer");
    }

    @Test
    @DisplayName("nested scopes compose and unwind in order")
    void nestedScopesCompose() {
        try (MdcScope outer = MdcScope.of(Map.of(MdcScope.SCENARIO_ID, "flow"))) {
            try (MdcScope inner = MdcScope.of(Map.of(MdcScope.STEP_ID, "s1", MdcScope.STEP_INDEX, "1"))) {
                assertThat(MDC.get(MdcScope.SCENARIO_ID)).isEqualTo("flow");
                assertThat(MDC.get(MdcScope.STEP_ID)).isEqualTo("s1");
                assertThat(MDC.get(MdcScope.STEP_INDEX)).isEqualTo("1");
            }
            assertThat(MDC.get(MdcScope.STEP_ID)).isNull();
            assertThat(MDC.get(MdcScope.SCENARIO_ID)).isEqualTo("flow");
        }
        assertThat(MDC.get(MdcScope.SCENARIO_ID)).isNull();
    }

    @Test
    @DisplayName("a null value removes the key for the scope and restores the previous value afterwards")
    void nullValueRemovesKeyForScope() {
        MDC.put(MdcScope.STEP_ID, "outer");
        Map<String, String> values = new HashMap<>();
        values.put(MdcScope.STEP_ID, null);
        try (MdcScope scope = MdcScope.of(values)) {
            assertThat(MDC.get(MdcScope.STEP_ID)).isNull();
        }
        assertThat(MDC.get(MdcScope.STEP_ID)).isEqualTo("outer");
    }

    @Test
    @DisplayName("of() rejects a null map")
    void rejectsNullMap() {
        assertThatThrownBy(() -> MdcScope.of(null)).isInstanceOf(NullPointerException.class);
    }
}
