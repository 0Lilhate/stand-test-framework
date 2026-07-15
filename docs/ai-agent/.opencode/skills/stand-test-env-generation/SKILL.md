---
name: stand-test-env-generation
description: Render environment configuration from the stand-test knowledge base into the REAL SDK formats - stand-test-environments.yml (plain JUnit, refs-only) or application.yml stand.test.environments.* (Spring starter, refs + optional non-secret value twins). Deterministic merge, never touches unrelated keys, never writes secret values, shows a diff before apply, re-validates YAML and alias coverage after. Use via /stand-test-generate-env.
---

# Skill: stand-test-env-generation

Render the KB's `environments/*.yml` entries into the SDK's registry configuration. The KB is the
input; **the SDK's registry schema is the output contract** — this skill never invents a third
format.

## Target surfaces (choose by consumer setup)

| Consumer | Target file | Tree | Rules |
|---|---|---|---|
| Plain JUnit + `stand-test-config` | `src/test/resources/stand-test-environments.yml` | `environments.<env>...` | **refs-only**: every endpoint/credential field is `*-ref`; value keys are rejected fail-closed by the loader |
| Spring Boot + starter | `src/test/resources/application.yml` (or `application-test.yml` if the project uses it) | `stand.test.environments.<env>...` | refs by default; NON-SECRET endpoint fields may use value twins (`base-url`/`url`/`target`/`bootstrap-servers`/`security-protocol`) with real `${ENV_VAR:}` placeholders, empty default kept so unset vars defer to a skipped run; exactly one twin per field. The starter also offers CREDENTIAL value twins (`user`/`password`/`username`/`token`/`sasl-jaas-config`), but **generated config never uses them** — this skill emits secrets in the `*-ref` spelling only (kit policy: a credential twin materialises the secret in the Spring Environment and has no `requireReferenceShape` guard; see the starter README tradeoff) |

## KB → registry mapping (fixed)

| KB (camelCase) | Registry (kebab-case) |
|---|---|
| `environments[].id` | `environments.<id>` |
| `services[].serviceId` | `services.<serviceId>` map key (service entries have no separate alias — the `id` IS the registry alias) |
| `services[].baseUrlRef` | `base-url-ref` |
| service entry `correlation.headerName` | `correlation: { source: HEADER, name: <headerName> }` |
| service entry `auth` (BASIC) | `auth: { scheme: BASIC, username-ref, password-ref }` |
| service entry `auth` (BEARER) | `auth: { scheme: BEARER, token-ref }` |
| `kafka.bootstrapServersRef` | `kafka-cluster.bootstrap-servers-ref` |
| `kafka.securityProtocolRef` | `kafka-cluster.security-protocol-ref` |
| `kafka.saslJaasConfigRef` | `kafka-cluster.sasl-jaas-config-ref` |
| `kafka.clusters[]` (`bootstrapServersRef`/`securityProtocolRef`/`saslJaasConfigRef`) | `kafka-clusters.<name>: { bootstrap-servers-ref, security-protocol-ref?, sasl-jaas-config-ref? }` |
| `kafka.topics[]` → topic entry `alias` + `actualName` + topic `correlation`/`cluster` | `topics.<alias>: { name: <actualName>, cluster?, correlation: {...} }` |
| `datasources[]` → datasource entry `alias`, `urlRef`/`userRef`/`passwordRef`, `allowedSchemas`, `access.mode` | `datasources.<alias>: { url-ref, user-ref, password-ref, allowed-schemas, write-allowed: <mode == write-allowed> }` |
| `grpcTargets[]` → target entry `alias` + `targetRef` + `correlation.metadataKey` | `grpc-targets.<alias>: { target-ref, correlation: { source: METADATA, name: <metadataKey> } }` |

\* the registry map key for topics/datasources/grpc-targets is the entity's `alias` from its KB
entry; for services it is the service `id` (services carry no alias field); the environment
binding always references the entity by `id`.

**Ref spelling per surface.** On the plain-JUnit file surface a `*-ref` may be a bare env-var
NAME or a `${NAME}`/`${NAME:default}` placeholder (the SDK resolves it lazily). On the STARTER
surface refs are **bare NAMES only**: Spring resolves `${...}` during property binding, so a
placeholder inside a `*-ref` would materialise the variable's VALUE (for credentials — the
secret) into the registry before the SDK ever sees the reference.

## Procedure

1. **Load the KB**: the requested environment entry plus every service/topic/datasource/target it
   binds. A binding whose entity is missing, or an entity in `allowedEnvironments` without a
   binding, goes to the report's `Missing KB references` — generation continues for the rest.
2. **Filter by modules** if requested (`rest,kafka,db,grpc`): emit only the corresponding blocks.
3. **Build the desired subtree** per the mapping table. Deterministic order: within the
   environment — `services`, `topics`, `datasources`, `grpc-targets`, `kafka-cluster`,
   `kafka-clusters`; aliases sorted lexicographically inside each map; fixed 2-space indent.
4. **Read the existing target file** (empty document if absent). Parse with safe YAML settings.
5. **Merge deterministically** — the managed subtree is `environments.<envId>` (or
   `stand.test.environments.<envId>`):
   - keys DERIVED FROM THE KB are set to the generated values;
   - keys inside the subtree NOT derivable from the KB (e.g. a hand-added service the KB does not
     know) are PRESERVED verbatim and listed in the report as unmanaged;
   - everything outside the subtree (Spring properties, logging, other environments) is never
     touched;
   - existing `${VAR:default}` inline defaults on NON-SECRET refs are preserved (they are
     someone's local DEV convenience); a default on a credential ref is a FINDING, not something
     to preserve silently.
6. **Show the diff** (unified, old→new) BEFORE writing. In `dry-run` mode stop here.
7. **Apply** via minimal textual edits so comments and unrelated formatting survive; whole-file
   generation is acceptable only for a file that is entirely SDK-managed (a fresh
   `stand-test-environments.yml` gets a `# Generated from stand-test-kb — edit the KB, not this file` header).
8. **Validate after writing**: file re-parses as YAML; every alias the KB binds for this
   environment is present; every `*-ref` matches the env-var shape; secret scan finds no values
   (`://`, `jdbc:`, `Bearer`/`Basic`, credential defaults); on the starter surface — no secret
   value twins and no twin/ref duplicates. Apply
   [`application-yml-generation-checklist.md`](application-yml-generation-checklist.md).
9. **Report** per [`env-generation-report-template.md`](env-generation-report-template.md),
   including the env-var names a runner must export (feeds the `@EnabledIfEnvironmentVariable`
   gate choice and the pre-run env check).

## Forbidden

- Applying without an explicit request: **dry-run is the default**; `apply` only when asked, and
  the diff is shown either way.
- Secret values or credential `${VAR:default}` defaults anywhere, on any surface; credential
  value twins (`user`/`password`/`username`/`token`/`sasl-jaas-config`) in generated config —
  secrets are emitted as `*-ref` only.
- Value twins for secrets, or on the plain-JUnit surface at all (the loader rejects them).
- Deleting or rewriting keys outside the managed subtree; reordering unrelated YAML.
- Inventing aliases/refs not present in the KB (a gap is a `Missing KB reference`, fix the KB).
- Production environments (schema-rejected in the KB; do not hand-author one here).
