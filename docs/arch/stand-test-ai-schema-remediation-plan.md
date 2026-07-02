# stand-test-ai-schema — remediation plan (после MVP + жёсткого ревью)

План доработки модуля `stand-test-ai-schema` по итогам жёсткого ревью MVP. Каждый пункт: проблема →
доказательство (воспроизведено против собранной схемы через networknt) → фикс → затронутые файлы →
критерий приёмки (DoD). Приоритеты: **P0** (блокирует смысл модуля / нужно решение), **P1**
(корректность guardrails и целостность тестов — делать независимо от P0), **P2** (полнота, usability,
синхронизация документации).

> Источники: `docs/arch/stand-test-sdk-implementation-plan.md` §4/§5/§8.6/§8.8, `stand-test-ai-schema-design.md`
> (Решения 1–6), `stand-test-scenario-yaml-design.md` (surface). Текущее состояние: MVP реализован
> (схема + rules-док + загрузчик + 12 тестов), `./gradlew build` зелёный, покрытие `AiSchemaResources`
> 82.9%.

> **Статус (2026-07-02).**
> - **A1 (P0): РЕАЛИЗОВАНО — вариант 2 (translator).** Добавлен `AiScenarioParser` в
>   `stand-test-scenario-yaml` (core-only): читает AI `steps/type`, нормализует ergonomic-поля на
>   yaml-surface (`AiStepNormalizer`) и делегирует существующим `*StepTranslator` → тот же `GenericStep`.
>   Общий safe-loader вынесен в `SafeYaml`. Fail-closed на неисполнимых конструкциях (inline `body.json`/
>   `payload.json`, не-`equals` matchers, `expect.rowExists`, `grpc.unary`). Parity-тест `AiSchemaParityTest`
>   в `stand-test-example`: документ проходит JSON Schema (networknt) **и** парсится в валидный `Scenario`
>   с проверкой wire-маппингов. Valid-примеры ai-schema приведены к executable-подмножеству. `./gradlew build`
>   зелёный. Схема (`steps/type`) — надмножество исполнимого; gap → follow-ups ниже.
> - **P1: ВЫПОЛНЕНО** — G1/G2/G3/G5 закрыты в схеме, T1 усилен, добавлен регрессионный `GuardrailHoleTest`.
>   16 тестов зелёные, `./gradlew build` зелёный. Детали — в статус-пометках ниже.
> - **P2: ВЫПОЛНЕНО** — T2, T3, R1, G4, G6, G7, C1, R2/D1/consistency закрыты (см. пометки ниже).
>   Открытыми остаются только **schema-follow-ups** (`restStep.query`, REST body assertions) и крупные
>   кросс-модульные (inline JSON body, rich matchers/`rowExists`, grpc execution, hoist surface-ключей).

---

## Сводка приоритетов

| # | Приоритет | Заголовок | Тип | Оценка |
|---|-----------|-----------|-----|--------|
| A1 | **P0** | Разрыв формата: схема `steps/type` vs surface `scenario-yaml` `given/then` | decision | — (решение) |
| T1 | **P1** | Invalid-тесты не пришпиливают причину падения | test | S |
| G2 | **P1** | Обход `HARDCODED_STAND_URL`: protocol-relative `path: //host` | schema | S |
| G1 | **P1** | Обход `THREAD_SLEEP` через SQL (`pg_sleep`) | schema/doc | S |
| G3 | **P1** | `timeout: 9999999m` (≈unbounded) принимается | schema | S |
| G5 | **P1** | Path traversal в `body.fixture` (`../../etc/passwd`) | schema | S |
| T2 ✅ | **P2** | Нет valid-примеров для `rest.get` и `grpc.unary` — **добавлены** | test | S |
| T3 ✅ | **P2** | `oneOf` → взрыв сообщений (25–32/док); перейти на `if/then` — **сделано (1–2/док)** | schema | M |
| R1 ✅ | **P2** | Rules-док завышает, что энфорсит схема — **сделано** | doc | S |
| G6 ✅ | **P2** | `capture` value не ограничен JSONPath — **^\$ добавлен** | schema | S |
| G7 ✅ | **P2** | `rest.get` с `body` — **запрещён (allOf)** | schema | S |
| G4 ✅ | **P2** | Дубли `step.id` — документировано (runtime ловит) | doc | S |
| C1 ✅ | **P2** | Хрупкость coverage-гейта (82.9%→**100%**, `catch` покрыт) | test/build | S |
| R2/D1/consistency ✅ | **P2** | cross-check усилен, core-dep зафиксирован, `id`-паттерн унифицирован | doc/build | S |

Оценка: S ≈ ≤0.5 дня, M ≈ 1–2 дня.

---

## P0 — блокер смысла модуля (нужно решение до правок схемы)

### A1. Формат документа расходится с исполняемым pipeline

**Проблема.** План §4 и `stand-test-ai-schema-design.md` (Решение 1) требуют: JSON Schema **зеркалит
surface `scenario-yaml`** (`given`/`then` + одноключевые step-мапы `{rest.post: {...}}`), а parity-тест в
`stand-test-example` гарантирует, что документ, принятый схемой, **реально парсится** `YamlScenarioParser`.
MVP по явному указанию использует `steps: [ {type: rest.post, ...} ]` — другой язык. Значит схема
валидирует документы, которые SDK исполнить не может; «единый источник/parity» из дизайна не выполняется.

**Доказательство.** `YamlScenarioParser`/translator'ы читают `given/then` + one-key map и ключи
`GenericStep.parameters` (`expectedStatus`, `injectCorrelationId`, `correlationIdFromContext`,
`timeoutMillis`, …). Ни одного компонента, читающего `steps/type` + `expect.status`/`correlation.inject`/
`timeout: "30s"`, в репозитории нет.

**Варианты решения (выбрать один):**

1. **Мигрировать surface `scenario-yaml` на `steps/type`** — привести парсер к формату схемы. Плюс: единый
   современный формат, дружелюбный к JSON Schema и AI. Минус: breaking-change Итерации 9, переписывание
   translator'ов и их тестов, миграция примеров в `stand-test-example`.
2. **Добавить translator `steps/type → given/then`** (либо `steps/type → GenericStep`) в `scenario-yaml`
   или отдельном слое. Плюс: схема остаётся AI-дружелюбной, рантайм не трогаем радикально. Минус: третий
   формат-mirror, ещё один источник drift.
3. **Вернуть схему к surface `given/then`** (как в дизайне). Плюс: сразу выполняет план §4 + parity-тест
   становится возможен. Минус: one-key map хуже валидируется/генерируется AI (причина, по которой MVP
   ушёл от него); `additionalProperties:false` на диспетчере сложнее.

**Рекомендация ревьюера.** Для MVP-guardrail, который должен реально защищать существующий pipeline —
**вариант 2** (translator): сохраняет AI-дружелюбный `steps/type` и восстанавливает parity через тонкий
слой, без breaking-change рантайма. Вариант 1 — если команда готова закоммитить единый формат стратегически.

**DoD A1.** Зафиксировано решение в этом документе и в мастер-плане §4; если вариант 2/3 — добавлен
parity-тест в `stand-test-example` (документ, принятый схемой, успешно доходит до `Scenario`/`GenericStep`).

> Пункты P1/P2 ниже **format-agnostic** там, где касаются паттернов значений (SQL, timeout, path) и тестов —
> их можно делать параллельно с решением A1. Пункты, привязанные к именам полей, финализировать после A1.

---

## P1 — корректность guardrails и целостность тестов ✅ ВЫПОЛНЕНО (2026-07-02)

> Изменения: `schema/stand-test-scenario.schema.json` (G1/G2/G3/G5), `ScenarioSchemaValidationTest`
> (T1: `@CsvSource` файл→токен-причина), новый `GuardrailHoleTest` (регресс на G1/G2/G3/G5).
> Все P1-фиксы format-agnostic — не затронуты решением A1.

### T1. Invalid-тесты обязаны проверять причину падения, а не факт  ✅

**Проблема.** `ScenarioSchemaValidationTest.invalidExamples_fail` ассертит только `messages.isNotEmpty()`.
У `oneOf` всегда 25–32 сообщения; документ, переставший ловиться **целевым** правилом, но упавший по любой
другой причине, оставит тест зелёным — регрессия guardrail не заметна.

**Доказательство.** Дамп сообщений подтвердил: сегодня причины верные (url-prop; `Authorization`
propertyNames; required `timeout`; SQL start-SELECT + not-destructive; type-enum), но тест этого не
проверяет.

**Фикс.** Перейти на таблицу «файл → ожидаемый фрагмент/локация сообщения» и ассертить, что среди сообщений
есть целевое. Пример соответствия:

| файл | ожидаемый признак в сообщении |
|------|-------------------------------|
| `arbitrary-url.json` | `url` + `не допускает дополнительных свойств` (instanceLocation `$.steps[0]`) |
| `inline-secret.json` | `propertyNames` ветка `headers` / имя `Authorization` |
| `missing-timeout.json` | `необходимое свойство 'timeout'` в ветке `kafka.expect` |
| `destructive-sql.json` | `query` + паттерн `SELECT` **или** `query.not` (destructive) |
| `unknown-step-type.json` | `type` не в enum / const по всем веткам |

**Файлы.** `stand-test-ai-schema/src/test/java/.../ScenarioSchemaValidationTest.java`.
**DoD.** Каждый invalid-кейс падает и содержит целевой фрагмент; подмена причины → красный тест.

---

### G2. Protocol-relative `path` обходит запрет абсолютных URL  ✅

**Проблема/доказательство.** `rest.get` с `path: "//evil.com/x"` → **ACCEPTED** (паттерн `^/` матчит `//host`).
Это фактически хост, а не относительный путь — частичный обход `HARDCODED_STAND_URL`.

**Фикс.** В `$defs.restStep.properties.path`: `"pattern": "^/($|[^/])"` (слэш, за которым конец строки или
не-слэш). Отвергает `//...`, оставляет `/`, `/api/x`.
**Файлы.** `schema/stand-test-scenario.schema.json`.
**DoD.** `path: "//evil.com"` отвергается; `path: "/"` и `/api/requests` принимаются (добавить в T1/valid).

---

### G1. Обход `THREAD_SLEEP` через серверный SQL  ✅

**Проблема/доказательство.** `db.expectEventually` с `query: "SELECT pg_sleep(30)"` → **ACCEPTED**. Паттерн
`^SELECT … not(DDL/DML)` не ловит блокирующие/побочные функции. Rules-док заявляет для `THREAD_SLEEP` слой
«schema» — сейчас неверно.

**Фикс (одно из).**
- (a) Расширить `$defs.dbExpectEventuallyStep.properties.query.not` альтернативами (case-insensitive
  char-classes): `pg_sleep`, `sleep\s*\(`, `waitfor`, `dbms_lock`, `dbms_session`, `benchmark\s*\(`.
- (b) Если не хотим гонку паттернов — понизить слой `THREAD_SLEEP` для DB до `RUNTIME_VALIDATOR` в rules-доке
  и добавить проверку в `SqlStatementClassifier`/DB-адаптере (companion-задача core).

**Рекомендация.** (a) сейчас (дёшево, закрывает 90% кейсов) + пометка, что полная семантика — рантайм.
**Файлы.** `schema/...json`, `ai/stand-test-ai-generation-rules.md`.
**DoD.** `SELECT pg_sleep(1)` отвергается; обычный `SELECT ... WHERE ...` принимается.

---

### G3. `timeout` без верхней границы  ✅

**Проблема/доказательство.** `timeout: "9999999m"` (≈19 лет) → **ACCEPTED**. ТЗ п.11 требует запрещать
«очень большие/unbounded». `duration = ^[1-9][0-9]{0,6}(ms|s|m)$` пускает 7 цифр.

**Фикс.** Ввести грубый потолок по единице (значения согласовать с командой), напр.:
```
"duration": { "oneOf": [
  { "type": "string", "pattern": "^[1-9][0-9]{0,4}ms$" },
  { "type": "string", "pattern": "^[1-9][0-9]{0,3}s$" },
  { "type": "string", "pattern": "^[1-9][0-9]{0,2}m$" }
] }
```
(≤ 99999ms / 9999s / 999m). Точный лимит — решение команды; рантайм может ужесточить.
**Файлы.** `schema/...json`.
**DoD.** `9999999m` отвергается; `30s`/`100ms`/`2m` принимаются.

---

### G5. Path traversal в `body.fixture`  ✅

**Проблема/доказательство.** `body.fixture: "../../../etc/passwd"` → **ACCEPTED** (нет ограничения пути).

**Фикс.** В `$defs.payloadSource.properties.fixture` (и в `grpcUnary.request`): паттерн без `..`-сегментов,
напр. `"pattern": "^[A-Za-z0-9_][A-Za-z0-9_./-]*$"` + `"not": { "pattern": "\\.\\." }`.
**Файлы.** `schema/...json`.
**DoD.** `../x` отвергается; `fixtures/request.json` принимается.

---

## P2 — полнота, usability, синхронизация

### T2. Positive-покрытие всех MVP-типов шагов  ✅ ВЫПОЛНЕНО (2026-07-02)
**Сделано.** Добавлены `examples/valid/rest-get-flow.json` и `examples/valid/grpc-unary-draft.json`,
включены в `validExamples_pass` (теперь 4 valid-примера, каждый — 0 сообщений). Все MVP-типы шагов имеют
≥1 valid-пример; draft-`grpc.unary` доказан на приёмку схемой.
**Побочная находка:** схема `restStep` **не содержит `query`**, хотя парсер/wire его поддерживают — пример
`rest.get` пришлось делать без `query`. Кандидат в схемные follow-up рядом с «REST body assertions».

### T3. `oneOf` → `if/then` дискриминатор (usability для AI)  ✅ ВЫПОЛНЕНО (2026-07-02)
`oneOf` выдавал 25–32 сообщения на документ — релевантны 1–2, остальное шум по чужим веткам. **Сделано.**
`$defs.step` заменён на `type`-дискриминатор: `type`-enum на уровне шага (ловит unknown/missing `type` одним
сообщением) + `allOf` из `if/then` (по ветке на тип), каждая ветка ссылается на прежний `*Step`-def с его
`additionalProperties:false`. `*Step`-def не менялись. Регресс-тест `invalidStep_reportsFocusedErrors` в
`ScenarioSchemaValidationTest` проверяет отсутствие кросс-веточного шума. **Замерено:** invalid-примеры теперь
дают **1–2** сообщения (было 25–32). Все valid/invalid/T1/parity тесты зелёные, `./gradlew build` зелёный.

### R1. Синхронизировать rules-док с реальным энфорсментом  ✅ ВЫПОЛНЕНО (2026-07-02)
**Сделано.** В `stand-test-ai-generation-rules.md`: уточнены строки forbidden-ops (`THREAD_SLEEP` — +SQL
sleep-функции; `HARDCODED_STAND_URL` — +relative `path`/no `//host`; `SECRET_IN_SOURCE` → «schema (keys) +
prompt», т.к. схема ловит имена secret-заголовков, не значения); раздел *Assertions* уточнён (схема
принимает 5 matcher'ов, рантайм исполняет только `equals`; assertions — на `kafka.expect`, REST — только
`expect.status`); добавлен раздел *Schema vs runtime (executable subset)* (fixture-only тела, equals-only,
`singleValue` вместо `rowExists`, grpc draft, **уникальность `step.id` — рантайм, не схема** → закрывает
**G4**); минимальный пример переведён на `body.fixture`. `ForbiddenOperationCoverageTest` (11 кодов) зелёный.

### G6. `capture` value → JSONPath-паттерн  ✅ ВЫПОЛНЕНО (2026-07-02)
**Сделано.** В `$defs.captureMap.additionalProperties` добавлен `"pattern": "^\\$"` (значение должно
начинаться с `$`). Действует на все capture-мапы (rest/kafka.expect/grpc) через `$ref`. Тест
`nonJsonPathCapture_rejected`; valid-примеры (`$.requestId`, …) без изменений.

### G7. `rest.get` с `body`  ✅ ВЫПОЛНЕНО (2026-07-02, решение: deny)
**Решено — запретить.** В `$defs.restStep` добавлен `allOf` c `if type==rest.get then not(required body)`;
`rest.post` не затронут. Тест `restGetWithBody_rejected`. Побочно: `GuardrailHoleTest.g5` переведён на
`rest.post` (его benign-кейс раньше опирался на GET+body — теперь корректно отвергается).

### G4. Уникальность `step.id`  ✅ ВЫПОЛНЕНО (2026-07-02, вместе с R1)
Задокументировано в rules-доке (раздел *Schema vs runtime*): id уникальны, проверяет рантайм-валидатор
(`STEP_ID_DUPLICATE`), не схема.

### C1. Хрупкость coverage-гейта  ✅ ВЫПОЛНЕНО (2026-07-02)
**Сделано (вариант a).** Тело чтения потока вынесено в package-private `AiSchemaResources.readAll(InputStream)`;
тест `unreadableStream_wrapped` подаёт поток, чей `read()` бросает `IOException`, и проверяет
`UncheckedIOException` — покрыт ранее недостижимый `catch`. Покрытие `AiSchemaResources` теперь **100%**
(было 82.9%); хрупкости 80%-гейта больше нет. `./gradlew :stand-test-ai-schema:build` зелёный.

### R2 / D1 / consistency (мелочи)  ✅ ВЫПОЛНЕНО (2026-07-02)
- **R2.** ✅ `ForbiddenOperationCoverageTest` усилен: проверяет заголовок таблицы `| Code | Meaning | Layer |`
  и что каждый `ForbiddenOperation.code()` присутствует **как ячейка строки** (`` | `CODE` | ``), а не просто
  где-то в prose. Код, выпавший из каталога-таблицы, теперь ломает сборку.
- **D1.** ✅ (решение зафиксировано) `stand-test-core` остаётся `testImplementation` у ai-schema — main-код
  его не использует; поднять до `api` только когда появится программный Java-`GuardrailCatalog` (тогда
  «единый источник» станет и рантайм-привязкой). Кода не меняем.
- **consistency.** ✅ Введён `$defs/identifier` (`minLength:1` + `^[A-Za-z0-9][A-Za-z0-9._-]*$`); на него
  ссылаются top-level `id` и все 5 step-`id`. Тест `malformedStepId_rejected` (id с пробелом → отклонён).
  Сгенерированные парсером id (`rest.get#0`) — post-validation, паттерн их не затрагивает.

---

## Порядок выполнения (рекомендуемый)

1. **A1 (P0)** — зафиксировать решение по формату (гейт для схемных правок, привязанных к полям).
2. **T1 (P1)** — усилить invalid-тесты (ловит регрессии всех последующих фиксов).
3. **G2 → G1 → G3 → G5 (P1)** — закрыть дыры паттернами значений (format-agnostic, можно до/параллельно A1).
4. **T2 (P2)** — valid-примеры на `rest.get`/`grpc.unary`.
5. **R1 + G4 + G6 (P2)** — синхронизировать rules-док и добить value-проверки.
6. **T3 (P2)** — миграция на `if/then` (после A1, крупнее прочих).
7. **C1 / R2 / D1 / consistency (P2)** — зачистка.

## Общий DoD доработки
- [ ] Решение A1 зафиксировано; при вариантах 2/3 — parity-тест в `stand-test-example` зелёный.
- [ ] Все P1-дыры (G1/G2/G3/G5) закрыты и покрыты invalid-тестами с проверкой причины (T1).
- [ ] Каждый MVP-тип шага имеет valid-пример (T2).
- [ ] Rules-док не завышает энфорсмент (R1); документированы G4-уникальность и слои.
- [ ] `./gradlew :stand-test-ai-schema:build` и `./gradlew build` зелёные; покрытие ≥80% с осознанным запасом.
