package ru.alfa.stand.test.core.validation;

/**
 * Severity of a {@link ValidationIssue}.
 */
public enum ValidationSeverity {

    /** A blocking problem: the scenario must not run. */
    ERROR,

    /** A non-blocking concern worth surfacing. */
    WARNING
}
