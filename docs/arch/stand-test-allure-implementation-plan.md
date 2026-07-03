# stand-test-allure — implementation plan (Итерация 7)

Самодостаточный план реализации **Итерации 7 (Allure)** — написан так, чтобы новая сессия без контекста
могла начать. Источник истины контрактов: `stand-test-sdk-implementation-plan.md` — **§7** (порядок),
**§8.9** (нормативный контракт attachments — читать первым), §4 `stand-test-allure`, §17 (observability).
Модуль сейчас — скелет (`stand-test-allure/src/main/java/ru/alfa/stand/test/allure/package-info.java`).

## Текущие core-контракты (verbatim — чтобы не переоткрывать)

- **`core.result.StepResult`** = `record(String stepId, String stepType, StepStatus status, Instant
  startedAt, Instant finishedAt, String errorMessage, Map<String,Object> diagnostics)`; `diagnostics`
  defensively-copied; есть фабрика `StepResult.failed(stepId, stepType, start, finish, message)`.
- **`core.event.StepEvent`** = `record(ScenarioId, TestRunId, CorrelationId, String stepId, String stepType,
  StepPhase phase, StepStatus status, Instant timestamp, String message, Map<String,Object> diagnostics)
  implements ReportingEvent`. `status` может быть null на STARTED.
- **`core.event.ScenarioEvent`** = `record(ScenarioId, TestRunId, CorrelationId, ScenarioPhase, Instant)`.
- **`core.event.ReportingEvent`** — `sealed interface permits ScenarioEvent, StepEvent`.
- **`core.event.ReportingEventPublisher`** = `{ void publish(ScenarioEvent); void publish(StepEvent); }`;
  дефолт — `NoOpReportingEventPublisher.INSTANCE` (всё игнорирует).
- **`core.execution.DefaultScenarioRunner`** публикует события через
  `publishStep(context, step, phase, status, message, diagnostics)`: STARTED перед `execute` (diagnostics
  `Map.of()`), FINISHED после (передаёт `result.diagnostics()`), recordFailure (`Map.of()`).
  `publish(StepEvent)`/`publish(ScenarioEvent)` **уже** глотают `RuntimeException` (best-effort, §17) — то
  есть инфраструктура «репортинг не влияет на pass/fail» на месте.
- **`core.execution.StepExecutor`** SPI: `StepResult execute(ScenarioStep, StepExecutionContext)`;
  `supports(type)`; `prepare(...)`. Адаптеры возвращают `StepResult` с `LinkedHashMap` diagnostics. Текущие
  ключи: REST `http.method/path/status`; Kafka `kafka.operation/topic/realTopic/key/partition/offset/
  messagesSeen`; DB `db.operation/datasource/captured/value/rowsAffected`. **Тел/payload/SQL-текста там нет
  — это и есть пробел, который §8.9 закрывает.**

---

## Фаза 0 — core-prerequisite (§8.9): контракт `Attachment` [ДЕТАЛЬНО, начинать отсюда]

Чистый core, без Allure-зависимости, собирается сам по себе. **Отдельный коммит** (как классификатор для DB).

1. **`core.event.Attachment`** — `record(String name, String mediaType, String content)`: иммутабельный,
   валидируемый (name/mediaType non-blank; `content` — `requireNonNull`). Javadoc. → `AttachmentTest`.
2. **Поле `List<Attachment> attachments`** на `StepResult` и `StepEvent`:
   - добавить как **последний** компонент канонического record-конструктора; defensively-copied
     (`List.copyOf`), `null → List.of()`;
   - **сохранить совместимость call-site'ов:** добавить delegating-конструктор прежней арности (7-арг для
     `StepResult`, 10-арг для `StepEvent`), делегирующий в канонический с `List.of()` — чтобы существующие
     `new StepResult(...)` в адаптерах и тесты `StepEvent` **компилировались без правок** в этой фазе;
   - обновить `StepResult.failed(...)` → пустой список;
   - геттер `attachments()` появляется автоматически (record).
3. **Раннер проброс:** `DefaultScenarioRunner.publishStep(...)` получает параметр `List<Attachment>
   attachments`; штатный FINISHED-вызов передаёт `result.attachments()`, STARTED — `List.of()`, а путь отказа
   (`recordFailure`) — **не** пустой список, а attachments/diagnostics упавшего шага (см. п.6);
   `StepEvent` строится с ним. (`publish` уже best-effort.)
4. **`core.event.Attachments`** — хелпер-редактор (security-gate, §8.9): статические фабрики, строящие
   **редактированные** `Attachment`:
   - `redactedHeaders(Map<String,String>)` → значения по deny-list (`Authorization`, `Proxy-Authorization`,
     `Cookie`, `Set-Cookie`, `X-Api-Key`, case-insensitive) → `***`;
   - `truncate(String, int maxBytes)` → обрезка с маркером `…[truncated N bytes]`;
   - контракт: **никогда** не принимать/прикладывать резолвнутые секреты; вызывающий адаптер обязан не
     передавать значения секрет-binds/кредов. Чистые функции, без IO. → `AttachmentsTest`.
5. **`StepStatus.BROKEN` — разнести assertion vs infrastructure (HIGH).** Сейчас `StepStatus` =
   `SUCCESS/FAILED/SKIPPED/TIMEOUT` (нет статуса инфра-сбоя), а раннер сводит и assertion-, и infra-падение к
   одному `FAILED` (`recordFailure` для всех трёх веток `execute`) — значит publisher (Фаза 2) **не сможет**
   отличить Allure-`FAILED` от `BROKEN`. Закрыть в core:
   - ввести `StepStatus.BROKEN` (инфра/конфиг-сбой); `isFailure()` → true и для него;
   - в `DefaultScenarioRunner`: ветку `StandTestAssertionError` (§8.3) → `FAILED` (`StepResult.failed(...)`),
     ветки `StandTestException`/`unexpected` → `BROKEN` (новый `StepResult.broken(...)` либо параметр статуса);
   - зафиксировать **полный детерминированный** маппинг для allure-publisher (Фаза 2), без «иначе→PASSED»:
     `SUCCESS→PASSED`, `FAILED→FAILED`, `BROKEN→BROKEN`, `TIMEOUT→FAILED` (просроченный await — невыполненное
     ожидание, §8.3 бросает `StandTestAssertionError`), `SKIPPED→SKIPPED`;
   - **синхронно поправить §8.3 SDK-плана** (добавить `BROKEN` в перечень `StepStatus` и в failure-маппинг).
6. **Доставка attachments/diagnostics на пути ОТКАЗА (HIGH).** Сейчас `recordFailure` строит
   `StepResult.failed(...)` без diagnostics/attachments и публикует FINISHED с `Map.of()`/`List.of()` — поэтому
   §8.9-вложения и timeout-diagnostics были бы **только у успешных шагов**, тогда как Allure смотрят именно на
   падениях. Закрыть в core (а не откладывать в Фазу 2):
   - дать адаптеру канал донести diagnostics+attachments упавшего шага до FINISHED. Основной механизм —
     **sink в `StepExecutionContext`** (напр. `context.attach(...)` / `context.diagnostic(...)`), который
     адаптер наполняет **до** выброса исключения (throw-семантика §8.3 сохраняется), а раннер в `recordFailure`
     читает накопленное. (Альтернативы: данные на самом `StandTest*`-исключении; либо адаптер возвращает
     FAILED/BROKEN `StepResult`, а раннер ре-выбрасывает после публикации — выбрать одну и зафиксировать.)
   - `recordFailure` публикует FINISHED с **реальными** `diagnostics` и `attachments`, а не пустыми — это
     **отменяет** упрощение «recordFailure → пустые» из пп.2–3;
   - редакция секретов обязательна и на этом пути (через `Attachments`: deny-list/`truncate`, никаких
     резолвнутых секрет-bind-значений/кредов).
7. **Тесты (DoD):** `AttachmentTest` (валидация); `AttachmentsTest` (deny-list заголовок→`***`, truncation,
   passthrough обычного); runner-тесты: (а) штатный FINISHED-`StepEvent` несёт `attachments` из `StepResult`,
   STARTED — пустой; (б) `StandTestException` → FINISHED со `StepStatus.BROKEN`, `StandTestAssertionError` →
   `FAILED` (п.5); (в) упавший шаг с накопленными в `StepExecutionContext` diagnostics/attachments → FINISHED
   несёт их **редактированными**, не пустые (п.6); `NoOp` игнорирует без эффекта. Coverage ≥80%, checkstyle,
   сборка core зелёная.

**Результат Фазы 0:** core отдаёт полный контракт доставки вложений **и на успехе, и на падении**, с явным
разделением assert/infra (`FAILED`/`BROKEN`); адаптеры/allure ещё не тронуты.

---

## Фаза 1 — адаптеры наполняют `attachments` (по одному модулю, отдельные коммиты)

Через `Attachments`-хелпер, в `StepResult.attachments` (FINISHED). Никогда не прикладывать
resolved-креды/секрет-bind-значения.

- **REST** (`RestStepExecutor`): request (метод+путь+`redactedHeaders`+тело) и response
  (статус+`redactedHeaders`+тело, с `truncate`). + тесты на наличие/редакцию.
- **Kafka** (`KafkaStepExecutor`): send → produced key/headers/value; expect → consumed key/headers/value
  совпавшего сообщения. + тесты.
- **DB** (`DbStepExecutor`): **итоговый** SQL (после append testRunId-предиката), **имена** binds (не
  значения), ограниченный preview результата (захваченные колонки / наблюдённое значение). + тесты.

DoD: каждый модуль зелёный; негативные/редакционные кейсы покрыты.

---

## Фаза 2 — `stand-test-allure` (consumer)

1. **build:** `api(project(":stand-test-core"))`; добавить в `gradle/libs.versions.toml`
   `allure-java-commons` (Lifecycle API) и `allure-junit5` (как делалось для h2/kafka).
2. **`AllureReportingEventPublisher implements ReportingEventPublisher`:**
   - `StepEvent(STARTED)` → `Allure.getLifecycle().startStep(uuid, new StepResult().setName(stepType+" "+stepId))`;
   - `StepEvent(FINISHED)` → выставить `Status` (`StepStatus` FAILED→FAILED, ошибки инфра→BROKEN, иначе
     PASSED), на каждый `event.attachments()` → `lifecycle.addAttachment(name, mediaType, ext, content)`,
     затем `stopStep`;
   - `ScenarioEvent` → границы Allure-теста + labels `scenarioId`/`testRunId`/`correlationId`;
   - timeout-diagnostics — из `diagnostics` FINISHED-события;
   - **best-effort:** весь маппинг в try/catch — ошибка рендера не роняет тест. Thread-confined (один
     прогон — один поток).
3. **Тесты:** через тестовый `AllureLifecycle` с `InMemoryResultsWriter` (allure-java-commons даёт seam) —
   проверить, что шаги/статусы/attachments/labels попадают в результат. AssertJ.
4. **README + package-info** (реальные, не скелет); опц. `stand-test-allure-decisions.md`.
5. DoD: сборка модуля зелёная; §18-чеклист (в т.ч. «есть Allure-диагностика: шаги/attachments/метаданные»).

---

## Фаза 3 — проводка + примеры

- **Кто инжектит publisher:** junit-extension (plain JUnit) строит раннер с
  `AllureReportingEventPublisher`, когда allure на classpath; либо `spring-boot-starter`. Дефолт остаётся
  `NoOpReportingEventPublisher`. Адаптеры про Allure не знают.
- **Примеры** — технические smoke-usage (без бизнес-логики, §8 stand-test-example; полноценно — Итерация 8).

---

## Project gotchas (сэкономить новой сессии время — проверено в этой)

- **Toolchain Java 24** (`build.gradle.kts: options.encoding="UTF-8"`). Для standalone-прогонов (jshell/
  javac вне gradle) брать JDK 24: `$(/usr/libexec/java_home -v 24)` — дефолтный `javac` (21) не прочитает
  классы (major 68).
- **Checkstyle zero-tolerance** (`maxWarnings=0`, main+test):
  - `MethodName` паттерн `^_?[a-z][a-z0-9][a-zA-Z0-9_]*$` — **второй символ имени метода должен быть
    строчным/цифрой**; имя вида `aBlankReference()` ОТКЛОНЯЕТСЯ (`aB` — заглавная второй). snake_case в
    именах тестов допустим (`datasource_schemaWhitelist`).
  - `AvoidEscapedUnicodeCharacters` — **запрещает `\uXXXX`-escape для печатных символов**; в тестах с
    не-ASCII писать **литеральный** символ (исходники UTF-8), не escape.
  - `InnerTypeLast` — методы/поля/конструкторы **перед** вложенными `enum`/`record` (forward-reference на
    типы внутри класса в Java ок).
  - AssertJ обязателен (`org.junit.jupiter.api.Assertions` и JUnit4 `org.junit.Test` — banned imports);
    non-JetBrains `@NotNull/@Nullable` — banned; `LineLength` max 1000 (цепочки/длинные строки не переносить).
  - `RedundantImport` не флагует импорт вложенного типа из «своего» пакета
    (`import ...SqlSpanScanner.Span;`) — это норм.
- **Команды:** `./gradlew :stand-test-core:build :stand-test-allure:build --console=plain`; coverage-gate
  80% instruction; jacoco-xml: `<module>/build/reports/jacoco/test/jacocoTestReport.xml` (парсить ElementTree
  по `<counter type=...>`). `--console=plain` для чистого вывода.
- **TDD:** RED-тест → подтвердить падение → реализация → GREEN → полная сборка обоих модулей.
- **Git:** формат `type: description` (feat/fix/docs/test/chore/refactor); **без** `Co-Authored-By`
  (проект отключил атрибуцию, `git-workflow.md`); core-prerequisite — **отдельный** коммит от модуля
  (паттерн истории: каждый адаптер = feat, core-prereq отдельно). Не пушить без явной просьбы.
- **Untracked-нюанс:** новые модули/классы на ветке `feat/stand-test-rest` — untracked; атомарность по
  компонентам, не по правкам.

## Verification (на каждой фазе)

```bash
./gradlew :stand-test-core:build --console=plain                 # Фаза 0
./gradlew :stand-test-rest:build :stand-test-kafka:build :stand-test-db:build --console=plain  # Фаза 1
./gradlew :stand-test-allure:build --console=plain               # Фаза 2
./gradlew build --console=plain                                  # всё вместе перед коммитами
```

## Порядок и оценка

1. **Фаза 0** (core `Attachment` + поле + проброс + редактор + тесты) — небольшая, чистая, разблокирует всё.
2. **Фаза 1** (REST → Kafka → DB наполнение) — параллелизуемо по адаптерам.
3. **Фаза 2** (`stand-test-allure` consumer) — основной модуль Итерации 7.
4. **Фаза 3** (проводка + smoke-пример).

После Фазы 0+2 закрывается §18-DoD по Allure; Фаза 1 даёт реальные attachments; Фаза 3 — потребление в plain
JUnit. Каждая фаза — зелёная сборка и (по возможности) отдельный коммит.
