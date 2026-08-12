package ru.alfa.stand.test.db;

import java.util.regex.Pattern;

/**
 * Validation of the SQL identifiers the SDK splices into SQL text rather than binding.
 *
 * <p>Bind <em>values</em> are always parameterized (plan §8.8), but a column <em>name</em> cannot be a
 * bind, so every identifier the SDK does interpolate is constrained to a plain unqualified identifier —
 * closing the injection vector a free-form column name would otherwise open. There are three, and the
 * list is worth keeping current: a reader who believes it is shorter than it is concludes that one of
 * these paths is unguarded.
 *
 * <ul>
 *   <li>{@code whereTestRunId} — becomes {@code WHERE <column> = :testRunId};</li>
 *   <li>{@code taggedByTestRunId} — the seed's tag column, verified against the INSERT column list;</li>
 *   <li>{@code identifiedBy} — becomes {@code WHERE <column> = :__pk_<column>} in the undo DELETE that
 *   {@code DbCompensator} builds.</li>
 * </ul>
 *
 * <p>Each is checked on BOTH surfaces: in {@code DbStep}'s builder, where an author meets it, and in
 * {@code DbStepParameters}, where a hand-assembled {@code GenericStep} that never touched the builder
 * arrives. The builder alone would be a suggestion.
 */
final class SqlIdentifiers {

    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private SqlIdentifiers() {
    }

    static boolean isPlainIdentifier(String identifier) {
        return identifier != null && PLAIN_IDENTIFIER.matcher(identifier).matches();
    }
}
