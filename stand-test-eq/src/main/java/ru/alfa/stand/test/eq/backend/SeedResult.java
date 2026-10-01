package ru.alfa.stand.test.eq.backend;

import java.util.List;
import java.util.Map;

/** Identifiers confirmed by a backend after every write succeeds. */
public record SeedResult(String pin, List<String> accounts, Map<Integer, String> deals) {

    public SeedResult {
        accounts = List.copyOf(accounts);
        deals = Map.copyOf(deals);
    }
}
