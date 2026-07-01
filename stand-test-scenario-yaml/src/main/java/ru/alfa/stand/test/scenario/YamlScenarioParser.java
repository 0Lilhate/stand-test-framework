package ru.alfa.stand.test.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;

/**
 * Parses a declarative YAML scenario into the generic core {@link Scenario} model — the second input (after
 * the Java DSL) to the same model (plan §3). It only <em>builds</em> the model: it opens no connections,
 * reads no environment, executes nothing, and leaves {@code ${...}} placeholders verbatim for the runtime
 * {@code VariableResolver}. The consumer runs the returned {@code Scenario} through the shared core runner
 * ({@code StandClient} → {@code DefaultScenarioRunner} + {@code StepExecutor} SPI), so this engine has no
 * compile-time edge to any adapter.
 *
 * <p>The surface syntax (annotated in {@code docs/arch/stand-test-scenario-yaml-design.md}) is translated
 * into the exact {@code GenericStep} parameter keys the adapter executors read. YAML is loaded with
 * SnakeYAML's {@code SafeConstructor} (no arbitrary type instantiation). Any structural or type problem is
 * reported as a config-class {@link StandTestException} with a location, fail-closed: unknown top-level
 * keys, unknown step types, ill-typed fields and malformed durations are rejected here rather than deferred
 * to the runner. Model-level checks (id/environment/step-id uniqueness) stay with the shared validator.
 *
 * <p>Stateless and thread-safe: a fresh SnakeYAML {@code Yaml} is created per call.
 */
public final class YamlScenarioParser {

    private static final Set<String> KNOWN_TOP_LEVEL = Set.of("id", "env", "title", "description", "tags", "given", "then");

    /**
     * Parses a YAML scenario document.
     *
     * @param yaml the YAML document text
     * @return the built {@link Scenario}
     * @throws StandTestException if the document is structurally invalid
     */
    public Scenario parse(String yaml) {
        Objects.requireNonNull(yaml, "yaml must not be null");
        Map<String, Object> document = SurfaceValues.asMap(load(yaml), "<document>");
        SurfaceValues.checkKnownKeys(document, KNOWN_TOP_LEVEL, "<document>");

        var builder = Scenario.builder(SurfaceValues.requireString(document, "id", "<document>"))
                .environment(SurfaceValues.requireString(document, "env", "<document>"));
        String title = SurfaceValues.optionalString(document, "title", "<document>");
        if (title != null) {
            builder.title(title);
        }
        String description = SurfaceValues.optionalString(document, "description", "<document>");
        if (description != null) {
            builder.description(description);
        }
        for (Object tag : tags(document)) {
            if (!(tag instanceof String text) || text.isBlank()) {
                throw new StandTestException("Each entry in 'tags' must be a non-blank string, but found " + tag);
            }
            builder.tag(text);
        }

        List<GenericStep> steps = new ArrayList<>();
        appendSteps(steps, document.get("given"), "given");
        appendSteps(steps, document.get("then"), "then");
        if (steps.isEmpty()) {
            throw new StandTestException("A scenario must declare at least one step under 'given'/'then'");
        }
        builder.steps(steps);
        return builder.build();
    }

    /**
     * Parses a YAML scenario from a classpath resource.
     *
     * @param classpathResource the resource path (e.g. {@code "scenarios/flow.yaml"})
     * @return the built {@link Scenario}
     * @throws StandTestException if the resource is missing, unreadable or structurally invalid
     */
    public Scenario parseResource(String classpathResource) {
        Objects.requireNonNull(classpathResource, "classpathResource must not be null");
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new StandTestException("YAML resource not found on the classpath: " + classpathResource);
            }
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException readFailure) {
            throw new StandTestException("Failed to read YAML resource: " + classpathResource, readFailure);
        }
    }

    private static void appendSteps(List<GenericStep> steps, Object phaseNode, String phase) {
        if (phaseNode == null) {
            return;
        }
        List<Object> nodes = SurfaceValues.asList(phaseNode, phase);
        for (int index = 0; index < nodes.size(); index++) {
            steps.add(StepNodeTranslator.translate(nodes.get(index), phase, index));
        }
    }

    private static List<Object> tags(Map<String, Object> document) {
        Object tags = document.get("tags");
        return (tags == null) ? List.of() : SurfaceValues.asList(tags, "tags");
    }

    private static Object load(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        // Explicit, conservative limits: this format is aimed at AI-generated scenarios (plan §4), so
        // reject alias/nesting bombs at parse time rather than relying on library defaults.
        options.setMaxAliasesForCollections(10);
        options.setNestingDepthLimit(50);
        try {
            return new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException parseFailure) {
            throw new StandTestException("Failed to parse YAML: " + parseFailure.getMessage(), parseFailure);
        }
    }
}
