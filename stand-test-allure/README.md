# stand-test-allure

**Group:** integrations · **Gradle plugin:** `java-library` · **Internal dependency:** `stand-test-core`

Allure reporting **adapter** for the stand-test SDK. It turns the SDK's generic reporting events into
Allure steps, labels, parameters and attachments. It is a **pure consumer** of the core event model —
it runs no scenario and contains no transport logic.

## What this module does

- Implements the core SPI `ru.alfa.stand.test.core.event.ReportingEventPublisher`
  (`AllureReportingEventPublisher`).
- Maps each `StepEvent`:
  - `STARTED` → opens an Allure step named `"<stepType> <stepId>"`;
  - `FINISHED` → sets the Allure step status, attaches the step's diagnostics and any
    `Attachment`s, then closes the step.
- Maps each `ScenarioEvent(STARTED)` → decorates the active Allure **test case** with `tag` labels and
  `scenarioId`/`testRunId`/`correlationId`/`environment` parameters. `ScenarioEvent(FINISHED)` is a
  no-op (the JUnit/Allure integration owns closing the test case).
- Masks secret-looking values before publishing key/value blocks (`SecretMasker`).

### Status mapping (deterministic, plan §8.3 — no "else → passed" fallback)

| SDK `StepStatus` | Allure status |
| ---------------- | ------------- |
| `SUCCESS`        | `PASSED`      |
| `FAILED` (assertion did not hold) | `FAILED` |
| `BROKEN` (infrastructure/config problem) | `BROKEN` |
| `TIMEOUT` (await expired — an unmet expectation) | `FAILED` |
| `SKIPPED`        | `SKIPPED`     |

`FAILED` vs `BROKEN` is what lets a report distinguish "the assertion is red" from "the test could not
be evaluated". The split is produced by `stand-test-core` (the runner records `StandTestAssertionError`
→ `FAILED` and `StandTestException`/unexpected → `BROKEN`); this adapter only renders it.

## What this module does NOT do

No REST/Kafka/DB/gRPC client, no JUnit 5 extension, no YAML parser, no Spring Boot starter, no
`ScenarioRunner`/`StepExecutor`, no scenarios/fixtures, no real-stand config, no retry/await/`Thread.sleep`.
It depends only on `stand-test-core` and the Allure Java commons (lifecycle/model) library.

## Why `stand-test-core` does not depend on Allure

The dependency edge is one-way: `stand-test-allure → stand-test-core`. Core owns the generic, library-free
reporting contract (`ScenarioEvent`/`StepEvent`/`Attachment`/`ReportingEventPublisher`); this adapter is
one of potentially several consumers of that contract. Keeping Allure out of core means a consumer that
does not use Allure (or uses a different reporter) never pays for it, and core stays a JDK-only
dependency-graph sink.

## How the adapter connects to reporting events

The runner publishes lifecycle events to whichever `ReportingEventPublisher` it was built with (the
default is the no-op publisher). Wire this adapter by passing it to the runner:

```java
import ru.alfa.stand.test.allure.AllureReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;

// The Allure publisher writes to the global Allure lifecycle:
ReportingEventPublisher publisher = new AllureReportingEventPublisher();

// Hand it to the runner; adapters (REST/Kafka/DB) remain unaware of Allure:
var runner = new DefaultScenarioRunner(executors, validator, environmentRegistry, publisher);
```

In a test, drive the publisher directly with the testable lifecycle seam — no Allure runtime needed:

```java
import ru.alfa.stand.test.allure.AllureReportingEventPublisher;
import ru.alfa.stand.test.allure.lifecycle.AllureLifecycleFacade;

AllureLifecycleFacade fake = new FakeAllureLifecycleFacade();           // records calls
var publisher = new AllureReportingEventPublisher(fake);
publisher.publish(stepEvent);                                           // assert on the fake
```

> Choosing the publisher per test run (e.g. `AllureReportingEventPublisher` when Allure is on the
> classpath, the no-op otherwise) belongs to the JUnit extension or the Spring Boot starter — a later
> wiring phase. This module deliberately stops at the adapter.

## Metadata in the report

- **Test case** (from `ScenarioEvent`): `tag` labels (one per scenario tag) and parameters
  `scenarioId`, `testRunId`, `correlationId`, `environment`.
- **Step** (from `StepEvent`): parameters `scenarioId`, `testRunId`, `correlationId`, `stepId`,
  `stepType`; plus the step's `diagnostics` and `Attachment`s.

## Attachments

Attachments are **generic** — there are no REST/Kafka/DB-specific attachment types (plan §8.9). Kinds
(`AttachmentType`): `TEXT`, `JSON`, `XML`, `SQL`, `BINARY`, `KEY_VALUE`. The adapter:

- publishes the core `Attachment`s carried on a finished step verbatim (file extension derived from the
  attachment's media type) — those are expected to be **pre-redacted by the producing adapter**;
- renders a step's `diagnostics` map (including await/timeout diagnostics) as a masked `KEY_VALUE`
  attachment named `diagnostics`.

## Secret masking

`SecretMasker` replaces the **value** of any key/value entry whose **key** names a secret
(`password`, `secret`, `token`, `authorization`, `apiKey`, `cookie`) before publishing. Matching is
case-insensitive and ignores separators, so `X-Api-Key`, `Set-Cookie` and `Proxy-Authorization` are
caught too. Masking is key-based (it never rewrites free-form bodies, which could corrupt JSON/XML), and
it is a defence-in-depth net at the sink — adapters are still expected to redact at the source.

## Design for testability

The adapter never calls `io.qameta.allure.Allure` statics directly: all Allure interaction goes through
the thin `AllureLifecycleFacade` seam. `DefaultAllureLifecycleFacade` is the production implementation;
a fake records calls so the mapping is unit-tested without an Allure runtime and without depending on
JUnit/Allure execution order (no flakiness).

## Failure safety

Reporting is a best-effort side-channel (plan §17): every mapping call is wrapped, so a rendering error
is swallowed and **never** replaces the test's real outcome, hides an SDK assertion failure, or turns a
failed step green. The runner additionally swallows publisher exceptions — two independent guards.

## Limitations

- The core event model carries no per-step **description** or **exception class** as first-class fields;
  the step name is `"<stepType> <stepId>"` and the failing exception's class is surfaced via the
  `exception.class` diagnostic the runner records.
- Rich diagnostics/attachments on **thrown** failures depend on adapters populating them on the failure
  path (a later phase); today the runner attaches the exception class on that path, and diagnostics/
  attachments flow fully on the success and returned-`TIMEOUT` paths.
- `BINARY` content travels as text because the core `Attachment` contract is textual.
```
