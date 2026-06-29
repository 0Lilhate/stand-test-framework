package ru.alfa.stand.test.rest;

import java.util.Locale;

/**
 * HTTP methods supported by {@link RestStep}.
 *
 * <p>Each method maps to a core step type of the form {@code rest.<method>} (lower-case), which the
 * runner uses to dispatch to {@link RestStepExecutor}.
 */
public enum RestMethod {

    /** HTTP GET. */
    GET,

    /** HTTP POST. */
    POST,

    /** HTTP PUT. */
    PUT,

    /** HTTP DELETE. */
    DELETE;

    /**
     * Returns the core step type for this method, for example {@code rest.get}.
     *
     * @return the {@code rest.<method>} step type
     */
    public String stepType() {
        return RestStepParameters.TYPE_PREFIX + name().toLowerCase(Locale.ROOT);
    }
}
