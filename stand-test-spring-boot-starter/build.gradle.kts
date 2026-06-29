// stand-test-spring-boot-starter — aggregator / test-context wiring that will pull the core,
// junit, allure, await and adapter modules into a Spring test context.
//
// NOTE: this is a LIBRARY, not a bootable application — the `org.springframework.boot` plugin
// is deliberately NOT applied. No Spring Boot auto-configuration is implemented yet.
//
// Skeleton stage: no implementation, no internal dependencies.
// Shared Java / checkstyle / publishing configuration comes from the root `subprojects { }`.
//
// Planned internal dependencies (added later):
//   api(project(":stand-test-core"))
//   api(project(":stand-test-junit"))
//   api(project(":stand-test-allure"))
//   api(project(":stand-test-await"))
//   api(project(":stand-test-rest"))
//   api(project(":stand-test-kafka"))
//   api(project(":stand-test-db"))
//   api(project(":stand-test-grpc"))
