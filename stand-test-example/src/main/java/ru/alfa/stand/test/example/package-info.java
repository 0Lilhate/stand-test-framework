/**
 * Stand test SDK — technical usage examples (Iteration 8).
 *
 * <p>This module has no production code: the examples live in {@code src/test} and demonstrate how a
 * consuming team writes scenarios with the SDK, executed through the public API against in-process test
 * doubles (JDK {@code HttpServer} for REST, H2 for DB) so they run green offline without a real DEV/IFT
 * stand. It is a pure consumer of the SDK modules and contains no business logic and no real-stand
 * configuration (plan §6/§7/§20).
 */
package ru.alfa.stand.test.example;
