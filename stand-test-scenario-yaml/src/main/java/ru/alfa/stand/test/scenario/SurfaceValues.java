package ru.alfa.stand.test.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Small helpers that read and coerce the loaded YAML tree ({@code Map}/{@code List}/scalar) into the
 * exact Java types the adapter executors expect, raising {@link StandTestException} with a location on any
 * malformed input. All failures are config-class (plan §8.3) and fail-closed: unknown or ill-typed input
 * is rejected, never silently ignored.
 */
final class SurfaceValues {

    private SurfaceValues() {
    }

    static Map<String, Object> asMap(Object value, String location) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new StandTestException("Expected a mapping at " + location + ", but found "
                    + (value == null ? "nothing" : value.getClass().getSimpleName()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    static List<Object> asList(Object value, String location) {
        if (!(value instanceof List<?> list)) {
            throw new StandTestException("Expected a list at " + location + ", but found "
                    + (value == null ? "nothing" : value.getClass().getSimpleName()));
        }
        return new ArrayList<>(list);
    }

    static void checkKnownKeys(Map<String, Object> fields, Set<String> known, String location) {
        for (String key : fields.keySet()) {
            if (!known.contains(key)) {
                throw new StandTestException("Unknown field '" + key + "' at " + location + " (allowed: " + known + ")");
            }
        }
    }

    static String requireString(Map<String, Object> fields, String key, String location) {
        Object value = fields.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("Field '" + key + "' at " + location + " must be a non-blank string");
        }
        return text;
    }

    static String optionalString(Map<String, Object> fields, String key, String location) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new StandTestException("Field '" + key + "' at " + location + " must be a string");
        }
        return text;
    }

    static boolean boolFlag(Map<String, Object> fields, String key, String location) {
        Object value = fields.get(key);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw new StandTestException("Field '" + key + "' at " + location + " must be a boolean");
        }
        return flag;
    }

    static Integer requireInteger(Map<String, Object> fields, String key, String location) {
        Object value = fields.get(key);
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException notANumber) {
                throw new StandTestException("Field '" + key + "' at " + location + " must be an integer, but was '" + text + "'");
            }
        }
        throw new StandTestException("Field '" + key + "' at " + location + " must be an integer");
    }

    static Map<String, String> stringMap(Object value, String location) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> raw = asMap(value, location);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getValue() == null) {
                throw new StandTestException("Value for '" + entry.getKey() + "' at " + location + " must not be null");
            }
            result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    static Map<String, Object> objectMap(Object value, String location) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> raw = asMap(value, location);
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getValue() == null) {
                throw new StandTestException("Value for '" + entry.getKey() + "' at " + location + " must not be null");
            }
        }
        return Map.copyOf(raw);
    }

    static Long durationMillis(Object value, String location) {
        long millis;
        if (value instanceof Number number) {
            if (!(number instanceof Integer) && !(number instanceof Long)) {
                throw new StandTestException("Duration at " + location + " must be a whole number of milliseconds, not a floating-point value: " + value);
            }
            millis = number.longValue();
        } else if (value instanceof String text) {
            millis = parseDuration(text.trim(), location);
        } else {
            throw new StandTestException("Duration at " + location + " must be a number of ms or '<n>s'/'<n>ms'");
        }
        if (millis <= 0) {
            throw new StandTestException("Duration at " + location + " must be positive, but was " + millis + "ms");
        }
        return millis;
    }

    private static long parseDuration(String text, String location) {
        try {
            if (text.endsWith("ms")) {
                return Long.parseLong(text.substring(0, text.length() - 2).trim());
            }
            if (text.endsWith("s")) {
                return Long.parseLong(text.substring(0, text.length() - 1).trim()) * 1000L;
            }
            return Long.parseLong(text);
        } catch (NumberFormatException notANumber) {
            throw new StandTestException("Invalid duration '" + text + "' at " + location + " (use '<n>s', '<n>ms' or a number of ms)");
        }
    }

    static List<Map<String, Object>> assertions(Object value, String location) {
        if (!(value instanceof Map<?, ?>)) {
            throw new StandTestException("'assert' at " + location + " must be a mapping of {\"jsonPath\": expectedValue}, but found "
                    + (value == null ? "nothing" : value.getClass().getSimpleName()));
        }
        Map<String, Object> raw = asMap(value, location);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getValue() == null) {
                throw new StandTestException("Assertion for '" + entry.getKey() + "' at " + location + " must have a non-null expected value");
            }
            result.add(Map.of(YamlStepKeys.JSON_PATH, entry.getKey(), YamlStepKeys.EXPECTED_VALUE, entry.getValue()));
        }
        return List.copyOf(result);
    }

    static List<Map<String, Object>> captures(Object value, String valueKey, String location) {
        if (!(value instanceof Map<?, ?>)) {
            throw new StandTestException("'capture' at " + location + " must be a mapping of {variableName: " + valueKey + "}, but found "
                    + (value == null ? "nothing" : value.getClass().getSimpleName()));
        }
        Map<String, Object> raw = asMap(value, location);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof String selector) || selector.isBlank()) {
                throw new StandTestException("Capture '" + entry.getKey() + "' at " + location + " must map to a non-blank " + valueKey);
            }
            result.add(Map.of(YamlStepKeys.VARIABLE_NAME, entry.getKey(), valueKey, selector));
        }
        return List.copyOf(result);
    }

    /**
     * Writes an inline value (under {@code inlineKey}) or a classpath-resource reference (under
     * {@code resourceKey}) from the two mutually exclusive surface fields, e.g. {@code body}/{@code bodyResource}
     * or {@code sql}/{@code sqlResource}.
     */
    static void putInlineOrResource(Map<String, Object> params, Map<String, Object> fields,
            String inlineField, String resourceField, String inlineKey, String resourceKey,
            boolean required, String location) {
        String inline = optionalString(fields, inlineField, location);
        String resource = optionalString(fields, resourceField, location);
        if (inline != null && resource != null) {
            throw new StandTestException("Specify only one of '" + inlineField + "'/'" + resourceField + "' at " + location);
        }
        if (inline != null) {
            params.put(inlineKey, inline);
        } else if (resource != null) {
            params.put(resourceKey, resource);
        } else if (required) {
            throw new StandTestException("One of '" + inlineField + "'/'" + resourceField + "' is required at " + location);
        }
    }
}
