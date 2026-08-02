package ru.alfa.stand.test.core.environment;

import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The declared <strong>format version</strong> of the environment registry configuration, and the one
 * place both configuration surfaces derive it from.
 *
 * <p>The registry loader is deliberately fail-closed: an unknown key is a configuration error. That is
 * what makes a typo in an alias or a section name fail loudly — and it is also what would make a file
 * carrying a <em>newer</em> section unreadable for an older SDK, with the unhelpful message
 * {@code Unknown field '<new-section>'}. An explicit format version turns that into a diagnosis: an SDK
 * that knows the {@code version} key can say "this file is version N, I support up to M — upgrade".
 *
 * <p>Behaviour of a declared version (identical on both surfaces, because both call this class):
 *
 * <table border="1">
 *   <caption>Version handling</caption>
 *   <tr><th>Declared</th><th>Result</th></tr>
 *   <tr><td>absent</td><td>read as {@link #INITIAL_VERSION} — existing files stay valid unchanged</td></tr>
 *   <tr><td>{@code <= }{@link #SUPPORTED_VERSION}</td><td>read</td></tr>
 *   <tr><td>{@code > }{@link #SUPPORTED_VERSION}</td><td>rejected with a message naming both versions and the action</td></tr>
 *   <tr><td>not a whole number, or {@code <= 0}</td><td>rejected as a configuration error (fail-closed)</td></tr>
 * </table>
 *
 * <p>Two surfaces read this: {@code stand-test-config}'s {@code stand-test-environments.yml} (root key
 * {@code version}) and the Spring Boot starter ({@code stand.test.version}). Keeping the numbers and the
 * wording here is what stops the two hand-maintained mappers from disagreeing about what they can read.
 */
public final class EnvironmentConfigFormat {

    /** Configuration key carrying the format version (root of the file; {@code stand.test.version} on the starter). */
    public static final String VERSION_FIELD = "version";

    /** The format version of a document that declares none — every file written before versioning existed. */
    public static final int INITIAL_VERSION = 1;

    /**
     * The highest registry format version this SDK build can read. Bumped by the change that introduces a
     * new section into the format — and never before an SDK that already understands the {@code version}
     * key has been released, or the older SDK still meets {@code Unknown field} and the diagnosis is void
     * (see {@code docs/publishing.md}).
     */
    public static final int SUPPORTED_VERSION = 1;

    private EnvironmentConfigFormat() {
    }

    /**
     * Validates a declared format version and returns the effective one.
     *
     * @param declared the raw declared value (null when the document declares none)
     * @param location the configuration location for the error message (for example {@code <document>})
     * @return the effective format version, {@link #INITIAL_VERSION} when none was declared
     * @throws StandTestException if the value is not a positive whole number, or is newer than
     *     {@link #SUPPORTED_VERSION}
     */
    public static int requireSupported(Object declared, String location) {
        if (declared == null) {
            return INITIAL_VERSION;
        }
        long version = wholeNumber(declared, location);
        if (version <= 0) {
            throw new StandTestException("Field '" + VERSION_FIELD + "' at " + location
                    + " must be a positive whole number — the environment registry FORMAT version (not the SDK version) — but was " + version);
        }
        if (version > SUPPORTED_VERSION) {
            throw new StandTestException("Environment registry format version " + version + " declared at " + location
                    + " is newer than this SDK supports (up to " + SUPPORTED_VERSION
                    + ") — upgrade the stand-test-* dependencies to a version that reads registry format " + version
                    + ", or remove the newer sections and declare version " + SUPPORTED_VERSION + ".");
        }
        return (int) version;
    }

    private static long wholeNumber(Object declared, String location) {
        if (declared instanceof Integer value) {
            return value;
        }
        if (declared instanceof Long value) {
            return value;
        }
        throw new StandTestException("Field '" + VERSION_FIELD + "' at " + location
                + " must be a whole number — the environment registry FORMAT version (not the SDK version) — but was '"
                + declared + "' (" + declared.getClass().getSimpleName() + ")");
    }
}
