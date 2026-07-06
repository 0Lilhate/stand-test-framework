package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestException;

class EnvironmentReferenceResolverTest {

    @Test
    @DisplayName("a reference is resolved indirectly to its looked-up value")
    void referenceResolvesToValue() {
        EnvironmentReferenceResolver resolver = new EnvironmentReferenceResolver(reference -> "billing.stand.local:6565");

        assertThat(resolver.resolve("BILLING_GRPC_TARGET")).isEqualTo("billing.stand.local:6565");
    }

    @Test
    @DisplayName("a literal-wrapped value resolves verbatim without any environment lookup")
    void literalResolvesVerbatimWithoutLookup() {
        UnaryOperator<String> failingLookup = name -> {
            throw new AssertionError("lookup must not be called for a literal, but was called with '" + name + "'");
        };
        EnvironmentReferenceResolver resolver = new EnvironmentReferenceResolver(failingLookup);

        assertThat(resolver.resolve(SecretReferences.literal("127.0.0.1:18090"))).isEqualTo("127.0.0.1:18090");
    }

    @Test
    @DisplayName("an empty literal (unset variable behind ${VAR:}) fails lazily with a literal-specific message")
    void emptyLiteralFailsWithLiteralMessage() {
        EnvironmentReferenceResolver resolver = new EnvironmentReferenceResolver(reference -> null);

        assertThatThrownBy(() -> resolver.resolve(SecretReferences.literal("")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("literal value but it is empty")
                .hasMessageNotContaining("environment variable not set");
    }

    @Test
    @DisplayName("an unset reference still fails with the reference-specific message")
    void unsetReferenceFails() {
        EnvironmentReferenceResolver resolver = new EnvironmentReferenceResolver(reference -> null);

        assertThatThrownBy(() -> resolver.resolve("MISSING"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("did not resolve (environment variable not set)");
    }
}
