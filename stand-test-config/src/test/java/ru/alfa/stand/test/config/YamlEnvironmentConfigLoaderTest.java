package ru.alfa.stand.test.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlEnvironmentConfigLoaderTest {

    private static final ClassLoader NO_RESOURCES = new ClassLoader(null) {
    };

    private static YamlEnvironmentConfigLoader loader(java.util.function.UnaryOperator<String> systemProperty, ClassLoader classLoader) {
        return new YamlEnvironmentConfigLoader(systemProperty, classLoader);
    }

    @Test
    @DisplayName("loads the default classpath resource when present")
    void loadsDefaultResource() {
        EnvironmentRegistry registry = loader(key -> null, getClass().getClassLoader()).load();
        assertThat(registry.environment("ift")).isPresent();
        assertThat(registry.environment("ift").orElseThrow().service("client-service")).isPresent();
    }

    @Test
    @DisplayName("yields an empty registry when no config file is present")
    void emptyWhenAbsent() {
        EnvironmentRegistry registry = loader(key -> null, NO_RESOURCES).load();
        assertThat(registry.environment("ift")).isEmpty();
    }

    @Test
    @DisplayName("reads an explicit file path from the system property")
    void readsExplicitPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("envs.yml");
        Files.writeString(file, "environments:\n  dev:\n    services:\n      svc: { base-url-ref: SVC_URL }\n", StandardCharsets.UTF_8);
        EnvironmentRegistry registry = loader(key -> file.toString(), NO_RESOURCES).load();
        assertThat(registry.environment("dev").orElseThrow().service("svc").orElseThrow().baseUrlRef()).isEqualTo("SVC_URL");
    }

    @Test
    @DisplayName("a missing explicit path is a configuration error")
    void missingExplicitPathFails() {
        assertThatThrownBy(() -> loader(key -> "/no/such/stand-test-environments.yml", NO_RESOURCES).load())
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("a blank file yields an empty registry")
    void blankFileEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blank.yml");
        Files.writeString(file, "   \n", StandardCharsets.UTF_8);
        assertThat(loader(key -> file.toString(), NO_RESOURCES).load().environment("ift")).isEmpty();
    }
}
