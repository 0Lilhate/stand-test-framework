package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;

/**
 * Cross-check pinning the schema's assertion grammar to the runtime {@link AssertionMatcher} enum, the
 * same way {@code ForbiddenOperationCoverageTest} pins the rules table to {@code ForbiddenOperation}:
 * adding a matcher to either side without the other fails here, so the schema can never advertise a
 * matcher the runtime cannot execute (or vice versa) unnoticed.
 */
class AssertionMatcherCoverageTest {

    @Test
    @DisplayName("the schema's assertion matcher keys and the core AssertionMatcher enum are the same set")
    void schemaMatchers_matchRuntimeEnum() throws IOException {
        JsonNode schema;
        try (InputStream in = AssertionMatcherCoverageTest.class.getResourceAsStream(AiSchemaResources.SCHEMA_RESOURCE)) {
            schema = new ObjectMapper().readTree(in);
        }
        JsonNode properties = schema.path("$defs").path("assertion").path("properties");
        assertThat(properties.isObject()).as("$defs.assertion.properties must exist").isTrue();

        List<String> schemaMatchers = new ArrayList<>();
        properties.fieldNames().forEachRemaining(name -> {
            if (!"path".equals(name)) {
                schemaMatchers.add(toEnumName(name));
            }
        });

        List<String> runtimeMatchers = new ArrayList<>();
        for (AssertionMatcher matcher : AssertionMatcher.values()) {
            runtimeMatchers.add(matcher.name());
        }

        assertThat(schemaMatchers).containsExactlyInAnyOrderElementsOf(runtimeMatchers);
    }

    private static String toEnumName(String surfaceKey) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < surfaceKey.length(); i++) {
            char symbol = surfaceKey.charAt(i);
            if (Character.isUpperCase(symbol)) {
                name.append('_');
            }
            name.append(Character.toUpperCase(symbol));
        }
        return name.toString().toUpperCase(Locale.ROOT);
    }
}
