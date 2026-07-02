package ru.alfa.stand.test.scenario;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Shared safe SnakeYAML loader for the declarative scenario parsers.
 *
 * <p>Uses {@link SafeConstructor} (no arbitrary Java-type instantiation) with conservative alias/nesting
 * limits, so both the {@code given/then} YAML surface and the AI {@code steps/type} surface reject alias
 * and nesting bombs identically — the security hardening lives in one place and cannot drift. JSON is a
 * subset of YAML, so this also loads JSON documents.
 */
final class SafeYaml {

    private SafeYaml() {
    }

    static Object load(String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        // Explicit, conservative limits: this format is aimed at AI-generated scenarios (plan §4), so
        // reject alias/nesting bombs at parse time rather than relying on library defaults.
        options.setMaxAliasesForCollections(10);
        options.setNestingDepthLimit(50);
        try {
            return new Yaml(new SafeConstructor(options)).load(source);
        } catch (RuntimeException parseFailure) {
            throw new StandTestException("Failed to parse document: " + parseFailure.getMessage(), parseFailure);
        }
    }
}
