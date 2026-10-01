package ru.alfa.stand.test.eq.config;

/** Registry configuration for the showcases writer. */
public record ShowcasesBackendConfig(String alias, String service, String path,
                                    VisibilityConfig visibility, EqDefaults defaults) implements EqBackendConfig {

    public ShowcasesBackendConfig(String alias, String service, String path) {
        this(alias, service, path, null, null);
    }

    public ShowcasesBackendConfig(String alias, String service, String path, VisibilityConfig visibility) {
        this(alias, service, path, visibility, null);
    }
}
