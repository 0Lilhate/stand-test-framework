package ru.alfa.stand.test.eq.report;

/**
 * One allowlisted EQ operation record, safe to render into a report.
 *
 * <p>Only the metadata permitted by NFR-05 lives here: the option name, its one-based position in the
 * chain, a classified status/category and a duration. Request/response bodies, headers, params, personal
 * names and credentials never enter this type.
 */
public record SeedOperation(String option, int sequence, String status, long durationMillis) {

    public SeedOperation {
        if (option == null || option.isBlank()) {
            throw new IllegalArgumentException("option must not be blank");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
    }
}