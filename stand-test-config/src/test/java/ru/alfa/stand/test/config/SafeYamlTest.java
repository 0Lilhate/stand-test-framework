package ru.alfa.stand.test.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Pins the {@link SafeYaml} hardening: SnakeYAML's {@code SafeConstructor} plus the explicit
 * {@code LoaderOptions} limits (no duplicate keys, at most 10 aliases per collection, nesting depth 50)
 * must reject hostile documents with the config-class "Failed to parse" diagnostic, never load them.
 */
class SafeYamlTest {

    @Test
    @DisplayName("a well-formed document loads into a plain map tree")
    void wellFormedDocumentLoads() {
        Object root = SafeYaml.load("environments:\n  ift:\n    services: {}\n");

        assertThat(root).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) root).get("environments")).isNotNull();
    }

    @Test
    @DisplayName("malformed YAML syntax is a StandTestException with the 'Failed to parse' diagnostic")
    void malformedSyntaxRejected() {
        assertThatThrownBy(() -> SafeYaml.load("a: [unclosed"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to parse environment config");
    }

    @Test
    @DisplayName("duplicate keys are rejected, not silently last-one-wins")
    void duplicateKeysRejected() {
        assertThatThrownBy(() -> SafeYaml.load("a: 1\na: 2\n"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to parse environment config");
    }

    @Test
    @DisplayName("an arbitrary-type tag (!!javax...) is rejected by the SafeConstructor")
    void arbitraryTypeTagRejected() {
        assertThatThrownBy(() -> SafeYaml.load("a: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader []]"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to parse environment config");
    }

    @Test
    @DisplayName("an alias bomb (more than 10 aliases for collections) is rejected")
    void aliasBombRejected() {
        StringBuilder yaml = new StringBuilder("base: &b [1, 2]\nlist: [");
        for (int i = 0; i < 11; i++) {
            if (i > 0) {
                yaml.append(", ");
            }
            yaml.append("*b");
        }
        yaml.append("]\n");

        assertThatThrownBy(() -> SafeYaml.load(yaml.toString()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to parse environment config");
    }

    @Test
    @DisplayName("a document nested deeper than 50 levels is rejected")
    void excessiveNestingRejected() {
        StringBuilder yaml = new StringBuilder();
        for (int depth = 0; depth < 60; depth++) {
            yaml.append("  ".repeat(depth)).append("a:\n");
        }
        yaml.append("  ".repeat(60)).append("1\n");

        assertThatThrownBy(() -> SafeYaml.load(yaml.toString()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to parse environment config");
    }
}
