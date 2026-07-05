# stand-test-rest

**Group:** adapters · **Gradle plugin:** `java-library`

REST / HTTP adapter for calling services on a stand. It owns the typed `RestStep` model and the REST
`StepExecutor` (registered via the core SPI), and is the **single point of real HTTP IO** to the stand
(plan §4, Iteration 4 / MVP).

**Internal dependencies:** `stand-test-core` (`api`), `stand-test-await` (`implementation`).

**External dependencies:** a mature HTTP client — Spring `WebClient` (`spring-webflux`) driven through
the **JDK HttpClient connector** (`JdkClientHttpConnector`), so reactor-netty is *not* pulled in — plus
`json-path` (JSONPath, json-smart provider; no Jackson). The SDK never ships its own HTTP client
(plan §4, §20).

## What it does (MVP)

- **GET / POST** (PUT / DELETE builders also provided); headers, query parameters, request body.
- **Outbound `correlationId` injection** — the SDK-owned id is injected into the header configured for
  the target service (`ServiceEndpointDefinition.correlation().name()`), *before* the call. The header
  name comes from the environment config, never hardcoded.
- **JSONPath assertions** (`assertPath`) and **response capture** into the run's `VariableStore`
  (`capture`), so later steps can read `${requestId}` etc. Assertion comparison is type-aware: numbers
  match by value (`100` ≡ `100.0`) but other type changes (boolean→string, string vs number) fail
  rather than being string-coerced — a field changing type is a real contract regression.
- **`${...}` variable substitution** in path, query, headers and body (built-ins `scenarioId` /
  `testRunId` / `correlationId` / `environment` plus captured variables).

## API surface

| Type | Role |
|------|------|
| `RestStep` | Lazy builder. Static `get`/`post`/`put`/`delete`; `header`/`query`/`body`/`bodyFromResource`/`injectCorrelationId`/`expectStatus`/`assertPath`/`capture`/`id`; `build()` → core `ScenarioStep`. Performs **no IO**. |
| `RestStepExecutor` | The `StepExecutor` SPI implementation (`supports("rest.*")`). Stateless / thread-safe; discovered via `ServiceLoader` (`META-INF/services`). The single point of real HTTP IO. |
| `RestStepParameters` | The shared parameter-map schema (key names + readers) — the one contract `RestStep` writes and `RestStepExecutor` reads, so a future YAML front-end can target the same map. |
| `HttpCaller` / `WebClientHttpCaller` | The HTTP transport seam and its default WebClient (JDK connector) implementation. |
| `BaseUrlResolver` / `EnvironmentBaseUrlResolver` | Resolves a `baseUrlRef` (treated strictly as a reference — the name of an environment variable holding the base URL) to an absolute base URL. Tests inject their own resolver rather than embedding a literal URL in the registry. |
| `RestRequest` / `RestResponse` / `RestAssertion` / `RestCapture` | Immutable value objects. |

## Usage sketch

The `RestStep.build()` result is a core `ScenarioStep` passed to `Scenario.Builder.step(...)`:

```java
var scenario = Scenario.builder("example-flow")
        .environment("ift")
        .step(RestStep.post("client-service", "/api/request")
                .header("Content-Type", "application/json")
                .body("{\"amount\": 100}")            // or .bodyFromResource("fixtures/request.json")
                .injectCorrelationId()                // SDK-owned correlationId → outbound header
                .expectStatus(200)
                .capture("requestId", "$.requestId")  // service-generated id, for later steps
                .build())
        .build();

stand.run(scenario);   // Validator → Runner → StepExecutor SPI → RestStepExecutor → real HTTP
```

Adding a `testImplementation` dependency on this module makes `rest.*` step types runnable: the runner
discovers `RestStepExecutor` via `ServiceLoader`, so no wiring code is needed in the test.

## Failure semantics (plan §8.3)

- **Assertion failures** (status mismatch, JSONPath mismatch, missing/`null` captured value, non-JSON
  body) → `StandTestAssertionError` (a JUnit-native failure).
- **Infrastructure / configuration problems** (alias not whitelisted, unresolved base URL, transport
  error, unresolved `${...}` variable, correlation injection without a HEADER config) →
  `StandTestException`.

## Environment resolution & security

The logical service alias is resolved through the core `EnvironmentRegistry` (a two-hop, fail-closed
whitelist: `environment(name) → service(alias)`). A missing alias never falls back to a guessed
endpoint. `baseUrlRef` is a **reference** (an environment-variable name) resolved at run time — stand
URLs and secrets stay out of source (plan §9). Request/response diagnostics attached to the
`StepResult` (`http.method` / `http.path` / `http.status`) deliberately exclude header values.
Assertion-failure messages DO echo the actual response value (`expected <...> but got <...>`) — that
diagnostic value is the point of the assertion; against a stand returning sensitive payloads, prefer
asserting on non-sensitive fields. A JSON parse failure never echoes the response body.

## Environment registry

`RestStepExecutor` resolves the `service` alias through a populated `EnvironmentRegistry`. Provide one via
either path: the **Spring Boot starter** (`@ConfigurationProperties("stand.test")`), or — for plain JUnit —
the **`stand-test-config`** module (add it to `testImplementation` and drop a `stand-test-environments.yml`
on the classpath). Both build the registry from environment-variable *references*, never inline URLs or
secrets. Without either, `StandTestExtension` falls back to an empty registry and alias resolution fails
at run time.

## Registry-driven authentication

A service whose `ServiceEndpointDefinition` carries an `AuthConfig` gets the `Authorization` header
injected by the executor at execution time — `Basic base64(username:password)` (UTF-8, RFC 7617) or
`Bearer token`. Every auth field is a secret **reference** (an env-var name) resolved by
`EnvironmentAuthHeaderResolver` at the point of use; an unresolved reference fails as
`StandTestException` naming the reference, never a value. This is the sanctioned auth path: an inline
`Authorization` header in a scenario is still rejected by the validator (`SECRET_IN_SOURCE`), and the
registry-driven value always wins. Configure it per service via `stand-test-config`
(`auth: { scheme: BASIC, username-ref: ..., password-ref: ... }`) or the starter
(`stand.test.environments.<env>.services.<alias>.auth.*`).

## Not here

No await/`expectEventually` polling for REST (the `rest → await` edge exists but is unused in the MVP —
plan defines `expectEventually` for DB only); no auth schemes beyond BASIC/BEARER, no multipart,
retry/redirect policies; no Allure attachments (those belong to the reporting adapter, fed by the
`StepResult` diagnostics).

## Testing

Unit tests drive `RestStepExecutor` through a `FakeHttpCaller` (no server); an end-to-end test exercises
the real `WebClientHttpCaller` against a local JDK `HttpServer` (correlation header on the wire, POST
body, status assertion, connection failure). A `ServiceLoader` test asserts the `META-INF/services`
registration. Instruction coverage is ≥ 80% (JaCoCo).
