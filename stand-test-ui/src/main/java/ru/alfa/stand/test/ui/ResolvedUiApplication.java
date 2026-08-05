package ru.alfa.stand.test.ui;

import java.util.Objects;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.ViewportProfile;

/**
 * A UI application alias after the environment registry has been consulted: the concrete base URL the
 * browser will use, the viewport it will use it at, and how a test signs in to it.
 *
 * <p>The base URL arrives here and nowhere else. A scenario cannot express one, and the registry stores a
 * reference rather than a value, so this record is the single place a real address exists at run time.
 *
 * <p>The sign-in configuration rides along <em>resolved</em> instead of being read from the registry a
 * second time by the sign-in step: one lookup, one answer, and no way for the step and the session to
 * disagree about which application they are talking about. It carries references only, never a credential.
 *
 * @param alias the logical alias, kept for diagnostics
 * @param baseUrl the resolved base URL
 * @param viewport the viewport to open at, or null for the browser default
 * @param auth how a test signs in, or null when the application declares no sign-in
 */
public record ResolvedUiApplication(String alias, String baseUrl, ViewportProfile viewport, UiAuthConfig auth) {

    /**
     * Validates the resolved application.
     */
    public ResolvedUiApplication {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
    }

    /**
     * Creates a resolved application that declares no sign-in.
     *
     * @param alias the logical alias, kept for diagnostics
     * @param baseUrl the resolved base URL
     * @param viewport the viewport to open at, or null for the browser default
     */
    public ResolvedUiApplication(String alias, String baseUrl, ViewportProfile viewport) {
        this(alias, baseUrl, viewport, null);
    }

    /**
     * Joins the base URL with a relative path, tolerating a trailing / leading slash on either side.
     *
     * @param relativePath the relative path
     * @return the absolute address to navigate to
     */
    public String urlFor(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        String base = this.baseUrl.endsWith("/") ? this.baseUrl.substring(0, this.baseUrl.length() - 1) : this.baseUrl;
        String path = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
        return base + path;
    }
}
