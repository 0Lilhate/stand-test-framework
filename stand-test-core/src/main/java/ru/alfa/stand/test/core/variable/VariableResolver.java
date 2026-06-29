package ru.alfa.stand.test.core.variable;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Resolves simple {@code ${name}} placeholders in strings against built-in metadata and a
 * {@link VariableStore}.
 *
 * <p>Supported built-in names are {@code scenarioId}, {@code testRunId}, {@code correlationId} and
 * {@code environment}; these are reserved and take precedence over stored variables. Any other name
 * is looked up in the variable store. This is intentionally not an expression language: it does not
 * evaluate code, call functions or support nested expressions. An unknown name fails with a clear
 * SDK error.
 */
public final class VariableResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final ScenarioContext context;
    private final VariableStore variableStore;

    /**
     * Creates a resolver bound to a context and a variable store.
     *
     * @param context the scenario context providing built-in values
     * @param variableStore the per-run variable store
     */
    public VariableResolver(ScenarioContext context, VariableStore variableStore) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.variableStore = Objects.requireNonNull(variableStore, "variableStore must not be null");
    }

    /**
     * Resolves all {@code ${name}} placeholders in the given template.
     *
     * @param template the template string, possibly null
     * @return the resolved string, or null if the template was null
     * @throws StandTestException if a referenced variable cannot be resolved
     */
    public String resolve(String template) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolveName(name)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolveName(String name) {
        if (name.isBlank()) {
            throw new StandTestException("Malformed placeholder: blank variable name in '${...}'");
        }
        return switch (name) {
            case "scenarioId" -> context.scenarioId().value();
            case "testRunId" -> context.testRunId().value();
            case "correlationId" -> context.correlationId().value();
            case "environment" -> context.environment();
            default -> resolveUserVariable(name);
        };
    }

    private String resolveUserVariable(String name) {
        return variableStore.get(name)
                .map(String::valueOf)
                .orElseThrow(() -> new StandTestException("Unresolved variable: '${" + name + "}'"));
    }
}
