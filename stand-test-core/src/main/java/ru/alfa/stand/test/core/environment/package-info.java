/**
 * Environment configuration contracts and value models.
 *
 * <p>These types describe, by logical alias, the services/topics/datasources/gRPC targets available
 * in an environment, together with secret <em>references</em> (never secret values) and datasource
 * schema whitelists. {@link ru.alfa.stand.test.core.environment.EnvironmentRegistry} resolves a
 * logical environment name to its {@link ru.alfa.stand.test.core.environment.EnvironmentDefinition}.
 * This iteration ships only models and contracts — no file/config parsing and no real endpoints.
 */
package ru.alfa.stand.test.core.environment;
