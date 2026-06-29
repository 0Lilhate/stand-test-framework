package ru.alfa.stand.test.core.identifier;

/**
 * Stable identifier of a scenario. Immutable, value-based, never blank.
 *
 * @param value the non-blank identifier value
 */
public record ScenarioId(String value) {

    public ScenarioId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ScenarioId value must not be blank");
        }
    }

    /**
     * Creates a scenario id from the given value.
     *
     * @param value the non-blank identifier value
     * @return a new scenario id
     */
    public static ScenarioId of(String value) {
        return new ScenarioId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
