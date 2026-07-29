# stand-test-ai-schema — design (Итерация 10, design-only)

Самодостаточный **дизайн-документ** модуля AI-guardrails (§7 Итерация 10: «JSON Schema (из core-контракта);
forbidden operations; правила генерации»). Кода нет — фиксация решений, чтобы реализация была механической
(как `stand-test-scenario-yaml-design.md` для Итерации 9). Источник истины: `stand-test-sdk-implementation-plan.md`
— **§4** (`stand-test-ai-schema`), **§5** (граф), **§8.6** (`ForbiddenOperation` — единый источник),
**§8.8** (двухслойный enforcement), **§9** (env-model), **§19** (риск drift); плюс
`docs/arch/stand-test-scenario-yaml-design.md` (surface-схема) и core-контракты.

> **Статус:** дизайн. Модуль `stand-test-ai-schema` — скелет (`package-info.java`). Пред-условие §4
> «отложено до стабилизации YAML DSL» **выполнено** (YAML DSL реализован, Итерация 9). Реализация — отдельно.

> **Зачем этот документ.** Разбор показал: §4/§8.6 задают направление, но недостаточны и в одной точке
> неточны для прямой реализации. Ключевые нерешённости, которые тут закрываются: (а) что валидирует JSON
> Schema; (б) static-vs-runtime split; (в) честная модель «single source» (enum `ForbiddenOperation` —
> **inert**, авто-генерация из него невозможна); (г) выбор JSON-Schema-библиотеки vs «no Jackson»;
> (д) устаревший README модуля.

## Что проектируется / чего НЕ проектируется
**Проектируется:** (1) что и как валидирует JSON Schema (surface-YAML); (2) разделение static (schema) vs
runtime (validator) guardrails; (3) rule-catalog, ключуемый по `ForbiddenOperation`, + cross-check-тест как
реальный механизм «единого источника»; (4) граф/зависимости и выбор JSON-Schema-инструмента; (5)
репрезентативный фрагмент JSON Schema; (6) определение «правил генерации».

**НЕ проектируется:** сама реализация; полный текст JSON Schema (только показательный фрагмент);
**реализация отложенных рантайм-проверок** в `DefaultScenarioValidator` (это core-задача, §Решение 6 —
companion, не ai-schema); исполнение сценариев.

---

## Решение 1 — JSON Schema валидирует **surface-YAML** (зеркало Итерации 9), не generic-модель

AI генерирует **YAML** (surface-формат Итерации 9), поэтому JSON Schema описывает **поверхностную
грамматику YAML** (`id/env/tags/given/then`, шаги `rest.*`/`kafka.send|expect`/`db.*`, эргономичные поля),
а не внутреннюю `GenericStep`-модель. Это ловит некорректный вывод AI **до** парсера.

Авторитетные списки полей — `KNOWN`-сеты translator'ов `stand-test-scenario-yaml` (`RestStepTranslator`/
`KafkaStepTranslator`/`DbStepTranslator`) + таблица §Решение 3 дизайн-дока scenario-yaml. JSON Schema их
**зеркалит** с `additionalProperties: false` (JSON-Schema-эквивалент fail-closed `checkKnownKeys`).

**⚠️ Coupling (как в scenario-yaml).** ai-schema — **core-only**, импортировать `scenario-yaml` нельзя (§5),
значит surface-поля **дублируются** в JSON Schema (второй mirror после `YamlStepKeys`). Митигация:
- **Golden-тесты в ai-schema**: набор valid/invalid YAML-сэмплов валидируется против схемы (schema
  принимает корректный surface, отвергает неизвестные поля/типы).
- **Parity-тест в `stand-test-example`** (единственный модуль, который тестово видит и `scenario-yaml`, и
  сможет `testImplementation` ai-schema): один и тот же YAML, принятый JSON-схемой, **успешно парсится**
  `YamlScenarioParser` — ловит расхождение схемы и парсера.
- **Follow-up** (общий с scenario-yaml): поднять surface-ключи/поля в единый источник в `core`.

---

## Решение 2 — Static (schema) vs runtime (validator) — ключевой split

Два слоя guardrails, разделённые по тому, что **знаемо статически** (§8.8 defense-in-depth):

**Static — JSON Schema (ai-schema), без знания окружения/IO:**
- разрешённые типы шагов + per-type поля (из `KNOWN`), типы/формы значений, обязательные поля,
  `additionalProperties:false`.
- Структурно-невозможные запреты (формат просто не имеет таких полей): `IMPERATIVE_EAGER_IO`,
  `RAW_KAFKA_CLIENT`, `RAW_JDBC_CLIENT` (нет «escape в Java»), `HARDCODED_STAND_URL` (нет `url:` — только
  алиасы), `THREAD_SLEEP` (нет `sleep:` — ожидание только `timeout`), `DESTRUCTIVE_SQL_WITHOUT_ALLOW`
  частично (write только `db.seed/db.cleanup`; cleanup требует `whereTestRunId` — схема это форсит).

**Runtime — `ScenarioValidator` + адаптеры (нужны env/registry/семантика SQL):**
- `NON_WHITELISTED_ENVIRONMENT`/`_DATASOURCE`: алиасы (`service`/`topic`/`datasource`) лежат в
  `EnvironmentDefinition`-мапах **per-environment, только в рантайме** → **статически перечислить в
  универсальной JSON Schema нельзя** (подтверждено кодом). Whitelist — рантайм через `EnvironmentRegistry`.
- `DESTRUCTIVE_SQL_WITHOUT_ALLOW` (семантика): реальный разбор SQL — `SqlStatementClassifier`/`DbWriteGuard`
  (рантайм; это **настоящий** «источник с логикой», в отличие от inert-enum).
- `FIXED_TEST_DATA_ID`, `SECRET_IN_SOURCE` (значения): эвристика/семантика — рантайм/advisory.

**Вывод:** JSON Schema = **структурный pre-parse-гейт + guidance для AI-промпта**; **энфорсмент-гейт** —
рантайм `ScenarioValidator` (чьи whitelist/forbidden-op-проверки сейчас **отложены**, §Решение 6). ai-schema
**не перечисляет алиасы**. Опционально — **per-environment schema builder**: если дан `EnvironmentDefinition`,
сгенерировать вариант схемы с `enum` реальных алиасов; по умолчанию — generic (`service: {type: string}`).

---

## Решение 3 — Честный «единый источник»: rule-catalog, ключуемый по `ForbiddenOperation` + cross-check

**Факт (сверено кодом):** `ForbiddenOperation` — enum из 11 констант `(code, human-description)`, **inert**:
ни правил, ни target-типов, ни severity. **Механически сгенерировать ограничения из него НЕЛЬЗЯ** —
формулировка §4 «производный/генерирует» оптимистична. Реальный механизм «не отдельный список» (§8.6):

- ai-schema (в `core`-only) заводит **`GuardrailRule`** каталог: **по одной записи на каждый
  `ForbiddenOperation`**, описывающей `operation`, `enforcement` (`SCHEMA_STRUCTURAL` / `RUNTIME_VALIDATOR` /
  `ADAPTER_RUNTIME` / `PROMPT_ONLY`), и как именно гасится. Правила **hand-authored** (иначе никак).
- **Coverage cross-check-тест**: `assertThat(catalog.keySet()).containsExactlyInAnyOrder(ForbiddenOperation.values())`
  — новый `ForbiddenOperation` без записи в каталоге **ломает сборку**. Это и есть «единый источник»:
  enum — чеклист, каталог — реализация, тест — 1:1-связка (нет drift, §19). ForbiddenOperation импортируется
  из `core` — единственная code-зависимость на «источник».
- **Промпт-guidance** для AI деривуется из `ForbiddenOperation.description()` (человекочитаемо) + категории
  каталога. Это удовлетворяет §8.6 «генерирует/использует из core, не ведёт отдельный список» **честно**.

---

## Решение 4 — Граф, зависимости и выбор JSON-Schema-инструмента

- **`stand-test-ai-schema` → только `stand-test-core`** (§5): нужен `ForbiddenOperation` (cross-check) и,
  для опц. per-env builder, типы `EnvironmentDefinition`/`*Definition`. **Без** адаптеров, **без**
  `scenario-yaml`, без исполнения. Граф ацикличен, `ai-schema` — sink.
- **JSON Schema — как ресурс + библиотека валидации.** §4 приводит `networknt/json-schema-validator` как
  пример, **но `networknt` тянет Jackson (`jackson-databind`)** — а репозиторий Jackson **сознательно
  избегает** (`libs.versions.toml`: json-path на json-smart). Решение:
  - Основной артефакт — **сама JSON Schema (ресурс `META-INF`/resources)** + `GuardrailRule`-каталог;
    это Jackson не требует.
  - Для валидации YAML против схемы (golden-тесты, AI-тулинг) выбрать валидатор **без Jackson** если API
    достаточно — напр. `everit-org/json-schema` (на `org.json`); **иначе**, если берём `networknt`, Jackson
    остаётся **изолированным в ai-schema** (это dev/AI-tooling-модуль, вне горячего test-runtime пути
    потребителя — не нарушает «no Jackson» на основном graph'е). **Рекомендация:** сначала оценить
    non-Jackson-опцию; при выборе `networknt` — задокументировать изоляцию Jackson. Добавить выбранную
    координату в `libs.versions.toml`.

---

## Решение 5 — Как выглядит JSON Schema (показательный фрагмент)

Top-level + `given`/`then` как массивы step-node'ов; каждый step-node — one-key объект, диспетчеризуемый
`oneOf` по имени шага; `additionalProperties:false` форсит fail-closed. Draft (JSON Schema 2020-12):

```jsonc
{
  "type": "object",
  "additionalProperties": false,
  "required": ["id", "env"],
  "properties": {
    "id": { "type": "string", "minLength": 1 },
    "env": { "type": "string", "minLength": 1 },
    "title": { "type": "string" },
    "description": { "type": "string" },
    "tags": { "type": "array", "items": { "type": "string", "minLength": 1 } },
    "given": { "type": "array", "items": { "$ref": "#/$defs/step" } },
    "then":  { "type": "array", "items": { "$ref": "#/$defs/step" } }
  },
  "$defs": {
    "duration": { "oneOf": [ { "type": "integer", "minimum": 1 }, { "type": "string", "pattern": "^[0-9]+(ms|s)$" } ] },
    "step": { "type": "object", "minProperties": 1, "maxProperties": 1, "additionalProperties": false,
      "properties": {
        "rest.post":  { "$ref": "#/$defs/restStep" },
        "rest.get":   { "$ref": "#/$defs/restStep" },
        "kafka.send": { "$ref": "#/$defs/kafkaSend" },
        "kafka.expect": { "$ref": "#/$defs/kafkaExpect" },
        "db.expectEventually": { "$ref": "#/$defs/dbExpect" }
        // db.query/seed/cleanup, rest.put/delete — аналогично
      }
    },
    "restStep": { "type": "object", "additionalProperties": false,
      "required": ["service", "path"],
      "properties": {
        "id": { "type": "string" }, "service": { "type": "string" }, "path": { "type": "string" },
        "query": { "type": "object" }, "headers": { "type": "object" },
        "body": { "type": "string" }, "bodyResource": { "type": "string" },
        "injectCorrelationId": { "type": "boolean" },
        "expectStatus": { "type": "integer" },
        "assert": { "type": "object" }, "capture": { "type": "object" }
      },
      "not": { "required": ["body", "bodyResource"] }   // body XOR bodyResource
    }
    // kafkaSend/kafkaExpect/dbExpect — зеркалят SEND_KNOWN/EXPECT_KNOWN/EXPECT_KNOWN + required (equals для expect, whereTestRunId для cleanup)
  }
}
```

Ключевое: `additionalProperties:false` (= `checkKnownKeys`), `required` (= обязательные surface-поля),
`duration`-паттерн (`<n>s`/`<n>ms`/int), `not required both body/bodyResource` (= mutual-exclusion). Алиасы —
`type:string` (whitelist рантайм), либо `enum` в per-env-варианте (Решение 2).

---

## Решение 6 — «Правила генерации» и companion-задача рантайм-энфорсмента

**«Правила генерации» = три категории (в `GuardrailRule.enforcement`):**
- **`SCHEMA_STRUCTURAL`** — форсится JSON-схемой (структура, невозможные-по-формату запреты).
- **`RUNTIME_VALIDATOR`** — семантика/env (whitelist, destructive-SQL) → `ScenarioValidator`/адаптеры.
- **`PROMPT_ONLY`** — advisory для AI (из `description`), не автоматизируемо (напр. `FIXED_TEST_DATA_ID`).

**Companion (не ai-schema, отметить как пред-/со-условие).** Чтобы «единый источник» действительно бил в
рантайме, **отложенные** проверки `DefaultScenarioValidator` (`whitelist`/`forbidden-op`, «intentionally out
of scope») надо реализовать — но это принадлежит **`core`** (`ScenarioValidator` потребляет
`ForbiddenOperation` + `EnvironmentRegistry`), а не ai-schema (тот core-only, без рантайма). Т.е. Итерация 10
даёт **shared rule-catalog + JSON Schema (статический слой)**; рантайм-энфорсмент — отдельная core-задача,
ключующаяся по тому же `ForbiddenOperation`/каталогу. Оба конца сходятся на enum → нет drift.

---

## Противоречия из разбора — как разрешены
- **«без scenario-yaml» vs «Итерация 10 берёт её схему»** → ai-schema берёт **спецификацию** (дизайн-док
  §Решение 3 + `KNOWN`-сеты как справочник), **не** compile-dep; JSON Schema авторится вручную по ней
  (Решение 1), parity гарантирует тест в `example`.
- **§8.6 «единый источник» vs §8.8 два слоя** → явный static/runtime split (Решение 2); каталог
  (Решение 3) знает про оба слоя через `enforcement`.
- **README модуля устарел** (deps = core + scenario-yaml) vs план/`build.gradle.kts` (core-only) → README
  правится в этой итерации (см. DoD).

## Follow-ups
- Поднять surface-поля/ключи в единый источник в `core` (общий с scenario-yaml — убирает **оба** mirror'а:
  `YamlStepKeys` и JSON-Schema).
- Реализовать отложенные рантайм-проверки в `core` `ScenarioValidator` (companion, Решение 6).
- Per-environment schema-builder (Решение 2) — если понадобится строгий alias-`enum`.

## Definition of Done (design-only)
- [x] Дизайн-документ (этот файл): surface-target схемы, static/runtime split, честный single-source
      (rule-catalog + cross-check), граф/lib-выбор, показательный JSON Schema, определение guardrails.
- [ ] README `stand-test-ai-schema` → core-only (устранить stale «+scenario-yaml»).
- [ ] Ссылка на этот документ из §4/§8.6 мастер-плана.
- [ ] (в плане отметить) реалистичная формулировка «derive»: hand-authored каталог + cross-check против
      inert-enum, а не авто-генерация.
- Кода/зависимостей не добавляется; сборка не меняется. Реализация — отдельной итерацией.
