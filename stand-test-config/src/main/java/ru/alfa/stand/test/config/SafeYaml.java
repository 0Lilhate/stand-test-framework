package ru.alfa.stand.test.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Shared safe SnakeYAML loader for the environment-config file.
 *
 * <p>Uses {@link SafeConstructor} (no arbitrary Java-type instantiation) with conservative alias/nesting
 * limits, so the config file cannot smuggle in alias or nesting bombs. This mirrors the identically-named
 * hardened loader in {@code stand-test-scenario-yaml} (the security policy is duplicated deliberately so
 * each YAML entry point owns its own limits and there is no cross-module compile edge). JSON is a subset
 * of YAML, so this also loads JSON documents.
 */
final class SafeYaml {

    private SafeYaml() {
    }

    static Object load(String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setNestingDepthLimit(50);
        try {
            return new Yaml(new SafeConstructor(options)).load(source);
        } catch (RuntimeException parseFailure) {
            throw new StandTestException("Failed to parse environment config: " + parseFailure.getMessage(), parseFailure);
        }
    }
}
