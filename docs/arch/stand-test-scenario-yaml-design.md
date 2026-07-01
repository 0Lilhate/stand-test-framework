# stand-test-scenario-yaml — design (Итерация 9, design-only)

Самодостаточный **дизайн-документ** YAML DSL (§7 Итерация 9: «Черновик схемы; план парсера; план раннера —
**без реализации**»). Кода нет; это фиксация решений, чтобы будущая реализация была механической. Источник
истины контрактов: `stand-test-sdk-implementation-plan.md` — **§3** (единая Scenario Model), **§4**
(`stand-test-scenario-yaml`/`ai-schema`), **§5** (граф), **§8.6** (`ForbiddenOperation`), **§9** (env-model),
**§11** (YAML draft). Формальную JSON Schema и вывод forbidden-operations оставляем **Итерации 10**
(`stand-test-ai-schema`) — она деривует их из того же core-контракта.

> **Статус:** РЕАЛИЗОВАНО. `YamlScenarioParser` (SnakeYAML `SafeConstructor`) парсит YAML → `Scenario`
> (модуль core-only + внешняя snakeyaml; парсер строит модель и ничего не исполняет). Дизайн-решения ниже
> актуальны; открытые вопросы разрешены при реализации (см. §«Отложено / открытые вопросы»).

## Что проектируется / чего НЕ проектируется

**Проектируется (этот документ):** (1) поверхностная YAML-схема сценария; (2) отображение surface→internal
на generic `Scenario`/`GenericStep`; (3) план парсера (библиотека, пайплайн, ошибки); (4) план раннера
(переиспользование, SPI, resolve `${...}`); (5) как формат структурно предотвращает `ForbiddenOperation`;
(6) граф модуля и внешние зависимости.

**НЕ проектируется здесь:** реализация парсера/loader'а; формальная JSON Schema (Итерация 10); YAML-runner
как отдельный движок (раннер один — core `DefaultScenarioRunner`); поддержка «escape в Java» (запрещена, §4);
gRPC-шаги (после `stand-test-grpc`).

---

## Решение 1 — YAML это второй ВХОД в ту же Scenario Model (не второй раннер)

Оба DSL сходятся в одну immutable модель, исполняемую одним конвейером (§3):

```
Java DSL ─┐
          ├─▶ Scenario (core) ─▶ DefaultScenarioValidator ─▶ DefaultScenarioRunner ─▶ StepExecutor SPI ─▶ адаптеры
YAML DSL ─┘   (parser строит ту же модель — GenericStep по типу шага)
```

Следствия (нормативно): YAML-парсер **строит `Scenario` из `GenericStep`** и больше ничего не исполняет;
он **не дублирует раннер** и **не имеет compile-time рёбер на адаптеры** — исполнители резолвятся через
core-`StepExecutor` SPI в рантайме (`ServiceLoader`), как в `StandTestExtension.buildStandClient()`. Парсер
JDK-совместим по духу core: зависит только от `stand-test-core` (+ внешний YAML-парсер).

---

## Решение 2 — Поверхностная YAML-схема (эргономичная, из §11)

Схема сознательно **эргономична для человека и AI** и отличается от внутренних ключей `GenericStep`
(парсер переводит — см. Решение 3). Верхний уровень сценария:

```yaml
id: example-flow                 # обязателен → Scenario.builder(id)
title: Example async flow        # опц. → .title(...)
description: ...                 # опц. → .description(...)
env: ift                         # обязателен, whitelisted-алиас окружения (§9) → .environment(env)
tags: [integration, kafka, db]   # опц. → .tag(...) на каждый

given: [ <step>, ... ]           # шаги «до триггера» (setup/act)
then:  [ <step>, ... ]           # шаги «после» (await/assert)
```

`given`/`then` — **читаемое разделение**, как в §11/§10; в модели оба списка **конкатенируются по порядку**
в единый `List<ScenarioStep>` (`given` затем `then`) — раннер исполняет их последовательно, а фаза `prepare`
(§8.7, напр. арм Kafka-consumer) всё равно проходит по всем шагам до любого `execute`. Каждый `<step>` —
одноключевая мапа `{<stepType>: {<surface-поля>}}`, где `<stepType>` ∈ {`rest.<method>`, `kafka.send`,
`kafka.expect`, `db.query`, `db.expectEventually`, `db.seed`, `db.cleanup`} (по префиксам
`RestStepParameters.TYPE_PREFIX="rest."`, `KafkaStepParameters="kafka."`, `DbStepParameters="db."`).

**Идентификатор шага (`id`):** опц. поле `id:` в surface; при отсутствии парсер генерирует детерминированно
(`<type>#<index>`, напр. `rest.post#0`), т.к. `ScenarioStep.id()` обязан быть non-blank и уникальным
(валидатор: `STEP_ID_REQUIRED`/`STEP_ID_DUPLICATE`).

**Секреты/URL — только ссылки (§9, §8.6 `SECRET_IN_SOURCE`/`HARDCODED_STAND_URL`).** YAML называет
**алиасы** (`service`/`topic`/`datasource`/env), а не URL/секреты; endpoint'ы и креды резолвятся адаптерами
из env-ref (`baseUrlRef`/`urlRef`/`userRef`/`passwordRef`/`bootstrapServersRef`) в рантайме. Литеральный URL
или секрет в YAML — вне схемы (и будущий guardrail Итерации 10 его отвергнет).

---

## Решение 3 — Отображение surface → internal (ядро дизайна)

Парсер переводит эргономичный surface в точные ключи `GenericStep.parameters`, которые читают адаптерные
executor'ы. **Общие эргономичные преобразования:**

| Surface (YAML) | Internal (`GenericStep.parameters`) | Примечание |
|---|---|---|
| `timeout: 30s` / `20s` | `timeoutMillis: 30000` (long) | парсинг `<n>s`/`<n>ms`; дефолт `DEFAULT_TIMEOUT_MILLIS=30000` |
| `pollInterval: 200ms` / `pollTimeout: 500ms` | `pollIntervalMillis` (db) / `pollTimeoutMillis` (kafka) | дефолты `200`/`500` |
| `assert: {"$.path": value, ...}` | `assertions: [{jsonPath, expectedValue}, ...]` | map(path→value) → list; порядок из `LinkedHashMap` |
| `capture: {var: "$.path", ...}` | `captures: [{variableName, jsonPath}, ...]` | map(var→path) → list |
| `body: fixtures/x.json` | `bodyResource: "fixtures/x.json"` | путь-строка → *_RESOURCE; inline-строка → `body` |
| `${name}` внутри строк | сохраняется дословно | резолв в рантайме `VariableResolver` (Решение 5) |

**По типам шагов** (surface-поля → internal-ключи; ключи — из `*StepParameters`):

- **`rest.<method>`** (`type="rest.post"` и т.п.; `METHOD` берётся из суффикса): `service`→`SERVICE`,
  `path`→`PATH`, `query`→`QUERY` (Map), `headers`→`HEADERS` (Map), `body`/`body: <resource>`→`BODY`/`BODY_RESOURCE`,
  `injectCorrelationId`→`INJECT_CORRELATION_ID` (bool), `expectStatus`→`EXPECTED_STATUS` (Integer),
  `assert`→`ASSERTIONS`, `capture`→`CAPTURES`.
- **`kafka.send`**: `topic`→`TOPIC`, `body`/`<resource>`→`BODY`/`BODY_RESOURCE`, `key`→`KEY`,
  `headers`→`HEADERS`, `injectCorrelationId`→`INJECT_CORRELATION_ID`.
- **`kafka.expect`**: `topic`→`TOPIC`, `correlationIdFromContext`→`CORRELATION_FROM_CONTEXT` (bool),
  `timeout`→`TIMEOUT_MILLIS`, `pollTimeout`→`POLL_TIMEOUT_MILLIS`, `assert`→`ASSERTIONS`, `capture`→`CAPTURES`,
  `key`→`KEY` (дискриминатор).
- **`db.query` / `db.expectEventually`**: `datasource`→`DATASOURCE`, `query`/`sql`→`SQL` (или `<resource>`→`SQL_RESOURCE`),
  `params`→`PARAMS` (Map), `capture`→`CAPTURES` (nested `column`/`variableName`), для expect: `equals`→`EXPECTED_VALUE`,
  `timeout`→`TIMEOUT_MILLIS`, `pollInterval`→`POLL_INTERVAL_MILLIS`.
- **`db.seed` / `db.cleanup`** (write, только при `writeAllowed`, §8.8): `datasource`→`DATASOURCE`,
  `sql`/`<resource>`→`SQL`/`SQL_RESOURCE`, `params`→`PARAMS`; для cleanup `whereTestRunId: <col>`→`WHERE_TEST_RUN_ID_COLUMN`
  (иначе DELETE/UPDATE классифицируется destructive и отвергается — §8.8).

> Замечание: surface-имена (`expectStatus`, `equals`, `assert`, `capture`, `timeout: 30s`) — из §11-draft;
> internal-ключи — из отгруженных `RestStepParameters`/`DbStepParameters`/`KafkaStepParameters`. §11 остаётся
> «человеческим» черновиком; данный документ формализует перевод. Значения-мапы должны удовлетворять типам,
> которые ждут executor'ы (`EXPECTED_STATUS`→Integer, `HEADERS`/`PARAMS`→Map, `ASSERTIONS`/`CAPTURES`→List<Map>).

---

## Решение 4 — План парсера

**Библиотека — SnakeYAML** (не `jackson-dataformat-yaml`): каталог **сознательно избегает Jackson**
(`libs.versions.toml`: «json-path uses the json-smart provider (no Jackson)»); SnakeYAML лёгок и его
`load()` даёт естественное дерево `Map<String,Object>`/`List`/scalar, ложащееся прямо в `GenericStep.parameters`.
**Безопасная загрузка обязательна:** `new Yaml(new SafeConstructor(new LoaderOptions()))` (без произвольной
инстанциации типов; ограничить размер/глубину `LoaderOptions`) — иначе риск класса CVE-2022-1471. Внешняя
зависимость модуля: `org.yaml:snakeyaml` (добавить в version-catalog при реализации).

**Пайплайн (draft, будущий `YamlScenarioParser`):**
1. `load` YAML → дерево `Map<String,Object>` (safe-loader).
2. Валидация верхнего уровня: обязательны `id`, `env`, ≥1 шаг (`given`/`then`); неизвестные top-level ключи → ошибка.
3. Конкатенация `given`+`then` по порядку; на каждый `<step>`:
   - извлечь единственный ключ = `stepType`; проверить префикс (`rest.`/`kafka.`/`db.`); неизвестный тип → ошибка
     **на этапе парсинга** (не откладывать до раннера, который бросит `No step executor registered`);
   - перевести surface-поля → internal-ключи (Решение 3); собрать `GenericStep.of(id, type, description)`
     + `parameters` (через будущий `GenericStep` c parameters — сейчас есть `of(id,type[,desc])`; для params
     нужен конструктор/билдер с мапой, он у record уже есть как канонический ctor).
4. Собрать `Scenario.builder(id).environment(env).title(...).description(...).tag(...)×N.steps(list).build()`.
5. Вернуть `Scenario` — **ничего не исполняя**.

**Ошибки:** структурные проблемы YAML → `StandTestException` (config-класс, §8.3) с **локацией** (путь
шага/поля, напр. `then[1].kafka.expect.timeout`), чтобы AI/человек чинил точечно. Парсер **fail-closed**:
непонятное отвергается, а не игнорируется. `${...}`-плейсхолдеры парсер **не резолвит** (оставляет строкам).

**Чего парсер НЕ делает:** не открывает соединения, не читает env, не исполняет шаги, не резолвит `${...}`,
не резолвит алиасы окружения (это рантайм адаптеров/раннера).

---

## Решение 5 — План раннера (переиспользование, без нового движка)

YAML **не вводит раннер**. Будущий тонкий `YamlScenarioLoader` (или статический хелпер) читает файл →
`YamlScenarioParser.parse(...)` → `Scenario`, а исполнение — тем же путём, что Java DSL:

```
Scenario  ->  StandClient.run(scenario)   // DefaultStandClient → DefaultScenarioRunner
                                           // executors: ServiceLoader.load(StepExecutor.class)
                                           // registry: EnvironmentRegistry (SPI, §9) → резолв алиасов
                                           // VariableResolver: ${...} в рантайме (built-ins + captures)
```

- **Исполнители — через SPI**, не compile-time: `scenario-yaml` не зависит от `rest`/`kafka`/`db`; на
  classpath потребителя нужные адаптеры регистрируют `StepExecutor` в `META-INF/services` (как сейчас).
- **Валидация** — общий `DefaultScenarioValidator` (id/env/steps/uniq-id/type). Whitelist окружения и
  forbidden-op enforcement **отложены** (comment в валидаторе) и будут добавлены единообразно для обоих
  входов; их источник — `ForbiddenOperation`/`EnvironmentRegistry` (Итерация 10).
- **`${...}`** — резолвится `VariableResolver` в рантайме: built-ins `${scenarioId}`/`${testRunId}`/
  `${correlationId}`/`${environment}` + пользовательские из `VariableStore` (captures предыдущих шагов);
  неизвестная переменная → `StandTestException("Unresolved variable ...")`. YAML лишь **несёт** плейсхолдеры.

---

## Решение 6 — Как декларативный формат гасит `ForbiddenOperation` (§8.6)

Формат спроектирован так, что часть запретов **невозможна структурно** (а не только проверяется):

| `ForbiddenOperation` | Как гасится YAML-форматом |
|---|---|
| `IMPERATIVE_EAGER_IO`, `RAW_KAFKA_CLIENT`, `RAW_JDBC_CLIENT` | нет «escape в Java»/произвольного кода — только декларативные шаги через SPI |
| `HARDCODED_STAND_URL`, `NON_WHITELISTED_ENVIRONMENT`/`_DATASOURCE` | только **алиасы** (`service`/`datasource`/`topic`/`env`), резолвятся `EnvironmentRegistry`; литералов URL нет в схеме |
| `SECRET_IN_SOURCE` | только env-ref в конфиге окружения; YAML не содержит значений секретов |
| `THREAD_SLEEP` | ожидание только декларативное (`kafka.expect`/`db.expectEventually` + `timeout`) — нет sleep |
| `DESTRUCTIVE_SQL_WITHOUT_ALLOW` | write только `db.seed`/`db.cleanup` при `writeAllowed`; cleanup требует `whereTestRunId` (§8.8) |
| `FIXED_TEST_DATA_ID` | поощряются `${testRunId}`/captured-id; проверка — уровень guardrail (Итерация 10) |
| `BUSINESS_LOGIC_IN_SDK` | YAML описывает шаги SDK, бизнес-логика вне формата |

**Enforcement-слой** (whitelist + классификация forbidden-ops) — общий с Java-входом и **деривуется из core**
(`ForbiddenOperation`, `EnvironmentRegistry`); формальная JSON Schema допустимого YAML — **Итерация 10**
(`stand-test-ai-schema`, §4), которая ведёт схему/список не отдельно, а из core-контракта.

---

## Модуль и граф

- **Зависимости:** `stand-test-scenario-yaml` → **только `stand-test-core`** (§5); внешняя — SnakeYAML.
  **Нет** compile-рёбер на `rest`/`kafka`/`db`/`grpc`/`await` — адаптеры через SPI в рантайме. Граф ацикличен.
- **Правка README (в этой итерации).** `stand-test-scenario-yaml/README.md` сейчас указывает
  «Planned internal dependencies: core, await, rest, kafka, db, grpc» — **противоречит** §4/§5 (core-only).
  Привести к «**только `stand-test-core`**; адаптеры — через `StepExecutor` SPI в рантайме, без compile-рёбер».
- **Скелет-стаб в `build.gradle.kts`** (`// api(project(":stand-test-core"))`) остаётся закомментированным
  до реализации; при реализации раскомментировать **только** core + добавить SnakeYAML.

## Связь с Итерацией 10 (`stand-test-ai-schema`)

Итерация 9 задаёт **структуру** YAML (эта схема) и **перевод** в модель. Итерация 10 берёт её и добавляет
**машиночитаемую JSON Schema** допустимого сценария + guardrails генерации, **производные** из
`ForbiddenOperation`/`EnvironmentRegistry` (не отдельный список). `ai-schema` → только `core`, **без**
`scenario-yaml` и адаптеров (§4/§5). Т.е. схема-для-людей (здесь) и схема-для-машин/AI (там) согласованы,
но не дублируют источник истины.

## Разрешённые при реализации решения / отложенное

Разрешено (реализовано в `YamlScenarioParser`):
- **`given`/`then` семантика:** просто порядок (конкатенация `given`+`then` в единый список). Жёсткого
  разделения (напр. запрет assert в `given`) не вводим — влияло бы лишь на дружелюбность ошибок, не на модель.
- **Инлайн vs ресурс:** **явные surface-ключи** `body:`/`bodyResource:` и `sql:`/`sqlResource:` (не
  эвристика «путь-подобная строка»); указать оба → ошибка. Однозначно и AI-safe.
- **Дюрации:** `<n>s` / `<n>ms` / bare-число (=ms) → `Long` millis; невалид/≤0 → ошибка. ISO-8601 не вводим.
- **Wire-ключи** централизованы в `YamlStepKeys` (хардкод-литералы, mirror `*StepParameters`), т.к. модуль
  core-only. **Follow-up:** поднять константы ключей в `core`, чтобы убрать дублирование парсер↔адаптеры.

Отложено:
- **gRPC-шаги** — вне схемы до `stand-test-grpc`.
- **JSON Schema + forbidden-op enforcement** — Итерация 10 (`ai-schema`), из core-контракта.
- **Опц. YAML-раннер-удобство** в junit-слое (`@StandTest` + путь к YAML) — отдельно.

## Definition of Done

- [x] Дизайн-документ (этот файл): схема, surface→internal маппинг, план парсера/раннера, гашение
      `ForbiddenOperation`, граф.
- [x] README `scenario-yaml` — core-only (устранено противоречие с §4/§5).
- [x] Ссылки на этот документ из §4/§11 мастер-плана.
- [x] **Реализация** `YamlScenarioParser` (SnakeYAML `SafeConstructor`): rest/kafka/db surface → корректный
      `Scenario`; типы (`expectedStatus` Integer, `*_MILLIS` Long, флаги Boolean, assert/capture List<Map>);
      fail-closed ошибки с локацией; проходит `DefaultScenarioValidator`; core-only (compileClasspath = core
      + snakeyaml); JaCoCo ≥80%.
