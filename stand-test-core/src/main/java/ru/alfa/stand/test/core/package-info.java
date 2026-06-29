/**
 * Stand test SDK — foundational core abstractions.
 *
 * <p>This module hosts only models, value objects, contracts and SPI for the stand-test SDK. It is
 * the root of the module dependency graph and depends on no sibling module and on no
 * adapter/IO library. There is deliberately no REST/Kafka/DB/gRPC/JUnit/Allure/YAML logic here.
 *
 * <p>The top-level package exposes the {@link ru.alfa.stand.test.core.StandClient} facade contract.
 * Supporting contracts live in dedicated sub-packages: {@code identifier}, {@code scenario},
 * {@code context}, {@code variable}, {@code validation}, {@code environment}, {@code execution},
 * {@code result}, {@code event} and {@code exception}.
 */
package ru.alfa.stand.test.core;
