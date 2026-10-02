package ru.alfa.stand.test.eq.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

/** Strict field reader shared by EQ backend configuration parsers. */
final class EqConfigReader {

    private final String location;

    EqConfigReader(String location) {
        this.location = location;
    }

    StandTestException invalid(String field, String reason) {
        return new StandTestException("Invalid " + location + ", field '" + field + "': " + reason);
    }

    void keys(Map<String, Object> fields, Set<String> allowed, String prefix) {
        for (String key : fields.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(prefix + key, "unknown field");
            }
        }
    }

    Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalid(field, "must be an object");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String name) || name.isBlank()) {
                throw invalid(field, "object keys must be non-blank strings");
            }
            copy.put(name, item);
        });
        return Map.copyOf(copy);
    }

    Map<String, Object> optionalMap(Map<String, Object> parent, String field, Set<String> allowed) {
        Object value = parent.get(field);
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> parsed = map(value, field);
        keys(parsed, allowed, field + '.');
        return parsed;
    }

    String string(Map<String, Object> fields, String field) {
        return stringValue(fields.get(field), field);
    }

    String stringValue(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(field, "must be a non-blank string");
        }
        SecretReferences.rejectLiteralMarkerInValue(text, field, location);
        return text;
    }

    String optionalString(Map<String, Object> fields, String field) {
        return fields.containsKey(field) ? string(fields, field) : null;
    }

    String alias(Map<String, Object> fields, String field) {
        String value = string(fields, field);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw invalid(field, "must be a logical alias");
        }
        return value;
    }

    String path(Map<String, Object> fields, String field) {
        String value = string(fields, field);
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("://")) {
            throw invalid(field, "must be an absolute path, not a URL");
        }
        return value;
    }

    String reference(Map<String, Object> fields, String field) {
        String value = string(fields, field);
        // The EQ gateway *-ref fields (base-url-ref, unit-phase.system/username/password-ref) accept
        // either a reference NAME/placeholder or a value. On the Spring surface Spring collapses
        // ${VAR:default} before the SDK sees the section, so a URL or a system name can arrive here;
        // rejecting it as "looks like a value" would break the documented ${VAR:default} ergonomics.
        // The internal literal:// marker is still refused fail-closed.
        return SecretReferences.requireReferenceOrLiteral(value, field, location);
    }

    ConfiguredValue scalarValue(Object value, String field) {
        ConfiguredValue parsed = value(value, field);
        if (parsed.literal() instanceof List<?>) {
            throw invalid(field, "must be a scalar or {ref: NAME}");
        }
        return parsed;
    }

    ConfiguredValue listValue(Object value, String field) {
        ConfiguredValue parsed = value(value, field);
        if (parsed.literal() != null && !(parsed.literal() instanceof List<?>)) {
            throw invalid(field, "must be a list or {ref: NAME}");
        }
        if (parsed.literal() instanceof List<?> items) {
            stringList(items, field);
        }
        return parsed;
    }

    private ConfiguredValue value(Object value, String field) {
        if (value == null) {
            throw invalid(field, "is required");
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> refObject = map(value, field);
            keys(refObject, Set.of("ref"), field + '.');
            if (refObject.size() != 1) {
                throw invalid(field, "reference object must contain only ref");
            }
            return new ConfiguredValue(null, reference(refObject, "ref"));
        }
        if (value instanceof String || value instanceof Number || value instanceof List<?>) {
            if (value instanceof String text) {
                SecretReferences.rejectLiteralMarkerInValue(text, field, location);
            }
            return new ConfiguredValue(value, null);
        }
        throw invalid(field, "must be a string, number, list, or {ref: NAME}");
    }

    boolean bool(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        if (value instanceof Boolean result) {
            return result;
        }
        if (value instanceof String text && ("true".equals(text) || "false".equals(text))) {
            return Boolean.parseBoolean(text);
        }
        throw invalid(field, "must be a boolean");
    }

    int integer(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw invalid(field, "must be an integer");
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException failure) {
            throw invalid(field, "must be an integer");
        }
    }

    BigDecimal optionalDecimal(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw invalid(field, "must be a decimal number");
        }
        try {
            BigDecimal number = new BigDecimal(value.toString());
            if (number.signum() < 0) {
                throw invalid(field, "must not be negative");
            }
            return number;
        } catch (NumberFormatException failure) {
            throw invalid(field, "must be a decimal number");
        }
    }

    List<String> stringList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw invalid(field, "must be a list");
        }
        return list.stream().map(item -> stringValue(item, field)).toList();
    }

    Duration duration(Map<String, Object> fields, String field, Duration defaultValue) {
        Object value = fields.get(field);
        if (value == null) {
            return defaultValue;
        }
        String raw = stringValue(value, field);
        try {
            Duration parsed;
            if (raw.matches("[1-9][0-9]*ms")) {
                parsed = Duration.ofMillis(Long.parseLong(raw.substring(0, raw.length() - 2)));
            } else if (raw.matches("[1-9][0-9]*s")) {
                parsed = Duration.ofSeconds(Long.parseLong(raw.substring(0, raw.length() - 1)));
            } else if (raw.matches("[1-9][0-9]*m")) {
                parsed = Duration.ofMinutes(Long.parseLong(raw.substring(0, raw.length() - 1)));
            } else {
                parsed = Duration.parse(raw);
            }
            if (parsed.isZero() || parsed.isNegative()
                    || parsed.toMillis() > DefaultScenarioValidator.MAX_TIMEOUT_MILLIS) {
                throw invalid(field, "must be within the SDK timeout bound");
            }
            return parsed;
        } catch (ArithmeticException | NumberFormatException | DateTimeParseException failure) {
            throw invalid(field, "must be a positive duration such as 10s or 5m");
        }
    }
}
