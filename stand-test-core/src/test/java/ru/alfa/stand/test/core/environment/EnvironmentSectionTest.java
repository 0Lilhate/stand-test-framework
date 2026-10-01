package ru.alfa.stand.test.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnvironmentSectionTest {

    @Test
    @DisplayName("BR-45: extension fields are deeply immutable and addressed only by declared aliases")
    void sectionCopiesNestedValues() {
        List<Object> offices = new ArrayList<>(List.of("7701"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inn-tax-offices", offices);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("defaults", nested);
        SectionEntry entry = new SectionEntry("eq", fields);
        EnvironmentSection section = new EnvironmentSection("eq-backends", Map.of("eq", entry));

        offices.add("7702");
        nested.put("other", "changed");
        fields.clear();

        assertThat(section.entry("eq")).contains(entry);
        assertThat(section.entry("unknown")).isEmpty();
        assertThatThrownBy(() -> entry.fields().put("kind", "gateway"))
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) entry.fields().get("defaults");
        @SuppressWarnings("unchecked")
        List<Object> copiedOffices = (List<Object>) copy.get("inn-tax-offices");
        assertThat(copiedOffices).containsExactly("7701");
        assertThatThrownBy(() -> copiedOffices.add("7703")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("BR-04: a registry refuses an undeclared default at construction")
    void defaultEnvironmentMustExist() {
        assertThatThrownBy(() -> new InMemoryEnvironmentRegistry(Map.of(), "ift"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ift");
    }
}
