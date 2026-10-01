package ru.alfa.stand.test.eq.config;

import java.util.Objects;
import java.util.List;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** A non-secret literal or a reference resolved only when its backend is selected. */
public record ConfiguredValue(Object literal, String reference) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public ConfiguredValue {
        if ((literal == null) == (reference == null)) {
            throw new IllegalArgumentException("Exactly one configured value form is required");
        }
    }

    public Object resolve(UnaryOperator<String> lookup) {
        Objects.requireNonNull(lookup, "lookup must not be null");
        if (reference == null) {
            return literal;
        }
        // The EQ gateway *-ref fields accept either a reference (a bare env-var NAME, or a
        // ${VAR}/{VAR:default} placeholder) or a value: on the Spring surface Spring collapses
        // ${VAR:default} before the SDK sees the section, so a URL or a unit arrives as text. A real
        // reference that resolves to null (an unset bare NAME) stays null and fails at the caller.
        Object resolved = SecretReferences.resolveOrLiteral(reference, lookup);
        if (resolved == null) {
            throw new StandTestException("EQ configuration reference '" + reference + "' is not set");
        }
        return resolved;
    }

    /** Resolves a scalar without exposing its value in a type-error message. */
    public String resolveString(UnaryOperator<String> lookup) {
        Object value = resolve(lookup);
        if (value instanceof String || value instanceof Number) {
            return value.toString();
        }
        throw new StandTestException("EQ configuration value must resolve to a scalar");
    }

    /** Resolves a list reference whose environment variable contains a JSON array. */
    public List<String> resolveStringList(UnaryOperator<String> lookup) {
        Object value = resolve(lookup);
        if (reference != null) {
            try {
                value = JSON.readValue((String) value, List.class);
            } catch (JacksonException failure) {
                throw new StandTestException("EQ configuration reference '" + reference
                        + "' must contain a JSON array");
            }
        }
        if (!(value instanceof List<?> items)) {
            throw new StandTestException("EQ configuration value must resolve to a list");
        }
        return items.stream().map(item -> {
            if (item instanceof String text && !text.isBlank()) {
                return text;
            }
            throw new StandTestException("EQ configuration list must contain non-blank strings");
        }).toList();
    }
}
