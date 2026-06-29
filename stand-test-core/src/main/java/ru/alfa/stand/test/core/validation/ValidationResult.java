package ru.alfa.stand.test.core.validation;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Immutable outcome of validating a scenario: a list of {@link ValidationIssue}s.
 *
 * <p>The result is considered valid when it contains no {@link ValidationSeverity#ERROR} issue. The
 * issue list is defensively copied and exposed as immutable.
 */
public final class ValidationResult {

    private final List<ValidationIssue> issues;

    private ValidationResult(List<ValidationIssue> issues) {
        this.issues = List.copyOf(issues);
    }

    /**
     * Returns a valid result with no issues.
     *
     * @return a valid result
     */
    public static ValidationResult valid() {
        return new ValidationResult(List.of());
    }

    /**
     * Returns a result wrapping the given issues.
     *
     * @param issues the issues
     * @return a new result
     */
    public static ValidationResult of(List<ValidationIssue> issues) {
        Objects.requireNonNull(issues, "issues must not be null");
        return new ValidationResult(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }

    /**
     * Returns only the error-severity issues.
     *
     * @return the immutable list of error issues
     */
    public List<ValidationIssue> errors() {
        return issues.stream().filter(issue -> issue.severity() == ValidationSeverity.ERROR).toList();
    }

    /**
     * Returns whether there is at least one error-severity issue.
     *
     * @return true if any error issue is present
     */
    public boolean hasErrors() {
        return !errors().isEmpty();
    }

    /**
     * Returns whether the result is valid (no error-severity issues).
     *
     * @return true if valid
     */
    public boolean isValid() {
        return !hasErrors();
    }

    /**
     * Throws a {@link StandTestException} listing all errors if the result is invalid.
     *
     * @throws StandTestException if any error-severity issue is present
     */
    public void throwIfInvalid() {
        if (hasErrors()) {
            String detail = errors().stream()
                    .map(issue -> issue.code() + ": " + issue.message())
                    .collect(Collectors.joining("; "));
            throw new StandTestException("Scenario validation failed: " + detail);
        }
    }

    @Override
    public String toString() {
        return "ValidationResult{valid=" + isValid() + ", issues=" + issues + "}";
    }
}
