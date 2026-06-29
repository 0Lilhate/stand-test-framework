// stand-test-ai-schema — AI scenario-schema generation / validation scaffold. Generates the
// JSON Schema and the forbidden-operations list FROM the core scenario model / contracts
// (single source of truth), not from a separate list. No schema generation is implemented yet.
//
// Skeleton stage: no implementation, no internal dependencies.
// Shared Java / checkstyle / publishing configuration comes from the root `subprojects { }`.
//
// Planned internal dependencies (added later) — target graph: CORE MODEL ONLY.
// Must NOT depend on runtime/adapter modules and must NOT depend on stand-test-scenario-yaml
// (keeps the guardrail module runtime-free). See docs/arch §4 stand-test-ai-schema, §5, §8.6.
//   api(project(":stand-test-core"))
