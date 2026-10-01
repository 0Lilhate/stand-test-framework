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

    @Test
    @DisplayName("falls back to application.yml and reads the nested stand.test.environments section")
    void readsApplicationYamlNestedSection(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), """
                spring:
                  application:
                    name: demo
                stand:
                  test:
                    environments:
                      dev:
                        services:
                          svc:
                            base-url-ref: ${SVC_URL:http://127.0.0.1:8080}
                """, StandardCharsets.UTF_8);

        EnvironmentRegistry registry = loader(key -> null, directoryClassLoader(dir)).load();

        assertThat(registry.environment("dev").orElseThrow().service("svc").orElseThrow().baseUrlRef())
                .isEqualTo("${SVC_URL:http://127.0.0.1:8080}");
    }

    @Test
    @DisplayName("BR-01a: plain JUnit reads the default environment from application.yml")
    void readsDefaultEnvironmentFromApplicationYaml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), """
                stand:
                  test:
                    version: 6
                    default-environment: ${STAND_TEST_STAGE1_UNSET:ift}
                    environments:
                      ift: {}
                """, StandardCharsets.UTF_8);

        assertThat(loader(key -> null, directoryClassLoader(dir)).load().defaultEnvironment()).contains("ift");
    }

    @Test
    @DisplayName("application.yml with a dotted stand.test.environments key is read too")
    void readsApplicationYamlDottedSection(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), """
                stand.test.environments:
                  dev:
                    services:
                      svc: { base-url-ref: SVC_URL }
                """, StandardCharsets.UTF_8);

        EnvironmentRegistry registry = loader(key -> null, directoryClassLoader(dir)).load();

        assertThat(registry.environment("dev")).isPresent();
    }

    @Test
    @DisplayName("an application.yml without a stand.test.environments section contributes nothing (empty registry)")
    void applicationYamlWithoutSectionEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), "spring:\n  application:\n    name: demo\n", StandardCharsets.UTF_8);

        assertThat(loader(key -> null, directoryClassLoader(dir)).load().environment("dev")).isEmpty();
    }

    @Test
    @DisplayName("stand-test-environments.yml wins over application.yml when both are on the classpath")
    void dedicatedFileWinsOverApplicationYaml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("stand-test-environments.yml"), """
                environments:
                  from-dedicated:
                    services:
                      svc: { base-url-ref: SVC_URL }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("application.yml"), """
                stand:
                  test:
                    environments:
                      from-application:
                        services:
                          svc: { base-url-ref: SVC_URL }
                """, StandardCharsets.UTF_8);

        EnvironmentRegistry registry = loader(key -> null, directoryClassLoader(dir)).load();

        assertThat(registry.environment("from-dedicated")).isPresent();
        assertThat(registry.environment("from-application")).isEmpty();
    }

    @Test
    @DisplayName("the application.yml path carries stand.test.version too, so one file means the same thing here and on the starter")
    void readsApplicationYamlFormatVersion(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), """
                stand:
                  test:
                    version: 1
                    environments:
                      dev:
                        services:
                          svc: { base-url-ref: SVC_URL }
                """, StandardCharsets.UTF_8);

        EnvironmentRegistry registry = loader(key -> null, directoryClassLoader(dir)).load();

        assertThat(registry.environment("dev").orElseThrow().service("svc").orElseThrow().baseUrlRef())
                .isEqualTo("SVC_URL");
    }

    @Test
    @DisplayName("a newer stand.test.version in application.yml is refused with the version message, nested or dotted alike")
    void applicationYamlNewerVersionRejected(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), """
                stand:
                  test:
                    version: 99
                    environments:
                      dev:
                        services:
                          svc: { base-url-ref: SVC_URL }
                """, StandardCharsets.UTF_8);

        ClassLoader nested = directoryClassLoader(dir);
        assertThatThrownBy(() -> loader(key -> null, nested).load())
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("format version 99");

        Path dotted = dir.resolve("dotted");
        Files.createDirectories(dotted);
        Files.writeString(dotted.resolve("application.yml"), """
                stand.test.version: 99
                stand.test.environments:
                  dev:
                    services:
                      svc: { base-url-ref: SVC_URL }
                """, StandardCharsets.UTF_8);

        ClassLoader dottedLoader = directoryClassLoader(dotted);
        assertThatThrownBy(() -> loader(key -> null, dottedLoader).load())
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("format version 99");
    }

    @Test
    @DisplayName("the ui-applications section is readable through application.yml too, and its version gate applies there as well")
    void readsApplicationYamlUiApplications(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), """
                stand.test.version: 2
                stand.test.environments:
                  dev:
                    ui-applications:
                      client-portal: { base-url-ref: CLIENT_PORTAL_URL }
                """, StandardCharsets.UTF_8);

        assertThat(loader(key -> null, directoryClassLoader(dir)).load()
                .environment("dev").orElseThrow()
                .uiApplication("client-portal").orElseThrow().baseUrlRef())
                .isEqualTo("CLIENT_PORTAL_URL");

        Path undeclared = dir.resolve("no-version");
        Files.createDirectories(undeclared);
        Files.writeString(undeclared.resolve("application.yml"), """
                stand.test.environments:
                  dev:
                    ui-applications:
                      client-portal: { base-url-ref: CLIENT_PORTAL_URL }
                """, StandardCharsets.UTF_8);

        ClassLoader withoutVersion = directoryClassLoader(undeclared);
        assertThatThrownBy(() -> loader(key -> null, withoutVersion).load())
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("requires environment registry format version 2");
    }

    private static ClassLoader directoryClassLoader(Path dir) throws Exception {
        return new java.net.URLClassLoader(new java.net.URL[] {dir.toUri().toURL()}, null);
    }
}
