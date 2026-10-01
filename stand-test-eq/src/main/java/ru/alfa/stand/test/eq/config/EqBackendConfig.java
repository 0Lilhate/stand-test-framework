package ru.alfa.stand.test.eq.config;

/** Typed configuration of a selected EQ backend. */
public sealed interface EqBackendConfig permits ShowcasesBackendConfig, GatewayBackendConfig {

    String alias();

    VisibilityConfig visibility();
}
