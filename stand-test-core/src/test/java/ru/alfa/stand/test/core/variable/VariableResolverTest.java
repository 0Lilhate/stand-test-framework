package ru.alfa.stand.test.core.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.identifier.ScenarioId;

class VariableResolverTest {

    private VariableResolver resolverFor(VariableStore store) {
        ScenarioContext context = ScenarioContext.start(ScenarioId.of("flow"), "ift");
        return new VariableResolver(context, store);
    }

    @Test
    @DisplayName("resolves user variables and built-in metadata")
    void resolve_userAndBuiltins() {
        VariableStore store = new VariableStore();
        store.put("requestId", "42");
        VariableResolver resolver = resolverFor(store);

        String resolved = resolver.resolve("id=${requestId} env=${environment} sc=${scenarioId}");

        assertThat(resolved).isEqualTo("id=42 env=ift sc=flow");
    }

    @Test
    @DisplayName("built-in names take precedence over stored variables")
    void resolve_builtinsTakePrecedence() {
        VariableStore store = new VariableStore();
        store.put("environment", "SHOULD_NOT_WIN");
        VariableResolver resolver = resolverFor(store);

        assertThat(resolver.resolve("${environment}")).isEqualTo("ift");
    }

    @Test
    @DisplayName("an unknown variable fails with a clear SDK error")
    void resolve_unknownVariable_throws() {
        VariableResolver resolver = resolverFor(new VariableStore());

        assertThatThrownBy(() -> resolver.resolve("${missing}"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("a null template resolves to null and text without placeholders is unchanged")
    void resolve_nullAndPlainText() {
        VariableResolver resolver = resolverFor(new VariableStore());

        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve("no placeholders here")).isEqualTo("no placeholders here");
    }

    @Test
    @DisplayName("resolves the testRunId and correlationId built-ins from the context")
    void resolve_runAndCorrelationBuiltins() {
        ScenarioContext context = ScenarioContext.start(ScenarioId.of("flow"), "ift");
        VariableResolver resolver = new VariableResolver(context, new VariableStore());

        assertThat(resolver.resolve("${testRunId}")).isEqualTo(context.testRunId().value());
        assertThat(resolver.resolve("${correlationId}")).isEqualTo(context.correlationId().value());
    }

    @Test
    @DisplayName("resolves adjacent placeholders and coerces non-String values")
    void resolve_adjacentAndCoercion() {
        VariableStore store = new VariableStore();
        store.put("a", "X");
        store.put("count", 7);
        VariableResolver resolver = resolverFor(store);

        assertThat(resolver.resolve("${a}${count}")).isEqualTo("X7");
    }

    @Test
    @DisplayName("a literal dollar and a dangling brace are left untouched")
    void resolve_literalDollarAndDanglingBrace() {
        VariableResolver resolver = resolverFor(new VariableStore());

        assertThat(resolver.resolve("price is $5.00")).isEqualTo("price is $5.00");
        assertThat(resolver.resolve("dangling ${name")).isEqualTo("dangling ${name");
        assertThat(resolver.resolve("empty ${}")).isEqualTo("empty ${}");
    }

    @Test
    @DisplayName("a value containing $ survives substitution literally")
    void resolve_valueWithDollarIsLiteral() {
        VariableStore store = new VariableStore();
        store.put("v", "a$1b");
        VariableResolver resolver = resolverFor(store);

        assertThat(resolver.resolve("<${v}>")).isEqualTo("<a$1b>");
    }

    @Test
    @DisplayName("a blank placeholder fails with a StandTestException, not a leaked IllegalArgumentException")
    void resolve_blankPlaceholder_throwsStandTestException() {
        VariableResolver resolver = resolverFor(new VariableStore());

        assertThatThrownBy(() -> resolver.resolve("${ }"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("placeholder");
    }
}
