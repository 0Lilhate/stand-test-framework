package ru.alfa.stand.test.core.variable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Mutable store of runtime variables captured during a single scenario run.
 *
 * <p>One {@code VariableStore} belongs to exactly one run and is owned by the runner. It is
 * deliberately a plain instance — not static, not global and not thread-local — so that parallel runs
 * are isolated by construction. Variable names must not be blank and values must not be null.
 */
public final class VariableStore {

    private final Map<String, Object> variables = new LinkedHashMap<>();

    /**
     * Stores a value under the given name, replacing any previous value.
     *
     * @param name the non-blank variable name
     * @param value the non-null value
     */
    public void put(String name, Object value) {
        this.variables.put(requireValidName(name), Objects.requireNonNull(value, "variable value must not be null"));
    }

    /**
     * Returns the value bound to the given name, if any.
     *
     * @param name the non-blank variable name
     * @return the value, or empty if not present
     */
    public Optional<Object> get(String name) {
        return Optional.ofNullable(this.variables.get(requireValidName(name)));
    }

    /**
     * Returns the value bound to the given name or fails with a clear SDK error if absent.
     *
     * @param name the non-blank variable name
     * @return the bound value
     * @throws StandTestException if no value is bound to the name
     */
    public Object getRequired(String name) {
        String key = requireValidName(name);
        Object value = this.variables.get(key);
        if (value == null) {
            throw new StandTestException("Required variable not found: '" + key + "'");
        }
        return value;
    }

    /**
     * Returns whether a value is bound to the given name.
     *
     * @param name the non-blank variable name
     * @return true if a value is bound
     */
    public boolean contains(String name) {
        return this.variables.containsKey(requireValidName(name));
    }

    /**
     * Returns an immutable snapshot of the current variables.
     *
     * @return an immutable copy of the variable map
     */
    public Map<String, Object> asMap() {
        return Map.copyOf(this.variables);
    }

    private static String requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
        return name;
    }
}
