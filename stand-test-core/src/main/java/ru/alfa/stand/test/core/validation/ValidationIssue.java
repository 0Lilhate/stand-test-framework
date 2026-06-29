package ru.alfa.stand.test.core.validation;

import java.util.Objects;

/**
 * A single validation finding: a severity, a stable code and a human-readable message.
 *
 * @param severity the issue severity
 * @param code a stable, machine-readable code
 * @param message a human-readable message
 */
public record ValidationIssue(ValidationSeverity severity, String code, String message) {

    public ValidationIssue {
        Objects.requireNonNull(severity, "severity must not be null");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /**
     * Creates an error-severity issue.
     *
     * @param code the stable code
     * @param message the message
     * @return a new error issue
     */
    public static ValidationIssue error(String code, String message) {
        return new ValidationIssue(ValidationSeverity.ERROR, code, message);
    }

    /**
     * Creates a warning-severity issue.
     *
     * @param code the stable code
     * @param message the message
     * @return a new warning issue
     */
    public static ValidationIssue warning(String code, String message) {
        return new ValidationIssue(ValidationSeverity.WARNING, code, message);
    }
}
