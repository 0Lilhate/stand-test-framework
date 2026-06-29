// stand-test-scenario-yaml — YAML scenario engine scaffold (parsing scenario declarations
// into a generic Scenario Model). No YAML parser / DSL is implemented yet.
//
// Skeleton stage: no implementation, no internal dependencies.
// Shared Java / checkstyle / publishing configuration comes from the root `subprojects { }`.
//
// Planned internal dependencies (added later) — target graph: CORE ONLY.
// The YAML engine builds the generic Scenario Model and resolves Step Executors via the
// core StepExecutor SPI at runtime, so it has NO compile-time edges to the adapter modules
// (rest/kafka/db/grpc) and does NOT duplicate the runner. See docs/arch §5 and §8.5.
//   api(project(":stand-test-core"))
