---
name: stand-test-env-generation
description: Render environment configuration from the stand-test knowledge base into the REAL SDK formats - stand-test-environments.yml (plain JUnit, refs-only) or application.yml stand.test.environments.* (Spring starter, refs + optional non-secret value twins). Deterministic merge, never touches unrelated keys, never writes secret values, shows a diff before apply, re-validates YAML and alias coverage after. Use via /stand-test-generate-env.
version: 1
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

## EQ backends (`eq-backends`, registry format version 6)

When the environment's KB entry carries `eqBackends`, render it into the registry's `eq-backends`
section verbatim (field by field, no interpretation beyond the mapping below). The SDK parses the
section fail-closed, so an unknown field, a wrong type or a missing required value is caught by the
adapter's parser before any IO — but the renderer must not emit one in the first place.

| KB (`eqBackends[]`) | Registry (`eq-backends.<alias>`) |
|---|---|
| `alias` | map key under `eq-backends` |
| `kind`, `service`, `path` | `kind`, `service`, `path` |
| `writeAllowed` | `write-allowed` |
| `baseUrlRef` / `baseUrl` | `base-url-ref` / `base-url` (value twin; `*-ref` is the safe spelling) |
| `auth.scheme` + `usernameRef`/`passwordRef` (BASIC) or `tokenRef` (BEARER) | `auth: { scheme: BASIC, username-ref, password-ref }` / `auth: { scheme: BEARER, token-ref }` |
| `unit`, `branch`, `innRegionCode`, `innTaxOffices` | `unit`, `branch`, `inn-region-code`, `inn-tax-offices` |
| `cashAccounts.RUR` (…per `[A-Z]{3}`) | `cash-accounts.RUR` |
| `timeouts.connect/response` | `timeouts.connect/response` |
| `serialization.acquireTimeout` | `serialization.acquire-timeout` |
| `unitPhase.systemRef/usernameRef/passwordRef/allowed/cacheTtl` | `unit-phase.system-ref/username-ref/password-ref/allowed/cache-ttl` |
| `visibility.probe.endpointId` | resolve to `visibility.probe.service` (the endpoint's serviceId) + `visibility.probe.path` (the endpoint's path) |
| `visibility.probe.query/expectStatus/expectBody` | `visibility.probe.query/expect-status/expect-body` |
| `visibility.timeout/pollInterval` | `visibility.timeout/poll-interval` |
| `defaults.organisation.namePrefix/type` | `defaults.organisation.name-prefix/type` |
| `defaults.account.typeOrganisation/typeIndividual/currency/topUp/packageRegistration/packageDuration` | `defaults.account.type-organisation/type-individual/currency/top-up/package-registration/package-duration` |
| `defaults.individual.lastName/firstName/middleName/documentType/servicePackage` | `defaults.individual.last-name/first-name/middle-name/document-type/service-package` (physical-client inputs for the gateway `ONF`/`VAD`/`SPU` chain; `document-type` is `GZDUL`, `service-package` is `GZP3R`) |

**Value and ref spelling.** A KB `eqValue` is emitted literally when it is a scalar or a list, and as
`{ref: NAME}` (both surfaces — the file loader and the starter mapper both resolve it lazily) when the
KB gives `{ref: NAME}`. **Secrets are always refs**: `unitPhase` credentials and any `auth` credential
are emitted as `*-ref` bare NAMEs, never as values and never with a `${VAR:default}` default. A
non-secret value WITHOUT a default in the KB is emitted as `{ref: NAME}` (not `${VAR}`): on the
starter Spring would otherwise resolve `${VAR}` at binding time, before the backend is selected, and
require the test-stand variable even for an IFT run. A value WITH a known safe default may be a
`${VAR:default}` placeholder on either surface.

**The gateway `*-ref` fields accept a reference OR a value.** On the START surface Spring collapses
`${VAR:default}` before the SDK sees `eq-backends`, so `base-url-ref: ${EQ_GATEWAY_URL:http://…}` arrives
holding the URL and `unit-phase.system-ref: ${EQ_AS400_SYSTEM:alfamosu}` holding the system name; the EQ
parser accepts either a reference or a value there and uses a value verbatim (a bare NAME that is not set
also falls back to its own text as a value). So a non-secret endpoint/unit value MAY be written directly
in the file or as a `${VAR:default}` value twin. **Secrets must still stay bare NAMEs** — a credential as
a value would live in the configuration and in the Spring Environment.

**Endpoint resolution.** `visibility.probe.endpointId` is resolved through the same KB lookup the
skill already loads for services: a missing endpoint is a `Missing KB reference` (render error, the
`eq-backends` entry is not written), never a guessed `service`/`path`. `ui-applications` is still not
touched — the merge preserves it as before.

**Readiness check (before emitting).** Render the BRD Appendix В example and confirm: the section
passes the SDK's `EqBackendConfigParser` (unknown field / secret literal / missing required field
refused); the file and starter forms of the same KB entry yield an equal `EnvironmentSection` and an
equal `EqBackendConfig`; the section declares version 6 at the document root (the loader refuses
`eq-backends` in a document that declares an older version).

## Ref spelling per surface. On the plain-JUnit file surface a `*-ref` may be a bare env-var
NAME or a `${NAME}`/`${NAME:default}` placeholder (the SDK resolves it lazily). On the STARTER
surface refs are **bare NAMES only**: Spring resolves `${...}` during property binding, so a
placeholder inside a `*-ref` would materialise the variable's VALUE (for credentials — the
secret) into the registry before the SDK ever sees the reference.

**UI applications are out of this skill's mapping, and the reason is not an oversight.** The
`ui-applications` block (registry format version 2 and up) has no KB source to render from — the
knowledge base has no UI collections in this version of the kit — so there is nothing to map, and
inventing a mapping would produce configuration that no curated entry attests. A UI application's
alias, `base-url-ref` and `auth` block are written by a human and approved as a registry addition.

Two things to carry anyway, because this skill is where credentials get written down. First, an
application with exactly one account may name it directly with `auth.credentials-username` /
`auth.credentials-password` (format version 4, mutually exclusive with `credentials-pool-ref`) — if
you are asked to prepare such a block, the same rule as every other credential applies: **emit the
reference spelling, never the value.** Second, that pair accepts `${var:value}`, where the part after
the colon is a VALUE rather than the name of a fallback variable, so **a password is never given a
default**; the write hook refuses one (`SECRET_IN_SOURCE`, BLOCK) in the block-YAML form a registry is
actually written in.

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
   including the env-var names a runner must export and which of them have defaults (feeds the
   pre-run env check, and the choice for an optional `@EnabledIfEnvironmentVariable` gate — only a
   variable WITHOUT a default is a candidate).

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
