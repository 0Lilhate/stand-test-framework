package ru.alfa.stand.test.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.alfa.stand.test.core.exception.StandTestException;

/** Resolves file-only value placeholders while leaving lazy reference fields untouched. */
final class FilePlaceholders {

    private static final Pattern VALUE = Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]{2,63}):([^}]*)}$");

    private FilePlaceholders() {
    }

    static String resolve(String value, String location) {
        Matcher matcher = VALUE.matcher(value);
        if (matcher.matches()) {
            String environmentValue = System.getenv(matcher.group(1));
            return environmentValue == null ? matcher.group(2) : environmentValue;
        }
        if (value.startsWith("${")) {
            throw new StandTestException("Value at " + location + " must use ${VAR:default}; a reference without a default must use ref");
        }
        return value;
    }

    static Map<String, Object> resolveFields(Map<String, Object> fields, String location) {
        Map<String, Object> result = new LinkedHashMap<>();
        fields.forEach((key, value) -> result.put(key, resolveValue(value, key, location + "." + key)));
        return result;
    }

    private static Object resolveValue(Object value, String key, String location) {
        if ("ref".equals(key) || key.endsWith("-ref") || key.endsWith("Ref")) {
            return value;
        }
        if (value instanceof String text) {
            return resolve(text, location);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> {
                String name = String.valueOf(nestedKey);
                copy.put(name, resolveValue(nestedValue, name, location + "." + name));
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> resolveValue(item, key, location)).toList();
        }
        return value;
    }
}
