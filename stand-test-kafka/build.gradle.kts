// stand-test-kafka — Kafka adapter scaffold. No producer / consumer implementation yet.
//
// Skeleton stage: no implementation, no internal dependencies.
// Shared Java / checkstyle / publishing configuration comes from the root `subprojects { }`.
//
// Planned internal dependencies (added later):
//   api(project(":stand-test-core"))
//   implementation(project(":stand-test-await"))
//
// Planned external dependencies (added later): kafka-clients (Apache, raw — for assign/seek control)
// and json-path (JSONPath; message value read as a string, no Jackson required). The SDK never ships
// its own Kafka client (plan §4, §20). Tests use the Apache MockConsumer/MockProducer (no broker).
