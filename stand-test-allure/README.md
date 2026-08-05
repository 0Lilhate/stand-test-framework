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

- publishes the core `Attachment`s carried on a finished step (file extension derived from the
  attachment's media type) with their bodies passed through the sink-side secret mask — producers are
  still expected to **pre-redact** (that contract is unchanged), the sink mask is the second echelon;
- renders a step's `diagnostics` map (including await/timeout diagnostics) as a masked `KEY_VALUE`
  attachment named `diagnostics`.

### Файловые вложения (ADR-UI-005)

Ядро `Attachment` несёт **ровно одно** из двух тел: текстовое `content` **или** путь `file`. Файловая
форма появилась потому, что тракт был текстовым насквозь (`content.getBytes(UTF_8)` в стоке), и PNG,
WebM или ZIP через него не проходили; base64 в текстовое поле не помогает — расширение выводится из
media type, и отчёт предлагал бы `.bin`-простыню вместо картинки.

```java
Attachment.of("response", "application/json", body);        // текст — как было
Attachment.ofFile("screenshot", "image/png", pathToPng);    // файл — новое
```

Сток ветвится по `isBinary()`. Три свойства файловой ветки, о которых нужно знать:

1. **Маскирование к ней неприменимо.** Байты скриншота нечем замаскировать постфактум, поэтому
   `SecretMasker` файловую ветку не трогает — и именно отсюда требование маскировать чувствительные
   зоны **в DOM до снятия** артефакта (SEC-05). Текстовая ветка маскируется как раньше.
2. **Путь проверяется до открытия файла.** `AllureAttachmentPublisher` принимает каталог артефактов
   прогона и публикует только файлы внутри него: путь резолвится до реального (`toRealPath()`, то есть
   по символическим ссылкам) и обязан лежать под каталогом. Без этого канал отчётности стал бы каналом
   раскрытия файлов: путь с `../` или ссылка наружу положили бы произвольный файл в отчёт, который потом
   прикладывают к тикету.
3. **Fail-closed дважды.** Публикатор, созданный **без** каталога артефактов, отвергает *любое* файловое
   вложение: сток, не знающий каталога прогона, не отличит артефакт от `/etc/passwd`. И любой отказ —
   это WARN и пропущенное вложение, никогда не исключение: отчётность side-channel и не меняет исход
   теста. Пропавший файл ведёт себя так же — прогон идёт дальше без картинки.

Расширение для файла выводит `AttachmentType.extensionForBinaryMediaType`, а не текстовый маппер: у них
**противоположный** fallback. Неизвестный media type у текста разумнее всего `txt`, у файла — `bin`;
перепутать их значит предложить скриншот как текст. Известны `png`, `jpg`, `webm`, `zip`, `json`, `xml`
и `text/*`; остальное — `bin` с WARN.

## Secret masking

Masking is two-echelon: adapters redact at the source (the core `Attachment` pre-redaction contract),
and `SecretMasker` re-masks at the sink so **no content leaves the publisher unmasked**.

- **Key/value surfaces** (step parameters, `KEY_VALUE` diagnostics): the **value** of any entry whose
  **key** names a secret (`password`, `secret`, `token`, `authorization`, `apiKey`, `cookie`) is
  replaced with `***`. Matching is case-insensitive and ignores separators, so `X-Api-Key`,
  `Set-Cookie` and `Proxy-Authorization` are caught too. A value that *is* a single `Bearer`/`Basic`
  credential token is masked even under an innocuous key.
- **Attachment bodies** (`maskText`): the scalar value of any **JSON field** whose key names a secret
  becomes `"***"` (string, number, boolean or null values alike), and any **embedded
  `Bearer`/`Basic` credential token** (8+ token characters including at least one non-letter) is
  replaced with `***` anywhere in the text — prose like `Basic authentication required` is untouched.

Limitations of the body mask (why producer pre-redaction stays mandatory): an object or array nested
*under* a sensitive key is not masked wholesale (sensitive keys *inside* it still are), and non-JSON
`key=value` property lines are not rewritten.

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
- Каталог артефактов прогона передаётся публикатору снаружи. Проводка его из
  `UiRunSettings.artifactsDirectory()` — задача `UITG-T003`, и у неё пока нет вызывающего: артефакты
  начинает снимать `UITG-S013`. До тех пор действует fail-closed: файловые вложения отвергаются.
- Само **снятие** артефактов (скриншот, консоль, сеть, трейс) в этот срез не входит — `UITG-S013`…`S016`.
  Здесь только канал.
```
