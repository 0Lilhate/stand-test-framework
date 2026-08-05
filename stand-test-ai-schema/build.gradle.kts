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
    // may still hold gitignored per-run state (.stand-test/), and folding that into the cache key
    // would make up-to-date checks differ per developer.
    //
    // The enforcement layer is declared too, and learning that it had to be cost a probe: with
    // settings.json missing from this list, editing it left the tests UP-TO-DATE, so a deliberately
    // broken permission policy passed the very test written to catch it. An undeclared input is not
    // a slower check — it is a check that silently does not run.
    // The WHOLE bundle, not a curated selection. The selection used to exclude machine-local files
    // so their presence would not make cache keys differ per developer — but BundleParityTest now
    // FORBIDS them outright, so such a file is a build failure rather than a local quirk, and
    // excluding it only meant the test that forbids it never re-ran when one appeared. Per-run hook
    // state is the one genuine exception.
    //
    // OpencodeConfigSafetyTest reads the shipped loader config, which BundleParityTest excludes by
    // design (opencode-only) and which holds no prompts — so nothing else looks at it, and that is
    // how three production database servers lived in it unnoticed.
    inputs.files(
        fileTree(rootDir.resolve("docs/ai-agent")) {
            include(".claude/**", ".opencode/**")
            exclude("**/.stand-test/**")
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

    // The installer and the manifest sit at the BUNDLE ROOT, outside both copies, so the tree above
    // does not cover them. KitManifestTest re-derives the manifest from the bundle and compares:
    // undeclared, an edit to either would leave that test UP-TO-DATE and a stale manifest would ship
    // — which is the same trap this file already warns about twice.
    inputs.files(
        rootDir.resolve("docs/ai-agent/MANIFEST.json"),
        rootDir.resolve("docs/ai-agent/install.mjs"),
    ).withPropertyName("standTestKitManifest")

    // EvaluationDatasetPatternsTest reads the corpus's forbidden-content patterns, which live outside
    // every tree declared above. Undeclared, a double-escaped pattern re-introduced tomorrow would
    // leave that test UP-TO-DATE and green — the same trap this file warns about twice already, and
    // exactly the shape of the defect the test exists to catch.
    // The whole of docs/agent-evaluation: the corpus AND the contract it is validated against.
    // Declaring only the dataset would leave an edited schema invisible — the test that proves the
    // README's "все 15 файлов проходят валидацию" would stay UP-TO-DATE and green while the rule it
    // checks had changed underneath it.
    inputs.dir(rootDir.resolve("docs/agent-evaluation")).withPropertyName("standTestEvaluationDataset")

    // TrainingMaterialLinksTest reads the kit's ROOT documents — the training materials. The bundle
    // tree above covers only `.claude/**` and `.opencode/**`, so without this a renamed skill file
    // would leave the test that checks the links UP-TO-DATE and the walkthrough pointing at nothing.
    inputs.files(
        rootDir.resolve("docs/ai-agent/example-ui-test-case-walkthrough.md"),
        rootDir.resolve("docs/ai-agent/usage-guide.md"),
    ).withPropertyName("standTestTrainingMaterials")

    // CiPipelineConfigTest reads the pipeline at the repository root. Undeclared, an edit that added
    // `|| true` to the Gradle invocation would leave the test that forbids it UP-TO-DATE and green —
    // and a pipeline is the one artefact whose failure mode is silence.
    inputs.file(rootDir.resolve(".gitlab-ci.yml")).withPropertyName("standTestCiPipeline")

    // UiLineDocumentHygieneTest reads every document of the UI line plus the BRD whose appendices the
    // conflict is about (UITG-F002). These documents are snapshots by nature: they are written once,
    // quoted for months and go stale silently when a module lands. That is exactly the case where an
    // undeclared input hurts most — the correction removed today would not turn the test red until
    // something unrelated invalidated the cache.
    inputs.dir(rootDir.resolve("docs/ui-test-generation")).withPropertyName("standTestUiLineDocuments")
    inputs.file(rootDir.resolve("docs/brd/ui-test-generation-brd.md")).withPropertyName("standTestUiLineBrd")
}
