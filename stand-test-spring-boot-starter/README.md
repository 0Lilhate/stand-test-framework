# stand-test-spring-boot-starter

**Group:** integrations · **Gradle plugin:** `java-library`

Spring Boot auto-configuration for the stand-test SDK. Drop it on a Spring Boot **test** project's
classpath and an `@Autowired StandClient` (plus the runner, validator, environment registry, reporting
publisher and the adapter step executors) becomes available — the same object graph
`StandTestExtension.buildStandClient()` assembles for JUnit, but wired through the Spring context and
driven by `application.yml` instead of the `ServiceLoader`.

This is a **library**, not a bootable application — the `org.springframework.boot` plugin is
deliberately **not** applied.

## What it auto-configures

Every bean below is `@ConditionalOnMissingBean` (declare your own to override) and the whole
configuration is gated by `stand.test.enabled` (default `true`).

| Bean | Condition | Notes |
|------|-----------|-------|
| `ScenarioValidator` | always | `DefaultScenarioValidator` (structural + guardrail checks) |
| `EnvironmentRegistry` | always | built from `stand.test.environments.*` (empty when none) |
| `RestStepExecutor` | `stand-test-rest` on classpath | collected into the runner |
| `KafkaStepExecutor` | `stand-test-kafka` on classpath | collected into the runner |
| `DbStepExecutor` | `stand-test-db` on classpath | collected into the runner |
| `GrpcStepExecutor` | `stand-test-grpc` on classpath | collected into the runner |
| `ReportingEventPublisher` | Allure on classpath & `reporting.allure.enabled` | else the no-op publisher |
| `ScenarioRunner` | always | `DefaultScenarioRunner` over all `StepExecutor` beans **and over the executors discovered on the classpath** — see below |
| `StandClient` | always | `DefaultStandClient` facade |
| `Awaiter` | `stand-test-await` on classpath | fresh system-backed awaiter |
| `AwaitPolicy` | `stand-test-await` on classpath | reusable default from `stand.test.await.*` |

## Adapters discovered on the classpath

An adapter that registers a `StepExecutor` through `META-INF/services` is picked up **without a bean of
its own**. `stand-test-ui` is the one that prompted this: it ships an SPI registration and no bean, so
before ADR-UI-008 a Spring consumer with the UI module on the classpath met

```
StandTestException: No step executor registered for step type 'ui.open' (stepId=…)
```

while the same scenario ran fine on plain JUnit, whose extension has always loaded executors through the
SPI. The two paths now agree; nothing needs declaring.

**A declared bean always wins.** If you declare your own executor for a step type, it takes precedence
over anything discovered — beans are placed first, and the runner uses the first executor that supports
the type. An SPI provider of a class you already declare as a bean is skipped outright, so the common
case (the starter declares `RestStepExecutor` while `stand-test-rest` also registers it) yields one
instance, not two.

The rule is general, not UI-specific: an adapter you build yourself is discovered the same way.

Discovery announces itself once at context start, naming the executor classes it added:

```
INFO  … Step executors discovered on the classpath (no bean declared): [ru.alfa.stand.test.ui.UiStepExecutor].
      A declared bean always takes precedence over a discovered executor.
```

Two consequences worth knowing. Discovery uses the **context's** class loader, so an adapter you
deliberately keep off the classpath is not brought back. And a service entry naming a class that will
not load is a `WARN`, not a failure — one stale entry in an unrelated jar must not stop your
application context; steps of that type then report the usual "no executor registered".

## What it does **not** do

The starter is an integration module only. It contains **no** transport or business logic and never
depends back on the modules it wires. It does not implement REST/Kafka/DB/gRPC clients, the JUnit
extension, the Allure mapping, the YAML parser or the AI schema — those live in their own modules; the
starter merely collects their existing beans. It ships no real stand config, no endpoints and no
secrets: only `*Ref` **names** that the adapters resolve (from the OS environment) at run time.

## Add the dependency

The starter forces **only `stand-test-core`**. Every adapter/reporting/await module is an *optional*
(`compileOnly`) dependency — it is **not** pulled transitively — so you add just the ones you use and
keep heavy transitive deps (spring-webflux, kafka-clients, allure-java-commons) off your classpath
unless you asked for them:

```kotlin
testImplementation("ru.alfa.stand.test:stand-test-spring-boot-starter:<version>")

// add only what this test project actually exercises:
testImplementation("ru.alfa.stand.test:stand-test-rest:<version>")     // → RestStepExecutor bean
testImplementation("ru.alfa.stand.test:stand-test-db:<version>")       // → DbStepExecutor bean
testImplementation("ru.alfa.stand.test:stand-test-allure:<version>")   // → Allure reporting publisher
testImplementation("ru.alfa.stand.test:stand-test-await:<version>")    // → Awaiter / AwaitPolicy beans
```

Each corresponding bean appears **only** when its module is on the classpath (`@ConditionalOnClass`); a
module you don't add simply contributes nothing. With no adapters at all the `StandClient`/runner still
wire, just with an empty executor list.

## Example `application-test.yml`

```yaml
stand:
  test:
    enabled: true
    version: 2                # registry FORMAT version (not the SDK version); absent means 1
    await:
      timeout: 30s
      poll-interval: 500ms
    reporting:
      enabled: true
      allure:
        enabled: true
    environments:
      ift:
        ui-applications:      # requires version: 2 (auth.login: version: 3; the direct credential pair: version: 4,
                              # its *-ref twins: version: 5)
          client-portal:
            base-url-ref: CLIENT_PORTAL_IFT_URL
            default-viewport: desktop
            viewport-profiles:
              desktop: { width: 1440, height: 900 }
              mobile: { width: 390, height: 844 }
            trace: "off"      # quote it: unquoted `off` is the YAML boolean false (accepted, but quoting says what you mean)
            auth:             # key `scheme`, as for services; the pool and the discovery account are refs only
              scheme: FORM
              credentials-pool-ref: CLIENT_PORTAL_TEST_USERS   # env var holding the account ROSTER
              # An application with exactly ONE account names it instead (version: 4). The pair excludes
              # credentials-pool-ref and answers every declared role. From version 5 it is a VALUE TWIN,
              # so on this surface prefer the *-ref spelling for a password:
              #   credentials-username-ref: CLIENT_PORTAL_USERNAME   # the NAME of the variable
              #   credentials-password-ref: CLIENT_PORTAL_PASSWORD
              # …or, when the value itself must come from Spring:
              #   credentials-username: ${PORTAL_USER}               # the VALUE (version: 5)
              roles: [client, operator]                        # declared roles ⇒ ui.login must name one
              discovery-account-ref: CLIENT_PORTAL_DISCOVERY
              challenge: none                                  # none | mfa | otp | captcha
              login:                                           # required by FORM and STORAGE_STATE
                path: /login
                username-locator: testId=login-username
                password-locator: testId=login-password        # a LOCATOR, not a credential
                submit-locator: "role=button:Sign in"
                signed-in-locator: testId=user-menu
        services:
          client-service:
            base-url-ref: CLIENT_SERVICE_URL
            correlation: { source: HEADER, name: X-Correlation-Id }
            auth: { scheme: BASIC, username-ref: CLIENT_USER, password-ref: CLIENT_PASSWORD }
        datasources:
          main-db:
            url-ref: MAIN_DB_URL
            user-ref: MAIN_DB_USER
            password-ref: MAIN_DB_PASSWORD
            allowed-schemas: [test_data]
            write-allowed: true
        topics:
          events:
            name: ift.events.v1
            correlation: { source: KEY, name: X-Correlation-Id }
        grpc-targets:
          accounts:
            target-ref: ACCOUNTS_GRPC
        kafka-cluster:
          bootstrap-servers-ref: KAFKA_BOOTSTRAP
```

> **The `*Ref` values are environment-variable names, never values.** `CLIENT_SERVICE_URL`,
> `MAIN_DB_PASSWORD`, `KAFKA_BOOTSTRAP` etc. are resolved by the adapters from the OS environment at
> execution time — the SDK keeps endpoints and secrets out of source.

## Registry format version (`stand.test.version`)

The version of the environment-registry **format**, not of the SDK — the exact mirror of the root
`version` key in `stand-test-environments.yml`, decided by the same core constant
(`EnvironmentConfigFormat`), so both surfaces read and refuse the same documents:

| Declared | Behaviour |
|---|---|
| absent | bound as `1`; an existing `application.yml` keeps working unchanged |
| ≤ supported | bound |
| > supported | context startup fails with a message naming the declared version, the supported one and the action (upgrade `stand-test-*`) — never `Unknown field` |

Sections introduced after version 1 must declare the version they arrived in — without that rule a
configuration could carry a version-2 section while claiming version 1, the very case the version key
exists to diagnose:

| Section / field | Requires |
|---|---|
| `ui-applications` | `stand.test.version: 2` |
| `auth.login` / `auth.challenge` inside it | `stand.test.version: 3` |
| `auth.credentials-username` / `auth.credentials-password` | `stand.test.version: 4` |
| `auth.credentials-username-ref` / `auth.credentials-password-ref` | `stand.test.version: 5` |

**UI applications** are addressed by alias exactly like services and topics; a scenario names
`client-portal` and no step can carry a URL. `base-url` / `base-url-ref` behave like every other
endpoint twin. The account **pool** (`credentials-pool-ref`) and the discovery account
(`discovery-account-ref`) are references only — deliberately no value twin, so a roster cannot be routed
through (and left in) the Spring `Environment`. The single-account pair is the exception, and what it
means depends on the declared format version — see the next section. The placeholder trap applies to
every `*-ref` here as everywhere: never write `${VAR}` inside one — Spring collapses it before the SDK
sees it, and the resolved text is then read as a variable name.

## Endpoint & credential values via Spring placeholders

Every field in the table below has a value twin that Spring resolves at context startup, so real
`${VAR}` / `${VAR:default}` placeholders work uniformly. The table is exhaustive — a field absent from
it has no twin, and writing the value spelling for it binds nothing (see the note under the table):

| Value field (Spring-resolved) | `*-ref` twin (lazy, adapter-resolved) | Resource |
|---|---|---|
| `base-url` | `base-url-ref` | service, **ui application** |
| `url` | `url-ref` | datasource |
| `target` | `target-ref` | gRPC target |
| `bootstrap-servers` | `bootstrap-servers-ref` | Kafka cluster |
| `security-protocol` | `security-protocol-ref` | Kafka cluster |
| `user` | `user-ref` | datasource (**secret**) |
| `password` | `password-ref` | datasource / auth (**secret**) |
| `username` | `username-ref` | auth (**secret**) |
| `token` | `token-ref` | auth (**secret**) |
| `sasl-jaas-config` | `sasl-jaas-config-ref` | Kafka cluster (**secret**) |
| `credentials-username` | `credentials-username-ref` | ui application, **from version 5** (**secret**) |
| `credentials-password` | `credentials-password-ref` | ui application, **from version 5** (**secret**) |

> **The UI single-account pair changed meaning in version 5 — read this before writing it.**
>
> | Declared version | `credentials-username` holds | `credentials-username-ref` |
> |---|---|---|
> | ≤ 4 | the NAME of an environment variable (a reference) | rejected — requires version 5 |
> | ≥ 5 | the VALUE itself, Spring-resolved like `base-url` | the NAME of the variable holding it |
>
> A version-4 document keeps its old meaning forever, so nothing that works today breaks. The flip exists
> because on this surface a `${VAR:default}` placeholder is resolved *before* the SDK sees the field, so a
> value and a variable name arrive as the same string and cannot share one key: under version 4 the
> spelling that worked in `stand-test-environments.yml` failed here with `variable 'tks_Admin' is not set`.
>
> **The flip's one silent failure is closed by refusing rather than guessing.** On a version-5 document a
> value shaped like a bare variable name (`^[A-Z][A-Z0-9_]{2,63}$`) is rejected, pointing at the `*-ref`
> spelling — signing in as the literal string `PORTAL_USERNAME` would otherwise cost a debugging session.
> Setting both twins fails the same way every other pair does: *configure exactly one*.
>
> **The cost is the usual one, and for a password it is real.** A value routed through a twin lives in the
> Spring `Environment` for the life of the context (actuator `/env`, a heap dump, a context report), and a
> default written into the file stays in git after rotation. So `*-ref` remains the right spelling for a
> password — the kit's safety gate treats a password value in a registry document as a blocking finding —
> while a login through the twin is usually acceptable. Never give a password a default.
>
> **The pool and the discovery account have no twin at all.**
> `ui-applications.<alias>.auth.credentials-pool-ref` and `discovery-account-ref` are references only;
> there is no `credentials-pool` / `discovery-account` setter, so writing one binds nothing silently.
>
> The `auth.login.*-locator`
> fields are not references either: they address elements on the page, are required whenever the scheme
> signs in, and are rejected unless spelled `<strategy>=<value>` — which is also what stops a credential
> from being typed into `password-locator`.

```yaml
stand:
  test:
    environments:
      ift:
        services:
          client-service:
            base-url: ${CLIENT_SERVICE_URL:}          # Spring resolves this at startup
            correlation: { source: HEADER, name: X-Correlation-Id }
            auth: { scheme: BASIC, username-ref: CLIENT_USER, password-ref: CLIENT_PASSWORD }
        datasources:
          main-db:
            url: ${MAIN_DB_URL:}
            user: ${MAIN_DB_USER}                     # Spring-resolved credential value twin
            password: ${MAIN_DB_PASSWORD}             # (or keep user-ref/password-ref for lazy refs)
            allowed-schemas: [test_data]
            write-allowed: true
        kafka-cluster:
          bootstrap-servers: ${KAFKA_BOOTSTRAP:}
          security-protocol: ${KAFKA_SECURITY_PROTOCOL:PLAINTEXT}
        ui-applications:                              # needs stand.test.version: 5 for the pair below
          client-portal:
            base-url: ${CLIENT_PORTAL_IFT_URL:}
            auth:
              scheme: FORM
              credentials-username: ${PORTAL_USER}    # the VALUE, Spring-resolved (version 5)
              credentials-password-ref: PORTAL_PASSWORD   # a password stays a lazy reference
              roles: [admin]
              # login: { … }                          # required by FORM — see the full example above
```

Rules:

- **Exactly one twin per field.** Setting both (`base-url` and `base-url-ref`) fails the context
  startup with `"... sets both 'base-url' and 'base-url-ref' — configure exactly one"`.
- **Always append `:` inside the placeholder** (`${CLIENT_SERVICE_URL:}`): with the variable unset the
  value binds as an empty string, the context still starts, and the failure is deferred to step
  execution (`"... configured as a literal value but it is empty"`). Tests gated with
  `@EnabledIfEnvironmentVariable` are simply **skipped** on machines without stand access — same
  behaviour as the ref form. Without the `:` default, Spring fails the startup on the unresolved
  placeholder.
- **Secrets have value twins too, but read the tradeoff.** `auth.username`/`password`/`token`,
  datasource `user`/`password`, `sasl-jaas-config` and (from version 5) the UI
  `credentials-username`/`credentials-password` pair are Spring-resolved twins of their `*-ref`
  fields. A credential supplied through a value twin **does materialise in the Spring Environment**
  (unlike the `*-ref` spelling, which the SDK resolves lazily from the OS environment and never
  places in the Environment). On every twin except the UI pair there is **no `requireReferenceShape`
  guard**, so a literal secret (`password: hunter2`) is technically accepted just like a hardcoded
  URL — always use `${ENV_VAR}` in a value twin, and keep the `*-ref` spelling when a secret must
  never enter the Spring Environment. The UI pair is the one field that does check: a value shaped
  like a variable name is refused rather than typed into a login form.
- Internally a configured value is wrapped with an SDK-internal literal marker (`SecretReferences`)
  so every adapter resolves it verbatim; that marker is rejected fail-closed if it ever appears in a
  `*-ref` field, or in a UI credential value. A hardcoded URL in a value field is technically accepted
  (Spring has already resolved it) — keep values as `${VAR:...}` placeholders by policy.
- **Starter-only, with one exception.** The plain-JUnit `stand-test-environments.yml` file
  (`stand-test-config`) has no Spring to resolve placeholders, so every twin above is a starter
  feature and that file stays committable by construction. The exception is the UI
  `credentials-username`/`credentials-password` pair: it is a value twin on **both** front-ends from
  version 5, because the version key belongs to the registry format rather than to one surface. The
  file loader resolves `${VAR:default}` itself, so a reference may still be written that way there —
  which is exactly why the same spelling used to behave differently on the two front-ends.

## Disable the starter

```yaml
stand:
  test:
    enabled: false
```

With `stand.test.enabled=false` the auto-configuration contributes **no** beans — wire the SDK manually
(or via `@StandTest` from `stand-test-junit`) instead.

## Override a bean

Any bean is overridable — just declare your own of the same type:

```java
@Bean
StandClient standClient(ScenarioRunner runner) {
    return new DefaultStandClient(runner);
}

@Bean
ReportingEventPublisher reportingEventPublisher() {
    return new MyReportingEventPublisher();
}
```

## Optional integrations — required classpath

| Feature | Needs on classpath |
|---------|--------------------|
| REST steps | `stand-test-rest` |
| Kafka steps | `stand-test-kafka` |
| DB steps | `stand-test-db` |
| gRPC steps | `stand-test-grpc` |
| Allure reporting | `stand-test-allure` |
| `Awaiter` / `AwaitPolicy` beans | `stand-test-await` |

These modules are **not** pulled transitively (they are `compileOnly` for the starter) — add the ones
you need explicitly. Omitting a module drops the corresponding bean (its `@ConditionalOnClass` skips it);
`stand-test-core` is the only module the starter forces.

## Why no REST/Kafka/DB/gRPC logic here

Transport logic lives in the adapter modules so the dependency graph stays one-way and acyclic
(`starter → adapters → core`, never the reverse) and `stand-test-core` stays Spring-free. The starter
only **collects** the adapter beans; duplicating client logic here would fork behaviour and violate the
module graph (docs/arch §4/§5).

## Troubleshooting

- **`@Autowired StandClient` fails on `run(...)` with an unknown-environment error** — the environment
  registry is empty. The runtime guardrail rejects any environment not declared under
  `stand.test.environments.*`, so at least one environment is effectively mandatory. Configure one.
- **Context fails to start with `Invalid stand.test.environments.<env> configuration: ... must not be
  blank`** — a `*Ref` (or a nested `correlation.source`/`name`) is missing. Every reference is required
  and must be a non-blank env-var name.
- **No beans appear at all** — check `stand.test.enabled` is not `false`, and that the auto-configuration
  is on the classpath (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`).
- **A step type has "no registered executor"** — the matching adapter module is not on the classpath
  (or was excluded). Add `stand-test-rest`/`-kafka`/`-db`/`-grpc`. `stand-test-ui` registers itself
  through the SPI and needs no bean.
- **Reporting doesn't reach Allure** — ensure `stand-test-allure` is present and
  `stand.test.reporting.allure.enabled` is not `false`; otherwise the no-op publisher is used. The
  Allure **JUnit 5** integration is a consumer dependency: without `allure-junit5` the report comes
  out empty.
- **`ui.login` is refused with `variable '<something>' is not set`** — a `${VAR:default}` placeholder
  written inside a `*-ref` field. Spring collapses it before the SDK sees it, so the resolved text is
  read as a variable name. Put the placeholder in the value twin, or make the `*-ref` a bare NAME.
- **Context fails to start with "shaped like the NAME of an environment variable"** — a version-5
  `credentials-username`/`credentials-password` carries a bare variable name where the VALUE is
  expected. Move it to the `*-ref` twin the message names.
