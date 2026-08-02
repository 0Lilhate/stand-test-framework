package ru.alfa.stand.test.core.environment;

import java.util.Map;
import java.util.Optional;

/**
 * Definition of a whitelisted UI application: the logical alias a scenario addresses instead of a URL.
 *
 * <p>{@code baseUrlRef} is a reference (for example an environment-variable name) resolving to the
 * application's base address at run time — never a hardcoded URL. That is the point of the alias: a
 * scenario names {@code client-portal}, and the registry is the single place where
 * {@code client-portal} becomes an address. An alias a {@code ui.*} step names but the registry does not
 * declare is rejected pre-flight by the validator; whether a step may omit the alias altogether is the
 * UI step schema's rule, not this record's.
 *
 * <p>Everything about <em>how</em> the run is performed — which viewport, whether a trace may be
 * recorded, how to sign in — is configuration and lives here, never in the scenario model: the core
 * {@code Scenario} stays free of browser-specific fields, so changing a viewport changes no test code.
 *
 * <p>{@code viewportProfiles} is a closed whitelist of named sizes; {@code defaultViewport}, when
 * declared, must name one of them (a default pointing at an undeclared profile is rejected at
 * construction, exactly as a topic naming an undeclared Kafka cluster is).
 *
 * @param alias the logical application alias (never blank)
 * @param baseUrlRef a reference resolving to the application base URL (never blank)
 * @param defaultViewport the viewport profile used when a run selects none (may be null; when set it must
 *     name a declared profile)
 * @param viewportProfiles named viewport sizes keyed by profile name
 * @param trace whether a browser trace may be recorded (never null; defaults to {@link UiTraceMode#OFF})
 * @param auth the sign-in configuration (may be null when the application needs none)
 */
public record UiApplicationDefinition(
        String alias,
        String baseUrlRef,
        String defaultViewport,
        Map<String, ViewportProfile> viewportProfiles,
        UiTraceMode trace,
        UiAuthConfig auth) {

    public UiApplicationDefinition {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("ui application alias must not be blank");
        }
        if (baseUrlRef == null || baseUrlRef.isBlank()) {
            throw new IllegalArgumentException("baseUrlRef must not be blank");
        }
        viewportProfiles = (viewportProfiles == null) ? Map.of() : Map.copyOf(viewportProfiles);
        trace = (trace == null) ? UiTraceMode.OFF : trace;
        for (String profile : viewportProfiles.keySet()) {
            if (profile == null || profile.isBlank()) {
                throw new IllegalArgumentException("viewport profile names must not be blank in ui application '" + alias + "'");
            }
        }
        if (defaultViewport != null) {
            if (defaultViewport.isBlank()) {
                throw new IllegalArgumentException("defaultViewport must not be blank when declared in ui application '" + alias + "'");
            }
            if (!viewportProfiles.containsKey(defaultViewport)) {
                throw new IllegalArgumentException("ui application '" + alias + "' names default viewport '" + defaultViewport
                        + "', which is not declared in viewport-profiles (declared: " + viewportProfiles.keySet() + ")");
            }
        }
    }

    /**
     * Creates a minimal application definition: an alias and the reference resolving to its base URL.
     *
     * @param alias the logical application alias (never blank)
     * @param baseUrlRef a reference resolving to the application base URL (never blank)
     */
    public UiApplicationDefinition(String alias, String baseUrlRef) {
        this(alias, baseUrlRef, null, Map.of(), UiTraceMode.OFF, null);
    }

    /**
     * Resolves a named viewport profile.
     *
     * @param profile the profile name
     * @return the profile, or empty if not declared for this application
     */
    public Optional<ViewportProfile> viewportProfile(String profile) {
        return Optional.ofNullable(viewportProfiles.get(profile));
    }

    /**
     * Resolves the profile named by {@code defaultViewport}.
     *
     * @return the default profile, or empty when the application declares no default
     */
    public Optional<ViewportProfile> defaultViewportProfile() {
        return (defaultViewport == null) ? Optional.empty() : viewportProfile(defaultViewport);
    }
}
