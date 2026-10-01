package ru.alfa.stand.test.eq.report;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Allowlisted, immutable snapshot of one {@code eq.seed} step's operation log.
 *
 * <p>This is the evidence an {@code eq.seed} step attaches to its own {@code StepEvent} — on success
 * through {@code StepResult.attachments()} and on failure through {@link
 * ru.alfa.stand.test.core.event.FailureAttachments} — so that a reporting reader sees the operations of
 * that step and nothing else. It is deliberately NOT the shared {@code eq-seeded.jsonl}: that file is a
 * whole-suite maintenance artefact, whereas this snapshot belongs to exactly one step (BR-43).
 *
 * <p>The rendered text carries only NFR-05 fields: option, sequence, classified status, duration and
 * the confirmed identifiers. It never carries raw request/response bodies, headers, params, personal
 * names, INN, DUL or credentials — those are never recorded in the first place, so there is nothing to
 * mask here.
 *
 * @param alias the variable prefix of the step
 * @param backend the backend alias the step ran against
 * @param pin the confirmed client PIN, or null when no client was confirmed
 * @param accounts the confirmed account numbers, in creation order
 * @param deals the number of confirmed package deals
 * @param approximations the attributes the backend approximated, if any
 * @param operations the ordered allowlisted operation records
 */
public record SeedLog(String alias, String backend, String pin, List<String> accounts, int deals,
                      List<String> approximations, List<SeedOperation> operations) {

    public SeedLog {
        accounts = List.copyOf(accounts == null ? List.of() : accounts);
        approximations = List.copyOf(approximations == null ? List.of() : approximations);
        operations = List.copyOf(operations == null ? List.of() : operations);
    }

    /** Renders the snapshot as a plain-text attachment body. */
    public String toText() {
        StringBuilder text = new StringBuilder("eq.seed '").append(alias).append("' backend=").append(backend).append('\n');
        if (pin != null) {
            text.append("confirmed PIN ").append(pin).append('\n');
        }
        if (!accounts.isEmpty()) {
            text.append("confirmed accounts ").append(String.join(", ", accounts)).append('\n');
        }
        text.append("confirmed deals ").append(deals).append('\n');
        if (!approximations.isEmpty()) {
            text.append("approximated ").append(String.join(", ", approximations)).append('\n');
        }
        for (SeedOperation operation : operations) {
            text.append(operation.option()).append(" #").append(operation.sequence())
                    .append(" ").append(operation.status()).append(" in ")
                    .append(operation.durationMillis()).append(" ms\n");
        }
        return text.toString();
    }

    /** Returns the UTF-8 bytes of {@link #toText()}. */
    public byte[] toBytes() {
        return toText().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Collects operations while a backend executes, then freezes them into a snapshot.
     *
     * <p>A per-step collector, not shared state: two parallel {@code eq.seed} steps never see each
     * other's operations, which is the isolation BR-43 requires.
     */
    public static final class Collector {

        private final List<SeedOperation> operations = new ArrayList<>();

        /** Appends one completed operation. */
        public synchronized void record(String option, String status, long durationMillis) {
            operations.add(new SeedOperation(option, operations.size() + 1, status, durationMillis));
        }

        /** Builds the immutable snapshot carrying the given confirmed identifiers. */
        public synchronized SeedLog snapshot(String alias, String backend, String pin, List<String> accounts,
                                             int deals, List<String> approximations) {
            return new SeedLog(alias, backend, pin, accounts, deals, approximations, List.copyOf(operations));
        }
    }
}