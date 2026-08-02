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
 * <p>Sections introduced after version 1 additionally require the document to <em>declare</em> the
 * version they arrived in ({@link #requireSectionSupported}). Without that rule the version key would
 * guarantee nothing in practice: a file could carry a newer section while still claiming version 1, and
 * an older SDK would again meet {@code Unknown field} instead of a version message.
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

    /** The highest registry format version this SDK build can read. */
    public static final int SUPPORTED_VERSION = 2;

    /** Format version in which the per-environment {@code ui-applications} section was introduced. */
    public static final int UI_APPLICATIONS_SINCE_VERSION = 2;

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

    /**
     * Enforces that a section introduced after {@link #INITIAL_VERSION} is used only in a document that
     * declares at least the version it arrived in.
     *
     * @param declaredVersion the effective version returned by {@link #requireSupported}
     * @param section the section name as spelled in configuration (for example {@code ui-applications})
     * @param since the first format version carrying the section
     * @param location the configuration location for the error message
     * @throws StandTestException if the document declares an older version than the section requires
     */
    public static void requireSectionSupported(int declaredVersion, String section, int since, String location) {
        if (declaredVersion >= since) {
            return;
        }
        throw new StandTestException("Section '" + section + "' at " + location + " requires environment registry format version "
                + since + ", but the document declares version " + declaredVersion
                + " — declare the format version at the configuration root ('" + VERSION_FIELD + ": " + since
                + "' in stand-test-environments.yml, 'stand.test." + VERSION_FIELD + ": " + since
                + "' on the Spring starter) so an SDK that predates this section refuses with a version message instead of 'Unknown field'.");
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
