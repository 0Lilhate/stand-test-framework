package ru.alfa.stand.test.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        Map<String, Object> document = SurfaceValues.asMap(SafeYaml.load(yaml), "<document>");
        SurfaceValues.checkKnownKeys(document, KNOWN_TOP_LEVEL, "<document>");

        var builder = ScenarioDocuments.startBuilder(document, "env");
        List<GenericStep> steps = new ArrayList<>();
        steps.addAll(phaseSteps(document.get("given"), "given"));
        steps.addAll(phaseSteps(document.get("then"), "then"));
        ScenarioDocuments.requireAtLeastOneStep(steps, "'given'/'then'");
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

    private static List<GenericStep> phaseSteps(Object phaseNode, String phase) {
        if (phaseNode == null) {
            return List.of();
        }
        return ScenarioDocuments.translateSteps(
                SurfaceValues.asList(phaseNode, phase), (node, index) -> StepNodeTranslator.translate(node, phase, index));
    }
}
