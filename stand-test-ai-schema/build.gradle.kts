// stand-test-ai-schema — AI guardrails: a machine-readable JSON Schema of the allowed declarative
// scenario document plus an AI generation-rules catalogue, so an AI agent produces safe declarative
// tests rather than arbitrary Java. The published artifact is the schema RESOURCE
// (src/main/resources/schema) plus a JDK-only loader; there is NO runtime runner and NO adapter code.
//
// Dependency boundary (plan §4/§5/§8.6 — target graph: CORE MODEL ONLY):
//   - Main source is JDK-only (loads its own resources); it must NOT depend on runtime/adapter modules
//     (rest/kafka/db/grpc), junit, allure, spring-boot-starter, and must NOT depend on scenario-yaml.
//   - stand-test-core is used ONLY by the cross-check test (asserts the generation rules stay in sync
//     with core's ForbiddenOperation), so it is a `testImplementation` edge, not a compile edge.
//   - The JSON Schema validator (networknt) + Jackson exist ONLY to validate the example scenarios in
//     tests; they are `testImplementation` so Jackson never reaches the main graph (§Решение 4 of
//     docs/arch/stand-test-ai-schema-design.md).

dependencies {
    testImplementation(project(":stand-test-core"))
    testImplementation(libs.networknt.json.schema.validator)
    testImplementation(libs.jackson.databind)
    // SnakeYAML exists ONLY to load the knowledge-base example/negative YAML files in tests
    // (docs/ai-agent/knowledge-base contract); like networknt/jackson it never reaches main.
    testImplementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The KB validation test reads the contract from docs/ai-agent/knowledge-base (outside this
// module), so declare it as a test input — editing the schemas or examples re-runs the tests
// instead of hitting a stale FROM-CACHE result.
tasks.test {
    inputs.dir(rootDir.resolve("docs/ai-agent/knowledge-base")).withPropertyName("standTestKnowledgeBaseDir")
}
