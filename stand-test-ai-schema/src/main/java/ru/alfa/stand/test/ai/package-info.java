/**
 * Stand test SDK — AI scenario schema (guardrails).
 *
 * <p>Ships the declarative scenario JSON Schema and the AI generation-rules document as classpath
 * resources, plus a JDK-only loader ({@link ru.alfa.stand.test.ai.AiSchemaResources}). This module is a
 * static guardrail layer: it constrains what an AI agent may generate. It does not execute or parse
 * scenarios and has no compile-time edge to the runtime/adapter modules.
 */
package ru.alfa.stand.test.ai;
