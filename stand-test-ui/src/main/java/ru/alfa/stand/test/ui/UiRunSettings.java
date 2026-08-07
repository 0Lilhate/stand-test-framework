package ru.alfa.stand.test.ui;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * How the browser is run: headed or headless, which engine, the bounds on a single action, and where the
 * run's artefacts are kept.
 *
 * <p>These are properties of the <em>environment a test runs in</em>, not of the scenario, which is why
 * they are configuration and never fields of a step: the same scenario must run headless on CI and
 * headed on a developer's machine without editing a line of it.
 *
 * <table border="1">
 * <caption>System properties</caption>
 * <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 * <tr><td>{@code stand.test.ui.headless}</td><td>{@code true}</td><td>Run without a visible window</td></tr>
 * <tr><td>{@code stand.test.ui.browser}</td><td>{@code chromium}</td><td>Browser engine</td></tr>
 * <tr><td>{@code stand.test.ui.action.timeout.millis}</td><td>{@code 10000}</td><td>Bound on one click / fill</td></tr>
 * <tr><td>{@code stand.test.ui.navigation.timeout.millis}</td><td>{@code 30000}</td><td>Bound on one navigation</td></tr>
 * <tr><td>{@code stand.test.ui.artifacts.dir}</td><td>{@code build/stand-test-ui}</td><td>Where saved browser sessions and the failure artefacts are kept</td></tr>
 * <tr><td>{@code stand.test.ui.artifacts.retention.days}</td><td>{@code 7}</td><td>How long a run's failure artefacts are kept before the next run sweeps them</td></tr>
 * </table>
 *
 * @param headless whether the browser runs without a visible window
 * @param browser the browser engine name
 * @param actionTimeout the bound on a single click / fill
 * @param navigationTimeout the bound on a single navigation
 * @param artifactsDirectory the root of the run's artefacts, holding among other things the saved browser
 *     sessions — files that are effectively secrets and live under the retention rules of SEC-09
 * @param artifactRetention how old an artefact may grow before the next run deletes it; never applies to
 *     the {@code storage-state} directory, which lives by its own rule (UITG-S018, SEC-09)
 */
public record UiRunSettings(
        boolean headless,
        String browser,
        Duration actionTimeout,
        Duration navigationTimeout,
        Path artifactsDirectory,
        Duration artifactRetention) {

    /** System property selecting headless or headed mode. */
    public static final String HEADLESS_PROPERTY = "stand.test.ui.headless";

    /** System property selecting the browser engine. */
    public static final String BROWSER_PROPERTY = "stand.test.ui.browser";

    /** System property bounding one action. */
    public static final String ACTION_TIMEOUT_PROPERTY = "stand.test.ui.action.timeout.millis";

    /** System property bounding one navigation. */
    public static final String NAVIGATION_TIMEOUT_PROPERTY = "stand.test.ui.navigation.timeout.millis";

    /** System property selecting the artefacts directory. */
    public static final String ARTIFACTS_DIRECTORY_PROPERTY = "stand.test.ui.artifacts.dir";

    /** System property selecting how long a run's failure artefacts are kept (in days). */
    public static final String ARTIFACTS_RETENTION_DAYS_PROPERTY = "stand.test.ui.artifacts.retention.days";

    /** The default browser engine. */
    public static final String DEFAULT_BROWSER = "chromium";

    /** The default artefacts directory, relative to the working directory of the build. */
    public static final String DEFAULT_ARTIFACTS_DIRECTORY = "build/stand-test-ui";

    /** The default retention of failure artefacts: no longer than the CI report retention (SEC-09). */
    public static final Duration DEFAULT_ARTIFACT_RETENTION = Duration.ofDays(7);

    /**
     * Validates the settings.
     */
    public UiRunSettings {
        if (browser == null || browser.isBlank()) {
            throw new IllegalArgumentException("browser must not be blank");
        }
        requirePositive(actionTimeout, "actionTimeout");
        requirePositive(navigationTimeout, "navigationTimeout");
        Objects.requireNonNull(artifactsDirectory, "artifactsDirectory must not be null");
        Objects.requireNonNull(artifactRetention, "artifactRetention must not be null");
        if (artifactRetention.isZero() || artifactRetention.isNegative()) {
            throw new IllegalArgumentException("artifactRetention must be strictly positive");
        }
    }

    /**
     * Creates settings with the default artefacts directory and the default retention.
     *
     * @param headless whether the browser runs without a visible window
     * @param browser the browser engine name
     * @param actionTimeout the bound on a single click / fill
     * @param navigationTimeout the bound on a single navigation
     */
    public UiRunSettings(boolean headless, String browser, Duration actionTimeout, Duration navigationTimeout) {
        this(headless, browser, actionTimeout, navigationTimeout, Paths.get(DEFAULT_ARTIFACTS_DIRECTORY), DEFAULT_ARTIFACT_RETENTION);
    }

    /**
     * Creates settings with the default retention.
     *
     * @param headless whether the browser runs without a visible window
     * @param browser the browser engine name
     * @param actionTimeout the bound on a single click / fill
     * @param navigationTimeout the bound on a single navigation
     * @param artifactsDirectory the root of the run's artefacts
     */
    public UiRunSettings(boolean headless, String browser, Duration actionTimeout, Duration navigationTimeout, Path artifactsDirectory) {
        this(headless, browser, actionTimeout, navigationTimeout, artifactsDirectory, DEFAULT_ARTIFACT_RETENTION);
    }

    /**
     * The settings a run uses unless a system property says otherwise. Headless is the default because
     * CI is the default place these tests run; a developer flips one property to watch the browser.
     *
     * @return the settings read from system properties
     */
    public static UiRunSettings fromSystemProperties() {
        return fromProperties(System::getProperty);
    }

    /**
     * Reads the settings from an arbitrary property source (for tests).
     *
     * @param source resolves a property name to its value, or null when unset
     * @return the settings
     */
    public static UiRunSettings fromProperties(UnaryOperator<String> source) {
        Objects.requireNonNull(source, "source must not be null");
        String headless = source.apply(HEADLESS_PROPERTY);
        String browser = source.apply(BROWSER_PROPERTY);
        String artifacts = source.apply(ARTIFACTS_DIRECTORY_PROPERTY);
        String retentionDays = source.apply(ARTIFACTS_RETENTION_DAYS_PROPERTY);
        return new UiRunSettings(
                headless == null || Boolean.parseBoolean(headless),
                (browser == null || browser.isBlank()) ? DEFAULT_BROWSER : browser.trim(),
                Duration.ofMillis(positiveMillis(source.apply(ACTION_TIMEOUT_PROPERTY), ACTION_TIMEOUT_PROPERTY, UiStepParameters.DEFAULT_ACTION_TIMEOUT_MILLIS)),
                Duration.ofMillis(positiveMillis(source.apply(NAVIGATION_TIMEOUT_PROPERTY), NAVIGATION_TIMEOUT_PROPERTY, UiStepParameters.DEFAULT_TIMEOUT_MILLIS)),
                Paths.get((artifacts == null || artifacts.isBlank()) ? DEFAULT_ARTIFACTS_DIRECTORY : artifacts.trim()),
                Duration.ofDays(positiveDays(retentionDays, ARTIFACTS_RETENTION_DAYS_PROPERTY, DEFAULT_ARTIFACT_RETENTION.toDays())));
    }

    private static long positiveMillis(String raw, String property, long defaultMillis) {
        if (raw == null || raw.isBlank()) {
            return defaultMillis;
        }
        long millis;
        try {
            millis = Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("System property '" + property + "' must be a number of milliseconds, but was '" + raw + "'", notANumber);
        }
        if (millis <= 0) {
            throw new IllegalArgumentException("System property '" + property + "' must be strictly positive, but was " + millis);
        }
        return millis;
    }

    /**
     * Parses a whole number of days (used for the artefacts retention) with the same loud refusal as the
     * millisecond properties: a misconfigured lifetime must not silently fall back to the default.
     */
    private static long positiveDays(String raw, String property, long defaultDays) {
        if (raw == null || raw.isBlank()) {
            return defaultDays;
        }
        long days;
        try {
            days = Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("System property '" + property + "' must be a number of days, but was '" + raw + "'", notANumber);
        }
        if (days <= 0) {
            throw new IllegalArgumentException("System property '" + property + "' must be strictly positive, but was " + days);
        }
        return days;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be strictly positive");
        }
    }
}
