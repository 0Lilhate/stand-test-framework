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

    // StepMatcherCapabilityCoverageTest scans the authoring bundle for stale matcher claims, so the
    // bundle is a test input too. Only the curated asset trees are declared: the bundle directories
    // also hold gitignored machine-local files (.env, settings.local.json, scheduled_tasks.lock), and
    // folding those into the cache key would make up-to-date checks differ per developer.
    inputs.files(
        fileTree(rootDir.resolve("docs/ai-agent")) {
            include(".claude/skills/**", ".claude/commands/**", ".claude/rules/**", ".claude/workflows/**")
            include(".opencode/skills/**", ".opencode/commands/**", ".opencode/rules/**", ".opencode/workflows/**", ".opencode/AGENTS.md")
        // OpencodeConfigSafetyTest reads the shipped loader config. It is excluded from
        // BundleParityTest by design (opencode-only) and holds no prompts, so nothing else looks
        // at it — which is how three production database servers lived in it unnoticed.
        include(".opencode/opencode.json")
        },
    ).withPropertyName("standTestAuthoringBundle")

    // ForbiddenOperationCoverageTest also scans the documents allowed to state how many
    // ForbiddenOperation codes exist. The two guardrails copies are already covered by the bundle
    // tree above; these three sit outside it, and without declaring them a wrong number typed into
    // CLAUDE.md would hide behind an UP-TO-DATE test.
    inputs.files(
        rootDir.resolve("CLAUDE.md"),
        rootDir.resolve("README.md"),
        rootDir.resolve("docs/ai-agent/README.md"),
    ).withPropertyName("standTestCountBearingDocs")
}
