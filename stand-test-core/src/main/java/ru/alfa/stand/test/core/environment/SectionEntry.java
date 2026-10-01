package ru.alfa.stand.test.core.environment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An opaque, deeply immutable entry in a named environment section. */
public record SectionEntry(String alias, Map<String, Object> fields) {

    public SectionEntry {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("section entry alias must not be blank");
        }
        fields = immutableMap(fields == null ? Map.of() : fields);
    }

    private static Map<String, Object> immutableMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("section field name must be a non-blank string");
            }
            copy.put(name, immutableValue(value));
        });
        return Map.copyOf(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map);
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list.stream().map(SectionEntry::immutableValue).toList());
        }
        if (value == null) {
            throw new IllegalArgumentException("section fields must not contain null");
        }
        return value;
    }
}
