package ru.alfa.stand.test.core.environment;

import java.util.Locale;

/**
 * Whether a UI application permits recording a browser trace as a failure artifact.
 *
 * <p>A trace replays the session frame by frame, so it can carry whatever was on screen — including
 * personal data. The default is therefore the safe one ({@link #OFF}): a trace is recorded only where
 * the registry opts in for that application.
 */
public enum UiTraceMode {

    /** No trace is recorded. The default when the application declares none. */
    OFF,

    /** A trace is recorded and attached only for a failed run. */
    ON_FAILURE;

    /**
     * Reads the configured {@code trace} value, in the one place both configuration surfaces share.
     *
     * <p>The subtlety this centralises: YAML 1.1 resolves an unquoted {@code off} to the boolean
     * {@code false} — and {@code off} is precisely how the documented example spells it — so
     * {@code false} means {@link #OFF}. Its counterpart {@code true} means nothing here ({@code on} is
     * not a mode) and is rejected rather than silently enabling a recording that may capture personal
     * data. An absent value is {@link #OFF}, the safe default.
     *
     * @param value the raw configured value (a mode name, the boolean {@code false}, or null)
     * @return the resolved mode
     * @throws IllegalArgumentException if the value is neither a known mode nor the boolean {@code false}
     */
    public static UiTraceMode fromConfig(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return OFF;
        }
        if (value instanceof UiTraceMode mode) {
            return mode;
        }
        if (value instanceof String text && !"true".equalsIgnoreCase(text.trim())) {
            if ("false".equalsIgnoreCase(text.trim())) {
                return OFF;
            }
            try {
                return valueOf(text.trim().replace('-', '_').toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw rejected(text);
            }
        }
        throw rejected(String.valueOf(value));
    }

    private static IllegalArgumentException rejected(String value) {
        return new IllegalArgumentException("trace must be 'off' or 'on-failure', but was '" + value + "'");
    }
}
