package ru.alfa.stand.test.eq.backend;

import ru.alfa.stand.test.eq.report.SeedLog;

/** Result of a completed (or partially completed) seed: the confirmed identifiers and the step's journal. */
public record SeedExecution(SeedResult result, SeedLog log) {

    public SeedExecution {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
    }
}