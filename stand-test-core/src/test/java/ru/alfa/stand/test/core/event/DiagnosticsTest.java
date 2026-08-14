package ru.alfa.stand.test.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiagnosticsTest {

    private static Map<String, Object> awaitShaped() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("await", "kafka.expect response-topic");
        ordered.put("timeout", "PT30S");
        ordered.put("pollInterval", "PT0.2S");
        ordered.put("attempts", 30);
        ordered.put("elapsed", "PT30S");
        ordered.put("lastValue", "PENDING");
        ordered.put("kafka.messagesSeen", 0);
        return ordered;
    }

    @Test
    @DisplayName("iteration order survives — this is the whole reason the helper exists instead of Map.copyOf")
    void preservesOrder() {
        assertThat(Diagnostics.immutable(awaitShaped()).keySet())
                .containsExactly("await", "timeout", "pollInterval", "attempts", "elapsed", "lastValue", "kafka.messagesSeen");
    }

    @Test
    @DisplayName("Map.copyOf really does scramble that order, so the helper is not cargo cult")
    void mapCopyOfWouldNotHavePreservedIt() {
        assertThat(Map.copyOf(awaitShaped()).keySet())
                .as("if this ever starts holding, the JDK changed and the helper's first reason is gone")
                .doesNotContainSequence("await", "timeout", "pollInterval", "attempts", "elapsed", "lastValue", "kafka.messagesSeen");
    }

    @Test
    @DisplayName("a null value is carried: a step reporting 'the last value was null' must not become a different failure")
    void carriesNullValues() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("lastValue", null);
        withNull.put("attempts", 3);

        assertThat(Diagnostics.immutable(withNull)).containsEntry("lastValue", null).containsEntry("attempts", 3);
        assertThatThrownBy(() -> Map.copyOf(withNull))
                .as("the second reason the helper exists")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the copy is defensive and unmodifiable")
    void isADefensiveUnmodifiableCopy() {
        Map<String, Object> source = awaitShaped();
        Map<String, Object> copied = Diagnostics.immutable(source);

        source.put("injected", "later");

        assertThat(copied).doesNotContainKey("injected");
        assertThatThrownBy(() -> copied.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null and empty are the same thing")
    void nullAndEmptyAreEmpty() {
        assertThat(Diagnostics.immutable(null)).isEmpty();
        assertThat(Diagnostics.immutable(Map.of())).isEmpty();
    }
}
