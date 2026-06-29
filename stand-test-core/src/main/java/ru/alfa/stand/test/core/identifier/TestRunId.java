package ru.alfa.stand.test.core.identifier;

import java.util.UUID;

/**
 * Unique identifier of a single scenario run. Immutable, value-based, never blank.
 *
 * @param value the non-blank identifier value
 */
public record TestRunId(String value) {

    public TestRunId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TestRunId value must not be blank");
        }
    }

    /**
     * Creates a test-run id from the given value.
     *
     * @param value the non-blank identifier value
     * @return a new test-run id
     */
    public static TestRunId of(String value) {
        return new TestRunId(value);
    }

    /**
     * Generates a new unique test-run id.
     *
     * @return a freshly generated test-run id
     */
    public static TestRunId generate() {
        return new TestRunId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
