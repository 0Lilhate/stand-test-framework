package ru.alfa.stand.test.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Loads an {@link EnvironmentRegistry} from a declarative YAML file.
 *
 * <p>Source resolution, in order: if the system property {@value #CONFIG_PROPERTY} is set, its value is a
 * filesystem path that <strong>must</strong> exist (a missing explicit path is a
 * {@link StandTestException}). Otherwise the classpath resource {@value #DEFAULT_RESOURCE} (or
 * {@value #DEFAULT_RESOURCE_ALT}) is used. Otherwise the familiar {@value #APPLICATION_RESOURCE} (or
 * {@value #APPLICATION_RESOURCE_ALT}) is consulted: its {@code stand.test.environments} section — either
 * nested ({@code stand: test: environments:}) or with dotted keys — is read with the exact same schema
 * the Spring Boot starter binds, so one configuration style serves both worlds; an application.yml
 * without that section contributes nothing. When no source is present the result is an empty registry
 * (unchanged behaviour). Malformed YAML, unknown keys and invalid references are reported as
 * config-class {@link StandTestException}.
 */
public final class YamlEnvironmentConfigLoader {

    /** System property whose value is a filesystem path to the environment-config YAML file. */
    public static final String CONFIG_PROPERTY = "stand.test.environments.config";

    /** Default classpath resource name for the environment-config file. */
    public static final String DEFAULT_RESOURCE = "stand-test-environments.yml";

    /** Alternative default classpath resource name ({@code .yaml} extension). */
    public static final String DEFAULT_RESOURCE_ALT = "stand-test-environments.yaml";

    /** Familiar application-config resource whose {@code stand.test.environments} section is read. */
    public static final String APPLICATION_RESOURCE = "application.yml";

    /** Alternative application-config resource name ({@code .yaml} extension). */
    public static final String APPLICATION_RESOURCE_ALT = "application.yaml";

    private final UnaryOperator<String> systemProperty;
    private final ClassLoader classLoader;

    /**
     * Creates a loader backed by the process system properties and the thread context class loader.
     */
    public YamlEnvironmentConfigLoader() {
        this(System::getProperty, Thread.currentThread().getContextClassLoader());
    }

    /**
     * Creates a loader with explicit collaborators (for tests).
     *
     * @param systemProperty resolves a system-property name to its value (or null)
     * @param classLoader the class loader used to find the default classpath resource (may be null)
     */
    YamlEnvironmentConfigLoader(UnaryOperator<String> systemProperty, ClassLoader classLoader) {
        this.systemProperty = Objects.requireNonNull(systemProperty, "systemProperty must not be null");
        this.classLoader = (classLoader != null) ? classLoader : YamlEnvironmentConfigLoader.class.getClassLoader();
    }

    /**
     * Resolves the config source and builds the registry.
     *
     * @return the loaded registry, or an empty registry when no config file is present
     */
    public EnvironmentRegistry load() {
        String path = this.systemProperty.apply(CONFIG_PROPERTY);
        String content;
        if (path != null && !path.isBlank()) {
            content = readFile(path.trim());
        } else {
            content = readClasspath();
            if (content == null) {
                return loadFromApplicationYaml();
            }
        }
        if (content.isBlank()) {
            return new InMemoryEnvironmentRegistry(Map.of());
        }
        return EnvironmentConfig.toRegistry(SafeYaml.load(content));
    }

    private EnvironmentRegistry loadFromApplicationYaml() {
        String content = readResource(APPLICATION_RESOURCE);
        if (content == null) {
            content = readResource(APPLICATION_RESOURCE_ALT);
        }
        if (content == null || content.isBlank()) {
            return new InMemoryEnvironmentRegistry(Map.of());
        }
        Object environments = standTestEnvironments(SafeYaml.load(content));
        if (environments == null) {
            // The application.yml belongs to the app; without a stand.test.environments section it
            // contributes nothing — same fail-safe outcome as having no config file at all.
            return new InMemoryEnvironmentRegistry(Map.of());
        }
        return EnvironmentConfig.toRegistry(Map.of("environments", environments));
    }

    /**
     * Extracts the {@code stand.test.environments} subtree, accepting both the nested spelling
     * ({@code stand: test: environments:}) and dotted keys at any join point
     * ({@code stand.test: environments:}, {@code stand.test.environments:}) — mirroring how Spring's
     * relaxed binding treats the same document.
     */
    private static Object standTestEnvironments(Object root) {
        if (!(root instanceof Map<?, ?> map)) {
            return null;
        }
        Object dotted = map.get("stand.test.environments");
        if (dotted != null) {
            return dotted;
        }
        Object standTest = map.get("stand.test");
        if (standTest == null && map.get("stand") instanceof Map<?, ?> stand) {
            standTest = stand.get("test");
        }
        if (standTest instanceof Map<?, ?> standTestMap) {
            return standTestMap.get("environments");
        }
        return null;
    }

    private static String readFile(String path) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            throw new StandTestException("Environment config file '" + path + "' (from -D" + CONFIG_PROPERTY + ") does not exist");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StandTestException("Failed to read environment config file '" + path + "': " + failure.getMessage(), failure);
        }
    }

    private String readClasspath() {
        String content = readResource(DEFAULT_RESOURCE);
        return (content != null) ? content : readResource(DEFAULT_RESOURCE_ALT);
    }

    private String readResource(String resource) {
        try (InputStream stream = this.classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StandTestException("Failed to read environment config resource '" + resource + "': " + failure.getMessage(), failure);
        }
    }
}
