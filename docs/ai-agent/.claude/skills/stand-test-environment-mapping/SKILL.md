---
name: stand-test-environment-mapping
description: Map systems named in a text case onto logical aliases of the stand-test environment registry (stand-test-environments.yml or stand.test.environments.*), verify correlation/auth/write-allowed properties, and report missing aliases and required env vars. Use right after stand-test-case-analysis.
---

# Skill: stand-test-environment-mapping

Map the systems named in a text case onto the **logical aliases** of the consumer project's
environment registry, and report what is missing. The registry is the whitelist enforcement
point of the SDK — a scenario can only reach what the registry declares.

## When to use

Right after `stand-test-case-analysis`, before scenario design.

## Where the registry lives

| Consumer setup | Registry location | Loaded by |
|---|---|---|
| Plain JUnit + `stand-test-config` | `src/test/resources/stand-test-environments.yml` (or `-Dstand.test.environments.config=<path>`; fallback: `application.yml` section `stand.test.environments`) | `ru.alfa.stand.test.config.FileEnvironmentRegistry` (ServiceLoader SPI) |
| Spring Boot + starter | `application.yml` under `stand.test.environments.*` | `StandTestAutoConfiguration` / `EnvironmentRegistryFactory` |

Same conceptual shape in both; kebab-case keys; **every `*-ref` field is an ENV-VAR NAME**
(or `${NAME}` / `${NAME:default}` placeholder), never a URL or secret value — a value-shaped
ref fails fail-closed (`SecretReferences.requireReferenceShape` rejects whitespace, `://`,
`Bearer `/`Basic ` prefixes).

**Starter-only endpoint value twins.** On the Spring surface, non-secret endpoint fields may
instead use value twins resolved by Spring at context startup — real `${ENV_VAR:}` placeholders:
`base-url` (service), `url` (datasource), `target` (gRPC), `bootstrap-servers` /
`security-protocol` (Kafka cluster). Rules: exactly one twin per field (both = startup failure);
always keep the `:` empty-default so an unset variable defers the failure to execution (gated
tests still SKIP); values must be `${ENV_VAR:...}` placeholders — a hardcoded URL in a value
field is a review finding even though Spring would accept it. Secrets (auth refs, datasource
user/password, SASL) have NO value twins on any surface, and the plain-JUnit
`stand-test-environments.yml` stays refs-only (value keys are rejected as unknown fields).

```yaml
environments:
  ift:
    services:
      order-service:
        base-url-ref: ORDER_SERVICE_URL
        correlation: { source: HEADER, name: X-Correlation-Id }
        auth: { scheme: BASIC, username-ref: ORDER_USER, password-ref: ORDER_PASSWORD }
    topics:
      order-events: { name: real.topic.name.v1, correlation: { source: HEADER, name: X-Correlation-Id } }
    datasources:
      orders-db:
        url-ref: ORDERS_DB_URL
        user-ref: ORDERS_DB_USER
        password-ref: ORDERS_DB_PASSWORD
        allowed-schemas: [test_data]
        write-allowed: true
    grpc-targets:
      billing-grpc: { target-ref: BILLING_GRPC_TARGET, correlation: { source: METADATA, name: x-correlation-id } }
    kafka-cluster: { bootstrap-servers-ref: KAFKA_BOOTSTRAP }
```

## What to produce

An **environment mapping report** with four tables:

1. **Resolved aliases** — case entity → alias → registry evidence, including the properties
   downstream skills depend on:
   - service: has `correlation:`? (needed for `injectCorrelationId`) has `auth:`?
   - topic: has HEADER `correlation:`? (Kafka is HEADER-only; `KEY`/`PAYLOAD_FIELD` carriers
     are not implemented and throw at run time) which cluster?
   - datasource: `write-allowed`? `allowed-schemas` list? (seed/cleanup impossible otherwise)
   - grpc target: `correlation.source == METADATA`? (required for inject)
2. **Missing aliases** — entities named by the case with no registry entry. For each, propose
   the registry block to add (with env-var **names** only, e.g. `PAYMENT_SERVICE_URL`) — this
   is a config change a HUMAN must approve and apply; the agent does not invent endpoints.
3. **Forbidden directs** — any URL/host/port/JDBC/bootstrap string found in the case text that
   someone might be tempted to paste into a test. Explicitly mark: goes into an env var behind
   a `*-ref`, never into a scenario.
4. **Env vars required to run** — the union of `*-ref` names the scenario will need at
   execution time (drives the `@EnabledIfEnvironmentVariable` gate choice).

## Failure-mode knowledge (encode in the report when relevant)

- Unknown **environment** or **datasource** → rejected pre-flight by the validator
  (`NON_WHITELISTED_ENVIRONMENT` / `NON_WHITELISTED_DATASOURCE`).
- Unknown **service/topic/grpc-target** → fails only at step execution
  (`StandTestException "... is not whitelisted"`), not pre-flight.
- Registry file missing while `stand-test-config` is on the classpath → **silent empty
  registry** → misleading `Environment '...' is not whitelisted` at first run. (The loud
  "No EnvironmentRegistry provider" diagnostic appears only when no provider exists at all.)
- Env var unset at run time → `StandTestException "... did not resolve"` naming the ref.

## Forbidden

- Inventing alias names not grounded in the registry or the proposed-additions table.
- Putting URLs, hosts, credentials or real topic names into scenario/test artifacts (real
  topic names belong only in the registry's `name:` field).
- Adding a second `EnvironmentRegistry` SPI provider (exactly one is allowed on the classpath).
- Declaring a production environment in a test registry.

## Checklist

- [ ] Every transport step in the analysis has a resolved alias or a missing-alias entry.
- [ ] Correlation capability verified per alias for every planned inject/fromContext.
- [ ] Write intent (seed/cleanup) verified against `write-allowed` + `allowed-schemas`.
- [ ] Env-var list for the run gate produced.
- [ ] No secrets/URLs anywhere in the report's proposed config (refs only).
