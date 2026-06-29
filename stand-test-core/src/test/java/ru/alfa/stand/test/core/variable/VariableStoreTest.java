package ru.alfa.stand.test.core.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class VariableStoreTest {

    @Test
    @DisplayName("put then get returns the stored value")
    void putThenGet_returnsValue() {
        VariableStore store = new VariableStore();
        store.put("requestId", "42");

        assertThat(store.get("requestId")).contains("42");
        assertThat(store.contains("requestId")).isTrue();
    }

    @Test
    @DisplayName("get returns empty for an unknown variable")
    void get_unknown_returnsEmpty() {
        VariableStore store = new VariableStore();

        assertThat(store.get("missing")).isEmpty();
        assertThat(store.contains("missing")).isFalse();
    }

    @Test
    @DisplayName("getRequired returns the value or fails with a clear SDK error")
    void getRequired_behaviour() {
        VariableStore store = new VariableStore();
        store.put("requestId", 7);

        assertThat(store.getRequired("requestId")).isEqualTo(7);
        assertThatThrownBy(() -> store.getRequired("missing"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("blank names and null values are rejected")
    void blankNameAndNullValue_areRejected() {
        VariableStore store = new VariableStore();

        assertThatThrownBy(() -> store.put("  ", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("name", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.get(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("put replaces any previous value under the same name")
    void put_replacesPreviousValue() {
        VariableStore store = new VariableStore();
        store.put("k", "1");
        store.put("k", "2");

        assertThat(store.get("k")).contains("2");
        assertThat(store.getRequired("k")).isEqualTo("2");
    }

    @Test
    @DisplayName("asMap returns an immutable snapshot decoupled from later writes")
    void asMap_isImmutableSnapshot() {
        VariableStore store = new VariableStore();
        store.put("a", "1");

        var snapshot = store.asMap();
        store.put("b", "2");

        assertThat(snapshot).containsExactly(org.assertj.core.api.Assertions.entry("a", "1"));
        assertThatThrownBy(() -> snapshot.put("c", "3")).isInstanceOf(UnsupportedOperationException.class);
    }
}
