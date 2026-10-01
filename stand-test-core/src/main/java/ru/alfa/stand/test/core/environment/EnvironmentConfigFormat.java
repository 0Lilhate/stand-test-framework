package ru.alfa.stand.test.core.environment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <tr><td>absent</td><td>read as {@link #INITIAL_VERSION} — existing files stay valid unchanged; warned about, because version 1 is
 * behind</td></tr>
 *   <tr><td>{@code < }{@link #SUPPORTED_VERSION}</td><td>read, with one {@code WARN} naming both versions</td></tr>
 *   <tr><td>{@code == }{@link #SUPPORTED_VERSION}</td><td>read, silently</td></tr>
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
    public static final int SUPPORTED_VERSION = 6;

    /** Format version in which named extension sections became available. */
    public static final int SECTIONS_SINCE_VERSION = 6;

    /** Format version in which a registry could declare a default environment. */
    public static final int DEFAULT_ENVIRONMENT_SINCE_VERSION = 6;

    /** Format version in which the per-environment {@code ui-applications} section was introduced. */
    public static final int UI_APPLICATIONS_SINCE_VERSION = 2;

    /**
     * Format version in which a UI application's sign-in gained {@code auth.login} and {@code auth.challenge}.
     *
     * <p>A field added to an existing section counts as a new section for this purpose, and the rule is
     * applied here rather than argued away. It was tempting not to: version 2 has never been published, so
     * no reader of it exists and the bump names a contract nobody ever held. But the rule as written has no
     * "unreleased" exception, and an SDK built from an earlier commit — {@code publishToMavenLocal} makes
     * that a real object on a colleague's machine — meets {@code auth.login} as the bare
     * {@code Unknown field 'login'} this whole mechanism exists to replace. The cost of applying the rule is
     * a version number; the cost of reasoning around it is the promise itself.
     */
    public static final int UI_LOGIN_SINCE_VERSION = 3;

    /**
     * Format version in which a UI application gained {@code auth.credentials-username} and
     * {@code auth.credentials-password} — the pair that names one technical account directly, instead of
     * pointing at a roster of several.
     *
     * <p>The same rule as above, applied for the same reason: an SDK built before this change meets the
     * pair as {@code Unknown field 'credentials-username'} unless the document declares the version it
     * needs. Here the argument is stronger than it was for {@code auth.login}, because version 3 IS in use —
     * the example module's registry declares it — so a reader of the older contract really exists.
     */
    public static final int UI_DIRECT_CREDENTIALS_SINCE_VERSION = 4;

    /**
     * Format version in which the direct credential pair became a VALUE TWIN, with
     * {@code auth.credentials-username-ref} / {@code auth.credentials-password-ref} taking over the
     * reference spelling.
     *
     * <p>This is the one version so far that changes what an EXISTING key MEANS rather than adding a new
     * one, which is why the change is bound to a version at all. Under version 4 the bare
     * {@code credentials-username} is the NAME of an environment variable; from version 5 it is the value
     * itself, exactly like {@code base-url}, {@code url} and every other twin in this registry. A document
     * that declares 4 therefore keeps its meaning forever, and nothing in the field changes under anyone.
     *
     * <p>Why the flip rather than a third spelling: on the Spring starter a placeholder is resolved before
     * the SDK sees the field, so {@code ${web_username:tks_Admin}} arrives as the plain string
     * {@code tks_Admin} — indistinguishable from a variable name someone wrote by hand. The two meanings
     * cannot share one key on that front-end, and the registry's own convention already says which is
     * which: bare name is the value, {@code *-ref} is the reference.
     *
     * <p>Upgrading 4 → 5 is not silent. A version-5 document whose {@code credentials-username} or
     * {@code credentials-password} looks like a bare environment-variable NAME is REFUSED with a message
     * naming the {@code *-ref} spelling, because reading a variable name as a login is the one failure this
     * flip could cause and refusing is the only safe way to be wrong.
     */
    public static final int UI_CREDENTIAL_VALUE_TWINS_SINCE_VERSION = 5;

    /**
     * The shape of a bare environment-variable name, used ONLY to refuse an ambiguous credential value on
     * a version-5 document — never to reinterpret one.
     *
     * <p>Deliberately the strict spelling ({@code ^[A-Z][A-Z0-9_]{2,63}$}) that {@code envVarRef} uses
     * across this SDK: a login that happens to be upper-case-with-underscores is possible in principle, and
     * a consumer who has one writes it through {@code *-ref} or renames the account. Being told to choose
     * costs a minute; signing in as the literal string {@code TAKSA_ADMIN_USERNAME} costs a debugging
     * session, and the message would not say why.
     */
    public static final String ENVIRONMENT_VARIABLE_NAME_PATTERN = "^[A-Z][A-Z0-9_]{2,63}$";

    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentConfigFormat.class);

    private EnvironmentConfigFormat() {
    }

    /**
     * Refuses a version-5 credential value that is shaped like an environment-variable name.
     *
     * @param value the configured value (may be null)
     * @param field the field name, for the message
     * @param location where the field sits, for the message
     */
    public static void rejectVariableNameAsCredentialValue(String value, String field, String location) {
        if (value == null || !value.matches(ENVIRONMENT_VARIABLE_NAME_PATTERN)) {
            return;
        }
        throw new StandTestException("Field '" + field + "' at " + location + " carries '" + value
                + "', which is shaped like the NAME of an environment variable, while from format version "
                + UI_CREDENTIAL_VALUE_TWINS_SINCE_VERSION + " this field holds the VALUE itself."
                + " Signing in with that literal string is almost certainly not what was meant."
                + " Use '" + field + "-ref: " + value + "' to keep it a reference, or write the credential value here.");
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
            warnIfBehind(INITIAL_VERSION, location);
            return INITIAL_VERSION;
        }
        long version = wholeNumber(declared, location);
        if (version <= 0) {
            throw new StandTestException("Field '" + VERSION_FIELD + "' at " + location
                    + " must be a positive whole number — the environment registry FORMAT version (not the SDK version) — but was "
                            + version);
        }
        if (version > SUPPORTED_VERSION) {
            throw new StandTestException("Environment registry format version " + version + " declared at " + location
                    + " is newer than this SDK supports (up to " + SUPPORTED_VERSION
                    + ") — upgrade the stand-test-* dependencies to a version that reads registry format " + version
                    + ", or remove the newer sections and declare version " + SUPPORTED_VERSION + ".");
        }
        warnIfBehind((int) version, location);
        return (int) version;
    }

    /**
     * Reports a document that is behind the format this SDK reads — and says, in the same breath, that
     * nothing is going to happen to it.
     *
     * <p>ADR-UI-004 accepted <strong>no compatibility window</strong>: every version from
     * {@link #INITIAL_VERSION} is read indefinitely. That decision constrains this message more than it
     * constrains the code. A warning is cheap to ignore, so the failure mode here is not silence but a
     * lie — "your file will stop working" would be false, and a threat that never arrives teaches the
     * reader to skip warnings from this SDK, including the ones that mean something. So the line states
     * the fact and states the reassurance, and {@code EnvironmentConfigFormatLoggingTest} pins both: the
     * vocabulary of removal is forbidden, the word {@code readable} is required.
     *
     * <p>A document declaring no version is version 1, which is behind — so it is reported, at the same
     * level and in the same words. Absence is a lagging version, not a mistake.
     *
     * <p>The call sits on the parse of the document, which happens once per surface per load, rather
     * than on alias resolution — otherwise a scenario touching a dozen aliases would print a dozen
     * copies and the channel would become the noise it exists to avoid. Nothing but the two version
     * numbers and the configuration location can reach the text: those are the only things in scope.
     *
     * @param effective the version the document is being read as
     * @param location the configuration location (for example {@code <document>} or {@code stand.test})
     */
    private static void warnIfBehind(int effective, String location) {
        if (effective >= SUPPORTED_VERSION) {
            return;
        }
        LOG.warn("Environment registry format version {} at {} — this SDK reads up to {}."
                        + " The file stays readable: there is no compatibility window, and every version from {} is read indefinitely."
                        + " Declare a newer version when you want the sections added since it.",
                effective, location, SUPPORTED_VERSION, INITIAL_VERSION);
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
                + "' on the Spring starter) so an SDK that predates this section refuses with a version "
                + "message instead of 'Unknown field'.");
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
