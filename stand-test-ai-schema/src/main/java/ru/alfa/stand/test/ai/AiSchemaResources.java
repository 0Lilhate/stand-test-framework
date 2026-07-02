package ru.alfa.stand.test.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Classpath access to the AI-guardrail resources shipped by this module: the declarative scenario
 * JSON Schema and the AI generation-rules document.
 *
 * <p>This is a JDK-only convenience loader — it does not execute scenarios, parse them, or perform any
 * IO beyond reading its own classpath resources. Consumers that only need the schema file may also read
 * {@link #SCHEMA_RESOURCE} directly.
 */
public final class AiSchemaResources {

    /** Classpath location of the declarative scenario JSON Schema. */
    public static final String SCHEMA_RESOURCE = "/schema/stand-test-scenario.schema.json";

    /** Classpath location of the AI generation-rules document. */
    public static final String GENERATION_RULES_RESOURCE = "/ai/stand-test-ai-generation-rules.md";

    private AiSchemaResources() {
    }

    /**
     * Reads the declarative scenario JSON Schema document.
     *
     * @return the JSON Schema content as a string
     */
    public static String scenarioSchemaJson() {
        return read(SCHEMA_RESOURCE);
    }

    /**
     * Reads the AI generation-rules document.
     *
     * @return the generation-rules content as a string
     */
    public static String generationRules() {
        return read(GENERATION_RULES_RESOURCE);
    }

    static String read(String resource) {
        InputStream in = AiSchemaResources.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Resource not found on classpath: " + resource);
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
