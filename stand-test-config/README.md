# stand-test-config

**Group:** configuration · **Gradle plugin:** `java-library`

File-based loader for the SDK's **environment configuration**. It reads a declarative YAML file into an
immutable core `EnvironmentRegistry` and publishes it through the core `EnvironmentRegistry` SPI
(`META-INF/services`), so a **plain-JUnit** consumer's `StandTestExtension` — which resolves the registry
via `ServiceLoader` and otherwise falls back to an *empty* one — gets a **populated** registry with no
wiring code. This closes the "known gap" where alias resolution (service/topic/datasource/grpc) failed at
run time because the registry was empty.

**Internal dependencies:** `stand-test-core` (`api`) only. **External:** SnakeYAML (safe-loaded). No edges
to the adapter modules (rest/kafka/db/grpc), junit, allure, or the Spring starter.

## What it does

- `FileEnvironmentRegistry` — the SPI provider (`EnvironmentRegistry`). **Lazily** loads on first lookup
  and caches, so `ServiceLoader` discovery never does file IO.
- `YamlEnvironmentConfigLoader` — resolves the config source and parses it:
  - if the system property `stand.test.environments.config` is set, its value is a **filesystem path that
    must exist** (a missing explicit path is a `StandTestException`);
  - otherwise the classpath resource `stand-test-environments.yml` (or `.yaml`);
  - otherwise the familiar **`application.yml`** (or `.yaml`) — its `stand.test.environments` section
    (nested `stand: test: environments:` or dotted keys), the exact schema the Spring Boot starter binds,
    so one configuration style serves both worlds; an `application.yml` without that section contributes
    nothing;
  - if no source is present → an **empty registry** (behaviour unchanged; the strict validator still
    rejects unknown environments — configure one to run against a stand).
- `EnvironmentConfig` — the canonical `Map → EnvironmentRegistry` mapper (fail-closed: unknown keys,
  ill-typed values and invalid references are `StandTestException` with a dotted location).
- `SafeYaml` — SnakeYAML `SafeConstructor` + conservative alias/nesting limits (anti-YAML-bomb).

## Usage

Add the module (typically `testImplementation`) and drop a `stand-test-environments.yml` on the test
classpath (`src/test/resources`):

```yaml
version: 6                                        # registry FORMAT version (optional; absent means 1)
default-environment: ${APP_STEND:ift}             # optional; requires version 6
environments:
  ift:
    services:
      client-service:
        base-url-ref: CLIENT_SERVICE_URL           # env-var NAME, never the URL
        correlation: { source: HEADER, name: X-Correlation-Id }
        auth: { scheme: BASIC, username-ref: CLIENT_USER, password-ref: CLIENT_PASSWORD }
        # or: auth: { scheme: BEARER, token-ref: CLIENT_TOKEN }
    topics:
      response-topic: { name: pakt.response.ift, correlation: { source: HEADER, name: X-Correlation-Id } }
    datasources:
      main-db:
        url-ref: MAIN_DB_URL
        user-ref: MAIN_DB_USER
        password-ref: MAIN_DB_PASSWORD
        allowed-schemas: [test_data]
        write-allowed: true
    grpc-targets:
      billing-grpc: { target-ref: BILLING_GRPC_TARGET, correlation: { source: METADATA, name: x-correlation-id } }
    kafka-cluster:                                  # the DEFAULT cluster (topics without `cluster`)
      bootstrap-servers-ref: KAFKA_BOOTSTRAP
      security-protocol-ref: KAFKA_SECURITY_PROTOCOL
    kafka-clusters:                                 # additional NAMED clusters
      audit:
        bootstrap-servers-ref: AUDIT_KAFKA_BOOTSTRAP
    ui-applications:                                # requires version: 2 (auth.login: version: 3)
      client-portal:
        base-url-ref: CLIENT_PORTAL_IFT_URL         # env-var NAME, never the URL
        default-viewport: desktop                   # must name a declared profile
        viewport-profiles:
          desktop: { width: 1440, height: 900 }
          mobile: { width: 390, height: 844 }
        trace: off                                  # off (default) | on-failure
        auth:                                       # key `scheme`, as for services — never `type`
          scheme: FORM                              # NONE | FORM | STORAGE_STATE | SSO
          credentials-pool-ref: CLIENT_PORTAL_TEST_USERS   # env var holding the account ROSTER, not an account
          roles: [client, operator]                 # declared roles ⇒ ui.login must name one
          discovery-account-ref: CLIENT_PORTAL_DISCOVERY
          challenge: none                           # none | mfa | otp | captcha — declared, never bypassed
          login:                                    # required by FORM and STORAGE_STATE
            path: /login
            username-locator: testId=login-username
            password-locator: testId=login-password # a LOCATOR: a value without `<strategy>=` is refused
            submit-locator: "role=button:Sign in"
            signed-in-locator: testId=user-menu     # present only once signed in
```

`CLIENT_PORTAL_TEST_USERS` holds `portal-client-1:client;portal-operator-1:operator` — account ids,
roles and (optionally, as two further `:`-separated fields) the NAMES of the variables holding the
credentials. No login and no password exists at any level of this configuration.

## Format version (root key `version`)

`version` is the version of the **file format**, not of the SDK. It exists because this loader is
fail-closed: without it, a file carrying a section a newer SDK introduced would fail on an older SDK
with the unhelpful `Unknown field '<section>'`. The rules (shared with the Spring starter through
`EnvironmentConfigFormat` in `stand-test-core`, so the two surfaces cannot disagree):

| Declared | Behaviour |
|---|---|
| absent | read as `1` — every file written before versioning existed loads unchanged; warned about, because 1 is behind |
| < supported | read, plus one `WARN` per document load |
| = supported | read, silently |
| > supported | refused with a message naming the file's version, the supported one and the action (upgrade `stand-test-*`) |
| not a whole number, or ≤ 0 | configuration error (fail-closed) |

**There is no compatibility window** (ADR-UI-004). Every version from `1` up to the supported one is
read indefinitely, and this SDK does not acquire the right to stop reading a file because it lagged.
The `WARN` therefore reports a fact and says outright that the file stays readable — it is not notice
of a future refusal, and it must never be written as one: a warning that threatens a removal which
never comes teaches the reader to ignore warnings from this SDK, including the ones that matter. The
one refusal is the opposite case, a file *newer* than the SDK understands.

The warning is attached to the parse of the document — once per load, per surface — rather than to
alias resolution, so a scenario touching a dozen aliases does not print a dozen copies.

A section introduced after version 1 requires the document to declare at least the version it arrived
in — `ui-applications` requires `version: 2`, the `auth.login` / `auth.challenge` keys inside it
require `version: 3`, and `default-environment` plus `eq-backends` require `version: 6`. A field added to an existing section counts as a section for this purpose: an SDK
built before those keys existed greets them with `Unknown field 'login'`, which is exactly the case the
version key exists to replace.

**UI applications.** `ui-applications` whitelists the aliases a UI scenario may address; a scenario
names `client-portal`, and there is nowhere in a step to write a URL instead. Viewport, trace and
sign-in are configuration, so switching a run to the mobile viewport changes this file, not a test.
Note the YAML detail: unquoted `off` is the boolean `false` in YAML 1.1, which is accepted as the
`off` mode; `on` is refused — the modes are `off` and `on-failure`. The `ui.*` step types are shipped by
[stand-test-ui](../stand-test-ui/README.md), including `ui.login`, which is what reads the `auth`
section here.

**Multiple Kafka clusters:** the single `kafka-cluster` is the environment's default; `kafka-clusters`
whitelists named clusters, and a topic selects one via `cluster: <alias>` (e.g.
`audit: { name: ift.audit.v1, cluster: audit }`). A topic naming an undeclared cluster is rejected at
load time — the whitelist stays closed. Multiple services and datasources need nothing special: the
maps take any number of aliases.

No code is needed: `StandTestExtension` discovers `FileEnvironmentRegistry` via `ServiceLoader`. Point the
loader at another file with `-Dstand.test.environments.config=/path/to/envs.yml`.

## Placeholders: `${ENV_VAR}` and `${ENV_VAR:default}`

Every `*-ref` field accepts three spellings — a bare env-var `NAME`, `${NAME}`, and `${NAME:default}`.
The reference is stored verbatim and resolved by the adapters at the point of use; with
`${NAME:default}` the inline default applies only when the variable is **missing** (a variable set to an
empty value wins, matching Spring's semantics):

```yaml
    services:
      client-service:
        base-url-ref: ${CLIENT_SERVICE_URL:http://localhost:8080}   # env var wins when set
```

**Trade-off, stated plainly:** an inline default IS a value in the repository. Use defaults for
non-secret DEV endpoints; keep credentials as pure references — a default on `password-ref` puts a
password into VCS, and nothing will stop you but this sentence.

## Security: references, never values (plan §9)

Every address/secret field is a `*-ref` — the **name of an environment variable**, not the value (bare
URLs, JDBC connection strings, tokens and `Bearer`/`Basic` values are rejected fail-closed). The adapters
resolve references at run time (`System.getenv`), so secrets stay out of source and out of the config
file — unless you opt into an inline `${NAME:default}` (see above). Field names are kebab-case
(canonical); camelCase is also accepted. Service `auth` refs (`username-ref`/`password-ref`/`token-ref`)
follow the same rule; note the shape guard cannot recognise a *bare* token pasted as a "name" (it catches
whitespace, `://` and `Bearer `/`Basic ` prefixes) — the same residual trust applies to datasource
passwords today.

### The one thing this file cannot check: where a reference points

A `*-ref` is a variable NAME, so the SDK never sees the address behind it. **It therefore cannot tell a
DEV stand from production** — that is a property of the design, not an oversight, and the residual risk
is **accepted in writing** (decision of 2026-08-09, `UITG-S029`, requirement `SEC-01`).

What does hold, and holds by machine rather than by discipline:

- a UI scenario can only name an **alias**, and an alias absent from `ui-applications` is refused
  **before a browser starts** — `NON_WHITELISTED_UI_APPLICATION`, raised by the pre-flight validator;
- a literal URL is **not expressible** in a step at all, so no scenario can route itself anywhere;
- the environment vocabulary of the evaluation corpus is the enum `dev | ift`, whose own schema says
  "Production is not expressible".

So reaching production requires someone to point a whitelisted alias's variable at it deliberately.
**A blocklist of forbidden hosts was considered and rejected**: it would be maintained by hand, would
go stale, and a stale list gives false confidence — worse than a stated limit. What guards this instead
is the same thing that guards the credentials: the variable's value is set on the stand, by the people
who own it.

## Relationship to the Spring Boot starter

The Spring Boot starter builds its registry from `@ConfigurationProperties("stand.test")` and does **not**
need this module. This module is the non-Spring counterpart; the YAML shape here mirrors the Spring
`stand.test.environments.<env>...` tree (minus the `stand.test` prefix).

**This surface is refs-only by design.** The starter's endpoint value twins (`base-url`, `url`,
`target`, `bootstrap-servers`, `security-protocol` with real Spring `${VAR:}` placeholders) do NOT
exist here: there is no Spring resolver when this file is loaded, and a committable YAML file must
never carry resolved endpoints. A value field in `stand-test-environments.yml` is rejected
fail-closed as an unknown key.

**Known limitation (drift risk):** the mapping lives in two places — this module's `EnvironmentConfig`
(from a YAML map) and the starter's `EnvironmentRegistryFactory` (from Spring POJOs). They share one
logical schema but are not a single implementation; keep them in sync. Unifying them (e.g. the starter
delegating to `EnvironmentConfig`) is tracked future work.

## Testing

Unit tests cover the mapper (every resource type → references; unknown key / blank ref / bad correlation
source → `StandTestException` with location; camelCase aliasing), the loader (default resource present /
absent, explicit path present / missing / blank) and the SPI provider (`ServiceLoader` discovery + lazy,
cached loading). No real stand, no `Thread.sleep`. Instruction coverage ≥ 80% (JaCoCo).
