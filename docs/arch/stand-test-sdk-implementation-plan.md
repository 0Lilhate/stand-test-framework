# stand-test-sdk — План реализации

> Статус: **Implemented** (обновлено 2026-07-03; первоначальный draft — 2026-06-26). Архитектурный
> план внутренней библиотеки стендовых автотестов `stand-test-sdk`.
> Итерации 0–10 и follow-on-модули (`config`, `spring-boot-starter`, `scenario-yaml`, `ai-schema`)
> **реализованы**; все описанные сущности (`ScenarioContext`, `Awaiter`, `@StandTest`, …) существуют в
> коде. Документ остаётся источником истины по контрактам, границам модулей и порядку зависимостей:
> при расхождении кода и плана план правится отдельным осознанным решением.
> Координаты артефактов: group `ru.alfa.stand.test`, текущая версия `0.1.0-SNAPSHOT`
> (далее в примерах — `<version>`), базовый пакет `ru.alfa.stand.test.<module>`.

---

## 1. Назначение библиотеки

`stand-test-sdk` — это **тонкий SDK/фасад** для написания автотестов против **реальных
DEV/IFT-стендов** (не Testcontainers как основа). Библиотека подключается как test-зависимость и
даёт единый стиль описания интеграционных и e2e бизнес-флоу, в которых одновременно участвуют REST,
Kafka, проверки БД, gRPC, асинхронные ожидания, отчётность Allure и JUnit 5.

**Проблема, которую решаем.** Сейчас стендовые автотесты пишутся вразнобой: у каждой команды свой
способ дернуть REST, прочитать из Kafka, сходить в БД, «подождать» эффект (часто `Thread.sleep`),
прикрепить артефакты в отчёт. Результат — flaky-тесты, несравнимые отчёты, скрытые подключения к
неописанным стендам, секреты в коде и невозможность безопасно генерировать тесты AI-агентом.

**Что библиотека фиксирует как НЕ-цели (важно):**

- **Не заменяет JUnit 5 как test engine.** Жизненный цикл и запуск остаются за JUnit; SDK
  интегрируется в него через extension. Но **SDK-level assertions пробрасываются как
  JUnit-compatible failures через `AssertionError`** (см. [§8.3 Result and failure semantics](#83-result-and-failure-semantics)) —
  то есть SDK не «отдаёт ассерты JUnit», а поднимает свои несоответствия так, чтобы JUnit/Allure
  трактовали их нативно как падение теста.
- **Не заменяет RestAssured / HTTP-клиенты / Kafka-клиенты / JDBC / gRPC / Allure.** Под капотом —
  зрелые инструменты; SDK не пишет свой транспорт и свой репортер.
- **Является тонким SDK/фасадом** поверх зрелых инструментов: единый вход, единый контекст,
  единые ожидания и отчётность.
- **Нужна для единого стиля** стендовых автотестов: один словарь, одни идентификаторы, один
  await-механизм, один формат отчёта.
- **Нужна для безопасной генерации автотестов AI-агентом**: декларативный ограниченный формат и
  guardrails, не дающие сгенерировать произвольный/опасный код.

---

## 2. Что библиотека должна стандартизировать

Правила (инварианты), которые SDK обязан задавать и по возможности форсить:

1. **`scenarioId`** — у каждого сценария есть стабильный идентификатор.
2. **`testRunId`** — у каждого запуска есть уникальный идентификатор прогона.
3. **`correlationId` принадлежит SDK.** Генерируется SDK в начале прогона сценария, входит в
   `ScenarioContext` и **инжектится исходящим** в REST/Kafka/gRPC (для БД — используется в
   query/assert/cleanup, если применимо). Capture `correlationId` из ответа сервиса допустим только
   как fallback/compatibility-режим (см. [§8.4](#84-correlationid-ownership-и-outbound-injection)).
4. **Только общий await-механизм.** Все ожидания асинхронных эффектов идут через единый `Awaiter`
   (см. [§4 stand-test-await](#stand-test-await)).
5. **Запрещён `Thread.sleep`.** Прямые фиксированные паузы недопустимы.
6. **Запрещены fixed test-data ids.** Данные генерируются/каптятся в рамках прогона, не хардкодятся.
7. **Запрещены произвольные подключения к неописанным стендам.** Только окружения и логические
   алиасы из whitelisted-конфигурации (`@StandEnv` / env-конфиг, см.
   [§9 Configuration / Environment model](#9-configuration--environment-model)).
8. **Секреты не хранятся в коде и в yaml.** Только внешние источники (env vars / secret manager /
   CI-secret); yaml и Java ссылаются на ключ (secret reference), а не значение.
9. **БД — слой probe/assertion, а не обход публичного поведения сервиса.** Если у сервиса есть
   публичный API/событие — проверяем через них; БД для зондов/ассертов.
10. **Cleanup явный или soft.** Очистка только явная, либо soft-cleanup по `testRunId` (помечаем и
    подчищаем данные прогона), без «широких» destructive-операций.

---

## 3. Архитектурный принцип

Целевая схема исполнения — единый конвейер от описания сценария к реальному стенду:

```mermaid
flowchart TD
    JDSL["Java DSL<br/>(lazy builder)"] --> MODEL["Scenario Model<br/>(immutable)"]
    YDSL["YAML DSL"] --> MODEL
    MODEL --> VAL["Scenario Validator"]
    VAL --> RUN["Scenario Runner<br/>(+ VariableStore)"]
    RUN --> SPI["Step Executor SPI"]
    SPI --> AD["Adapter Step Executors:<br/>REST / Kafka / DB / gRPC"]
    AD --> STAND[("Реальный DEV/IFT стенд")]
```

**Ключевой принцип — единая внутренняя модель.** **Java DSL** и **YAML DSL** — это два *входа*,
которые приводятся к **одной** внутренней `Scenario Model`. Дальше валидация, раннер, исполнители
шагов и адаптеры работают **только** с этой моделью. Так runtime-логика не дублируется: добавление
шага/возможности делается один раз на уровне модели и исполнителей, а оба DSL получают его «бесплатно».

**Java DSL — это lazy builder.** Он не исполняет IO в fluent-chain, а **строит** immutable
`Scenario Model`, которая затем проходит тот же `Scenario Validator` + `Scenario Runner`, что и YAML
(контракт зафиксирован в [§8.1](#81-single-scenario-model-pipeline)). Императивные API,
выполняющие реальный вызов прямо в цепочке, **запрещены** — они обходят валидатор и guardrails.

Слои и их роли:

- **Java DSL / YAML DSL** — поверхностный синтаксис описания сценария (см. черновики в §10 и §11).
- **Scenario Model** — неизменяемое типизированное представление сценария (шаги, параметры,
  ожидания, ассерты, метаданные `scenarioId`/`testRunId`/`correlationId`/`environment`).
- **Scenario Validator** — статическая проверка модели до запуска (обязательные поля, ссылки на
  переменные, whitelisting стендов/датасорсов/схем, запрет небезопасных операций — единый список
  forbidden operations, см. [§8.6](#86-forbidden-operations--единый-источник-истины)).
- **Scenario Runner** — оркестрация исполнения шагов, владение контекстом и `VariableStore`
  (см. [§8.2](#82-scenariocontext-variablestore-и-captureresolve)).
- **Step Executor SPI** — контракт исполнителя шага (живёт в `core`); конкретные реализации — в
  адаптерах.
- **Adapter Step Executors (REST/Kafka/DB/gRPC)** — тонкие обёртки над зрелыми клиентами;
  единственная точка реального IO к стенду.

---

## 4. Модули и ответственность

Для каждого модуля: назначение · что входит · чего быть не должно · внутренние зависимости ·
допустимые внешние зависимости · MVP · отложено. Точные версии внешних библиотек фиксируются
поитерационно; принцип — **тонкий фасад поверх зрелых инструментов**, без собственного транспорта.
Распределение компонентов конвейера (модель/валидатор/раннер/SPI/фасад) по модулям зафиксировано в
[§8.5 Module ownership](#85-module-ownership-validator-runner-step-executor-spi-standclient).

### stand-test-bom

- **Назначение.** Управление версиями модулей SDK и согласованным набором внешних библиотек.
- **Входит.** `java-platform`-платформа: constraints на все `stand-test-*` и (опционально) на
  курируемые версии внешних инструментов.
- **Не должно входить.** Любой код, ресурсы, `src/`. BOM ничего не «реализует».
- **Внутренние зависимости.** Нет (платформа объявляет constraints по координатам).
- **Внешние зависимости.** Нет.
- **MVP.** Минимальная заготовка-платформа; constraints наполняются по мере появления утверждённого
  списка зависимостей (см. [§13 Publishing & Versioning](#13-publishing--versioning)).
- **Отложено.** Re-export сторонних BOM (Spring Boot и т.п.) — до момента, когда появятся реальные
  зависимости.

### stand-test-core

- **Назначение.** Базовая модель сценария и контекста, SPI и базовые контракты — фундамент графа
  модулей. Только модели, интерфейсы и базовые контракты, **без** конкретной реализации
  REST/Kafka/DB/gRPC.
- **Входит (будущие контракты, только описание).**
  - `Scenario`, `ScenarioStep` (generic-модель шага: тип + типизированные параметры);
  - `ScenarioContext` (immutable metadata), `ScenarioId`, `TestRunId`, `CorrelationId`,
    `Environment` (в Итерации 1 реализован как `String` — принятое отклонение, см. §21);
  - `VariableStore` (mutable runtime-хранилище переменных), `VariableResolver`;
  - `ScenarioValidator`, `ValidationResult`;
  - `ScenarioRunner` (interface/SPI), `StepExecutor` SPI, `StepExecutionContext`;
  - `StepResult`, `ScenarioResult`, `StepStatus`;
  - `StandClient` (контракт-фасад);
  - `ForbiddenOperation` (единый источник истины запрещённых операций);
  - `EnvironmentRegistry` контракты (резолв логических алиасов → endpoint + secret-ref);
  - `StepEvent` / `ReportingEvent` SPI (для Allure и логов);
  - `StandTestException` (инфраструктурные ошибки), `StandTestAssertionError` (ассерты);
  - неизменяемые value-объекты.
- **Не должно входить.** Реализации адаптеров (REST/Kafka/DB/gRPC), JUnit-зависимости, Spring,
  YAML-парсер, отчётность — только абстракции, модель и SPI.
- **Внутренние зависимости.** Нет (сток графа).
- **Внешние зависимости.** Минимум: SLF4J (api). JSON/JSONPath-биндинг — на уровне адаптеров, не в
  core.
- **MVP.** Да — value-объекты, контекст, `VariableStore`/resolver, result/status-модели, базовые
  исключения, `ScenarioValidator`/`ScenarioRunner`/`StepExecutor` SPI, `StandClient`-контракт,
  reporting-event SPI.
- **Отложено.** Расширенная модель условий/ветвлений сценария; продвинутый data-generator.

### stand-test-await

- **Назначение.** Единый механизм ожиданий асинхронных эффектов.
- **Входит (будущие сущности).** `AwaitPolicy`, `AwaitResult`, `Awaiter`, `TimeoutDiagnostics`.
- **Обязательные свойства.** **никаких `Thread.sleep`**; **configurable timeout**; **poll interval**;
  **diagnostics on timeout** (что ждали, сколько попыток, последнее наблюдаемое состояние).
- **Не должно входить.** Любая транспортная логика (await не знает про REST/Kafka/DB напрямую — он
  ждёт переданный предикат/поставщик значения).
- **Внутренние зависимости.** `stand-test-core`.
- **Внешние зависимости.** SLF4J; опционально Awaitility как зрелый движок поллинга (фасад, а не своя
  реализация с нуля).
- **MVP.** Да — централизованная политика ожидания, timeout, polling, diagnostics.
- **Отложено.** Адаптивные/backoff-стратегии, бюджет ожиданий на сценарий.

### stand-test-junit

- **Назначение.** Интеграция с JUnit 5 (bridge JUnit ↔ контекст/раннер SDK).
- **Входит (будущие сущности).** Аннотации `@StandTest`, `@StandEnv`, `@StandScenarioId`; extension
  `StandTestExtension` (инициализация контекста, lifecycle, проброс `testRunId`/`correlationId`,
  предоставление `StandClient` как параметра теста); запуск сценария через `ScenarioRunner`;
  преобразование SDK-падений (`StandTestAssertionError`/`StandTestException`) в JUnit-failures.
- **Не должно входить.** Бизнес-ассерты, транспорт, отчётность — только мост «JUnit ↔ контекст SDK».
- **Внутренние зависимости.** `stand-test-core`, `stand-test-await`.
- **Внешние зависимости.** `junit-jupiter-api` (JUnit 5).
- **MVP.** Да — extension, аннотации, lifecycle, инициализация контекста, предоставление
  `StandClient` без Spring.
- **Отложено.** Параметризованные сценарии, интеграция с YAML-источниками сценариев.

### stand-test-rest

- **Назначение.** REST-вызовы и проверки. Содержит typed step-модель (`RestStep`) и REST
  `StepExecutor` (см. [§8.5](#85-module-ownership-validator-runner-step-executor-spi-standclient)).
- **Входит (будущие возможности).** GET/POST/PUT/DELETE; headers; auth; outbound-инъекция
  `correlationId` (header, имя — из env-конфига); JSONPath-ассерты; capture
  переменных из ответа в `VariableStore`; attachments запроса/ответа в отчёт.
- **Не должно входить.** Собственный HTTP-клиент с нуля; бизнес-логика сервисов.
- **Внутренние зависимости.** `stand-test-core`, `stand-test-await`.
- **Внешние зависимости.** Зрелый HTTP-клиент (RestAssured / OkHttp / `java.net.http`), Jackson,
  JSONPath.
- **MVP.** Да — минимальные GET/POST, outbound correlationId, JSON-ассерты, capture переменных.
- **Отложено.** Полный набор методов/auth-флоу, multipart, ret- и redirect-политики.

### stand-test-kafka

- **Назначение.** Kafka send/expect/assert для стендовых сценариев. Содержит typed step-модель
  (`KafkaStep` → step-типы `kafka.send` / `kafka.expect`) и Kafka `StepExecutor`. Единственная точка
  реального Kafka-IO к стенду; поверх `kafka-clients` (raw — для контроля assign/seek), без своего
  клиента.

- **`kafka.send` (MVP).** Публикует JSON-сообщение в топик-алиас:
  - параметры: topic-алиас (§9); `body` (inline) / `bodyResource` (classpath); `key` (опц.); headers
    (опц.); `injectCorrelationId`; подстановка `${...}` в key/headers/body (как у REST);
  - **outbound `correlationId`** инжектится носителем из конфига топика (§8.4). В MVP реализован только
    носитель **HEADER**: при `injectCorrelationId` и (a) отсутствии `correlation` у топика, либо (b)
    носителе KEY/PAYLOAD_FIELD (ещё не реализованы) — `StandTestException`, зеркаля REST HEADER-only
    (`RestStepExecutor.injectCorrelationId`); KEY/PAYLOAD_FIELD — следующая подытерация (§8.4);
  - продьюсер создаётся и **закрывается внутри** `execute()` (try-with-resources, `flush` до close);
  - сериализация: ключ и значение — `String` (JSON как строка), header-значения — UTF-8 байты.

- **`kafka.expect` (MVP).** Ждёт сообщение из топик-алиаса до timeout:
  - **selection (выбор сообщения):** primary — по `correlationId` (`correlationIdFromContext`); опц.
    дискриминатор — по `key` (param `key`, симметрично `kafka.send`, нужен для нескольких
    триггер→expect на одном топике, §8.7); сообщение «потребляется» (consume-and-advance, §8.7), так что
    следующий expect на топике стартует за уже выбранным;
  - **assertion:** JSONPath-ассерты по value выбранного сообщения (`assertPath`) — **жёсткий**
    `StandTestAssertionError` при несовпадении; **capture** значений из сообщения в `VariableStore`
    (как REST-capture);
  - **poll-цикл через `stand-test-await`:** probe = один **короткий** `consumer.poll(pollTimeout)`
    (возвращает первое сообщение, прошедшее selection, либо пусто); cadence/timeout держит `Awaiter`
    (`AwaitPolicy.timeout` = `withinSeconds(...)`); чтобы не удваивать ожидание — либо `pollTimeout`
    несёт паузу и `AwaitPolicy.pollInterval`≈0, либо poll near-zero и паузу держит `pollInterval`
    (одно из двух, не оба); консьюмер спозиционирован в `prepare` (§8.7) и поллится **на вызывающем
    потоке** — совместимо с thread-confinement await и с тем, что `KafkaConsumer` непотокобезопасен.

- **Offset-стратегия и seek-race (обязательно для MVP).**
  - `group.id` уникален **на прогон сценария** (включает `testRunId`), не переиспользуется между
    тестами;
  - **`assign(partitionsFor(topic))` → `seekToEnd` → `position(...)`**, а не голый `subscribe()` (при
    нём назначение партиций ленивое и `seekToEnd` до первого `poll` бессмыслен); start-from-now;
  - **позиционирование выполняется в фазе `prepare` раннера ДО любого шага** (§8.7) — это и снимает
    **`KAFKA-SEEK-RACE`**: консьюмер живёт в run-scoped `ResourceScope` и закрывается раннером;
  - при timeout в отчёт прикладывается диагностика (`TimeoutDiagnostics`): topic; partition(s);
    offsets; число просмотренных сообщений; критерии selection; сэмпл последних сообщений.

- **Подключение к брокеру.** Адрес/креды — по ссылкам из env-модели (§9): `KafkaClusterDefinition`
  (`bootstrapServersRef` + опц. security secret-ref'ы), резолв через `EnvironmentRegistry` как
  `baseUrlRef` у REST; запрет хардкода bootstrap-серверов и секретов в коде/сценарии (§9/§20).

- **Регистрация executor.** Через `ServiceLoader`
  (`META-INF/services/ru.alfa.stand.test.core.execution.StepExecutor`, public no-arg конструктор) —
  см. §8.5.

- **Не должно входить.** Собственный Kafka-клиент; бизнес-обработчики; продакшн-конфигурация
  ретраев/DLQ.
- **Внутренние зависимости.** `stand-test-core`, `stand-test-await`.
- **Внешние зависимости.** `kafka-clients` (Apache, raw — для контроля assign/seek); `json-path`
  (JSONPath; value читается как строка — отдельный JSON-binding/Jackson не обязателен, как в REST).
- **MVP.** Да — send JSON; expect JSON; selection по `correlationId`/`key`; JSONPath-ассерты; capture;
  базовая offset-стратегия с pre-arm (§8.7); timeout-diagnostics. Носитель correlationId по умолчанию —
  **HEADER** (полностью специфицирован и реализован в REST).
- **Отложено.** JSONPath как **фильтр выбора** среди многих сообщений (в MVP JSONPath — ассерт по уже
  выбранному сообщению); KEY/PAYLOAD_FIELD как носитель correlationId (следующая подытерация, §8.4);
  сложные offset/commit-стратегии; batch-проверки; schema-registry/Avro.

### stand-test-db

- **Назначение.** Слой seed/probe/assertion для БД. Содержит typed step-модель (`DbStep`) и DB
  `StepExecutor`. **Сначала — probe/assertion-слой, а не generic DB-клиент**: запись поддерживается
  только как ограниченная подготовка тест-данных.
- **Входит (будущие возможности).** Типы шагов **`db.query`** / **`db.expectEventually`** (await-query) /
  **`db.seed`** / **`db.cleanup`** — typed `DbStep` собирает core-`ScenarioStep` (как `RestStep`/`KafkaStep`,
  §8.5): query; await-query; expect row exists; expect single value; seed-скрипты; cleanup по `testRunId`.
  `db.expectEventually` поллит query через `stand-test-await` (probe = выполнить query, condition = значение
  совпало; на timeout — `StandTestAssertionError` с `TimeoutDiagnostics`: datasource, query, params,
  последнее наблюдённое значение, число попыток). У DB нет poll-хазардов Kafka (нет pre-arm/seek-race);
  per-run JDBC-соединение держится в `ResourceScope` по ключу-алиасу датасорса (§8.7).
- **Ограничения безопасности (обязательно — механизм специфицирован в [§8.8](#88-безопасность-db-классификация-sql-и-write-guard)).**
  - **datasource whitelist** — только описанные датасорсы из env-конфига (резолв `urlRef`/`userRef`/
    `passwordRef` → значения в рантайме адаптером, как `baseUrlRef` у REST, §9);
  - **schema whitelist** — seed/cleanup пишут только в разрешённые схемы (`allowedSchemas`); схема write-шага
    выводится из **schema-qualified** имени таблицы (§8.8);
  - **readonly по умолчанию**; write (`INSERT`/`UPDATE`/`DELETE`) — только на `db.seed`/`db.cleanup` **и** при
    `writeAllowed == true` (флаг датасорса в env-конфиге + явный шаг);
  - **destructive SQL запрещён**; **что считается destructive:** `delete`/`update` без декларированного
    `testRunId`-предиката (маркер `DbStep.whereTestRunId(...)`, §8.8); `truncate`; `drop`/DDL; любой
    `insert`/`update`/`delete` вне whitelisted-схемы или неквалифицированной таблицы;
  - **классификация SQL** — единый statement-классификатор в core, потребляемый `ScenarioValidator` (статически,
    до прогона) и форсимый адаптером повторно в рантайме (defense-in-depth); один statement на шаг,
    непарсимое — **reject** (fail-closed), см. §8.8;
  - **seed помечает данные `testRunId`** — автор включает `test_run_id = :testRunId` (built-in `${testRunId}`);
    адаптер не инъектит автоматически (§8.8);
  - **cleanup работает по `testRunId`** (soft-cleanup, без «широких» операций) — **явный** `db.cleanup`-шаг
    (не авто-teardown; не отработает после упавшего шага — §8.8);
  - **shared mutable test data запрещены** (см. [§15](#15-parallel-execution-and-isolation));
  - **БД не становится основным способом проверки бизнес-логики**, если есть публичный API/событие.
- **Не должно входить.** ORM/доменные репозитории сервиса; «широкие» destructive-операции;
  обход публичного поведения; generic-режим «произвольный SQL к произвольной БД»; мульти-statement-батчи.
- **Внутренние зависимости.** `stand-test-core`, `stand-test-await`.
- **Внешние зависимости.** JDBC (`java.sql`); драйвер предоставляет потребитель. Именованные параметры
  (`:name`) — собственный переписыватель `:name` → `?` поверх `PreparedStatement`; Spring-JDBC/HikariCP —
  опциональный тонкий помощник, не обязателен для MVP. Тесты самого SDK — на **H2 in-memory** (§16).
- **MVP.** Да — `db.query`; `db.expectEventually` (await-query, expect single value); `db.seed`
  (write-allow); черновик `db.cleanup` по `testRunId`; statement-классификатор + schema/datasource-whitelist.
  `seed` принимает inline SQL или classpath-ресурс (как `body`/`bodyFromResource` у REST), **один statement**.
- **Отложено.** Полноценный безопасный движок cleanup + teardown/finally-хук; «expect row exists» как отдельный
  шаг; миграционные сиды; multi-datasource транзакции; мульти-statement seed.

### stand-test-grpc

- **Назначение.** gRPC-вызовы и проверки. Содержит typed step-модель (`GrpcStep`) и gRPC
  `StepExecutor`.
- **Входит (будущие возможности).** unary-вызовы; metadata (включая outbound `correlationId`);
  deadlines; protobuf-ассерты; capture полей.
- **Не должно входить.** Собственный gRPC-стек; генерация контрактов сервиса; streaming на старте.
- **Внутренние зависимости.** `stand-test-core`, `stand-test-await`.
- **Внешние зависимости.** `grpc-java` (`io.grpc`), `protobuf-java`.
- **MVP.** Не входит в MVP (см. §6) — модуль-каркас, реализация позже.
- **Отложено.** Streaming (client/server/bidi), сложные deadline/retry-политики.

### stand-test-allure

- **Назначение.** Единые отчёты. Listener/consumer для `StepEvent` / `ReportingEvent` (см.
  [§17 Logging and observability](#17-logging-and-observability)); **Allure не является
  hard-зависимостью core**.
- **Входит (будущие возможности).** Каждый шаг как Allure step; attachments REST request/response;
  attachments Kafka-сообщений; attachments SQL query/result; attachments gRPC request/response;
  переменные; `testRunId`/`correlationId`/`scenarioId` в отчёте; timeout-diagnostics из await в отчёт.
  **Контракт доставки attachments к консьюмеру (`Attachment` + редактирование секретов) — §8.9, prerequisite Итерации 7.**
- **Не должно входить.** Транспорт; ассерты; бизнес-логика — только отчётность/диагностика.
- **Внутренние зависимости.** `stand-test-core` (метаданные/события шагов через reporting-event SPI).
- **Внешние зависимости.** `allure-java-commons` / `allure-junit5`.
- **MVP.** Да — step-репортинг, attachments, метаданные сценария, проброс timeout-диагностики.
- **Отложено.** Кастомные категории дефектов, агрегированные дашборды.

### stand-test-example

- **Назначение.** Технические примеры использования SDK (Итерация 8) — образец того, как потребитель
  пишет сценарии. TEST-ONLY: сценарии в `src/test`, исполняются через публичный API как чёрный ящик
  против in-process doubles (JDK `HttpServer` для REST, H2 для DB), поэтому `./gradlew build` зелёный
  офлайн без реального стенда. Детальный план: `docs/arch/stand-test-example-implementation-plan.md`.
- **Входит.** REST/DB smoke-сценарии; capture/resolve между шагами; await/timeout; демонстрация
  Allure-репортинга. **Phase 1** — REST+DB; `@StandTest`-автопроводка и Kafka — **Phase 2**.
- **Не должно входить.** Бизнес-логика реального проекта; реальные стендовые конфиги; публикуемый
  артефакт; смешивание SDK и тестов конкретного проекта (§20).
- **Внутренние зависимости (test).** `stand-test-core`, `stand-test-rest`, `stand-test-db`,
  `stand-test-allure` (Phase 2 добавит `junit`/`kafka`). `await` — транзитивно.
- **Внешние зависимости (test).** JUnit 5, AssertJ, H2 (DB double); REST-double — JDK `HttpServer`.
- **MVP.** Да — закрывает сквозной usage-срез и §18-DoD (примеры usage + Allure-диагностика).

### stand-test-spring-boot-starter

- **Назначение.** Удобная автоконфигурация для Spring Boot тестовых проектов.
- **Важно.** **Core SDK не должен зависеть от Spring Boot.** Starter — отдельный удобный модуль,
  который связывает нужные runtime-модули в Spring-тест-контекст. Плагин `org.springframework.boot`
  **не** применяется (это библиотека, а не bootable-приложение). В MVP `StandClient` доступен и без
  Spring — через `StandTestExtension` (см. stand-test-junit); `@Autowired StandClient` — это
  post-MVP удобство этого стартера.
- **Входит (будущее).** Auto-configuration, бины-обёртки `StandClient`/адаптеров, биндинг конфигурации
  окружений.
- **Не должно входить.** Реализация адаптеров; зависимость core/адаптеров от starter (только в одну
  сторону).
- **Внутренние зависимости.** Нужные runtime-модули (`core`, `junit`, `allure`, `await`, адаптеры) —
  **никогда наоборот**.
- **Внешние зависимости.** `spring-boot-autoconfigure`, `spring-boot`.
- **MVP.** Не входит в MVP (см. §6).
- **Отложено.** Весь модуль до стабилизации core и адаптеров.

### stand-test-scenario-yaml

- **Назначение.** YAML DSL для описания сценариев.
- **Важно.** YAML нужен в первую очередь для **AI-generated tests** и **ограниченного декларативного
  формата**. Это второй вход в ту же `Scenario Model` (§3).
- **Входит (будущее).** Парсер YAML → generic `Scenario Model` (`ScenarioStep` по типу шага); план
  раннера declarative-сценариев. Исполнители резолвятся через `StepExecutor` SPI в рантайме.
- **Не должно входить.** Дублирование runtime-логики (раннер один — общий, на уровне core);
  произвольные «escape в Java»; **compile-time рёбра на адаптеры**.
- **Внутренние зависимости.** **Только `stand-test-core`** (раннер достаёт адаптеры через core-SPI,
  инжектируемые в рантайме, — это **не** compile-time ребро на адаптеры).
- **Внешние зависимости.** SnakeYAML / `jackson-dataformat-yaml`.
- **MVP.** Не входит в MVP (только дизайн на итерации 9, §7 — дизайн-документ:
  `docs/arch/stand-test-scenario-yaml-design.md`).
- **Отложено.** Полноценный YAML-runner.

### stand-test-ai-schema

- **Назначение.** JSON Schema, правила и ограничения для AI-агента.
- **Важно.** AI-агент **не должен генерировать произвольный Java-код**, если сценарий можно описать
  декларативно. Модуль задаёт схему допустимого YAML-сценария и перечень запрещённых операций,
  **генерируя** ограничения из единого источника истины в core (`ForbiddenOperation`,
  `EnvironmentRegistry`), а не ведя отдельный список (см.
  [§8.6](#86-forbidden-operations--единый-источник-истины)).
- **Входит (будущее).** JSON Schema **surface-YAML** (зеркало surface-схемы Итерации 9); rule-catalog,
  **ключуемый по `ForbiddenOperation`**; правила генерации/guardrails. Дизайн: `docs/arch/stand-test-ai-schema-design.md`.
  **Уточнение «производный».** `ForbiddenOperation` — inert enum `(code, description)`, авто-генерации из
  него **нет**: правила **hand-authored**, но связаны с источником **cross-check-тестом** (каждому
  `ForbiddenOperation` — ровно одна запись каталога) — так «не отдельный список» соблюдается честно (§8.6).
  Static-слой (JSON Schema) vs runtime-слой (`ScenarioValidator`, §8.8) — см. дизайн-док, §Решение 2.
- **Не должно входить.** **Зависимости от runtime-модулей** (адаптеров) и от `scenario-yaml`;
  исполнение сценариев. (JSON Schema зеркалит surface-схему `scenario-yaml` как **спецификацию**, не
  compile-ребро; parity — тестом в `stand-test-example`.)
- **Внутренние зависимости.** Только модель `stand-test-core`, **без** runtime/адаптеров и **без**
  `scenario-yaml`.
- **Внешние зависимости.** Инструмент работы с JSON Schema. **NB:** `networknt/json-schema-validator` тянет
  Jackson, а репозиторий Jackson избегает (json-path на json-smart) — предпочесть non-Jackson-валидатор
  (напр. `everit-org/json-schema`), либо изолировать Jackson в этом dev/AI-tooling-модуле (дизайн-док §Решение 4).
- **MVP.** Не входит в MVP.
- **Отложено.** Весь модуль до стабилизации YAML DSL.

---

## 5. Dependency graph

Стрелка `A → B` читается как «**A зависит от B**». `stand-test-core` — единственный сток (ни от чего
не зависит). Граф ацикличен.

```mermaid
flowchart LR
    await --> core
    junit --> core
    junit --> await
    rest --> core
    rest --> await
    kafka --> core
    kafka --> await
    db --> core
    db --> await
    grpc --> core
    grpc --> await
    allure --> core
    example --> core
    example --> junit
    example --> rest
    example --> kafka
    example --> db
    example --> allure
    scenario_yaml["scenario-yaml"] --> core
    ai_schema["ai-schema"] --> core
    starter["spring-boot-starter"] --> core
    starter --> junit
    starter --> allure
    starter --> await
    starter --> rest
    starter --> kafka
    starter --> db
    starter --> grpc

    bom["bom (java-platform)"]
```

Правила графа:

- `stand-test-core` **не зависит** от адаптеров (он их сток через SPI).
- `stand-test-await` → `core`.
- `stand-test-junit` → `core`, `await`.
- `stand-test-rest` → `core`, `await`.
- `stand-test-kafka` → `core`, `await`.
- `stand-test-db` → `core`, `await`.
- `stand-test-grpc` → `core`, `await`.
- `stand-test-allure` → `core`.
- `stand-test-example` → `core`, `junit`, `rest`, `kafka`, `db`, `allure` (всё как `testImplementation`;
  TEST-ONLY потребитель-сток, от него никто не зависит; Kafka-пример тегирован `requires-broker` и
  исключён из дефолтного прогона).
- `stand-test-scenario-yaml` → **только `core`** (адаптеры — через core-SPI в рантайме, **без**
  compile-time ребра на конкретные адаптеры → нет дублирования раннера).
- `stand-test-ai-schema` → **только модель `core`**, **не** зависит от runtime/адаптеров и от
  `scenario-yaml`.
- `stand-test-spring-boot-starter` → нужные runtime-модули, **никогда наоборот**.
- adapter-модули **не зависят друг от друга**.
- `stand-test-bom` — платформа управления версиями (не участвует в compile-графе модулей; импортируется
  только внешними потребителями).

> Примечание (scaffold). Сейчас в `build.gradle.kts` модулей рёбра `project(...)` ещё не активированы
> (живут как комментарии-заготовки). Комментарии `Planned internal dependencies` приведены **к этому
> графу** (в частности: `scenario-yaml` → только `core`; `ai-schema` → только `core`, без
> `scenario-yaml` и адаптеров). При реализации рёбра добавляются строго по этому графу. Сверка
> комментариев скаффолда с целевым графом выполнена (см. [§20 анти-правила](#20-что-нельзя-делать-анти-правила)).

---

## 6. MVP

**MVP включает** (первый реализуемый срез — сквозной REST→Kafka→DB-флоу с ожиданиями и отчётом):

- `stand-test-core`
- `stand-test-await`
- `stand-test-junit`
- `stand-test-rest`
- `stand-test-kafka`
- `stand-test-db`
- `stand-test-allure`

**MVP НЕ включает:**

- полноценный YAML DSL (`stand-test-scenario-yaml` — только дизайн);
- AI schema (`stand-test-ai-schema`);
- gRPC streaming (и `stand-test-grpc` в целом — каркас без реализации);
- Spring Boot starter (`stand-test-spring-boot-starter`);
- сложный data generator;
- UI;
- инфраструктуру на основе Testcontainers как способ стендового тестирования (test doubles для
  тестов **самого SDK** допустимы — см. [§16](#16-testing-strategy-for-sdk-itself)).

---

## 7. Порядок реализации

Реализация разбита на изолированные итерации; после каждой проект собирается и зелёный (см. §18 DoD).
**Итерация 0 (дизайн, без кода) предшествует Итерации 1** — её контракты должны быть закрыты до старта
`stand-test-core`.

- **Итерация 0 — Core contracts (design-only).** Зафиксировать контракты уровня core (см.
  [§8](#8-итерация-0-core-contracts)): single Scenario Model pipeline; `ScenarioContext`/`VariableStore`;
  result/failure semantics; correlationId ownership; module ownership; forbidden-ops single source of
  truth. Кода нет — только разделы плана.
- **Итерация 1 — Core model.** `scenarioId`; `testRunId`; `correlationId`; scenario context;
  `VariableStore`/variable resolver; базовые result/status-модели; базовые исключения;
  `ScenarioValidator`/`ScenarioRunner`/`StepExecutor` SPI; `StandClient`-контракт; reporting-event SPI.
- **Итерация 2 — Await.** Централизованная await-политика; timeout; polling; diagnostics.
- **Итерация 3 — JUnit.** JUnit 5 extension; аннотации; lifecycle; инициализация контекста;
  предоставление `StandClient`; проброс SDK-падений в JUnit.
- **Итерация 4 — REST adapter.** Минимальные GET/POST; outbound correlationId; JSON-ассерты; capture
  переменных.
- **Итерация 5 — Kafka adapter.** *Prerequisite (core):* хук `StepExecutor.prepare` + `ResourceScope`
  в `StepExecutionContext` (§8.7) и `KafkaClusterDefinition` в env-модели (§9). Затем: `kafka.send`
  (JSON, inject correlationId); `kafka.expect` (selection по `correlationId`/`key`, JSONPath-ассерты,
  capture); базовая offset-стратегия (start-from-now с pre-arm, уникальный group.id); timeout-diagnostics.
- **Итерация 6 — DB adapter.** *Prerequisite (core):* statement-классификатор SQL в `core.validation`
  (read/write/destructive; schema-qualified; `testRunId`-предикат; fail-closed), потребляемый
  `ScenarioValidator` и деривируемый из `ForbiddenOperation` (§8.6/§8.8) — нового env-контракта **не**
  требуется (`DatasourceDefinition` уже есть, §9). Затем: `db.query`; `db.expectEventually` (await-query,
  expect single value); `db.seed` (write-allow); schema/datasource-whitelist; черновик `db.cleanup` по
  `testRunId` (§8.8).
- **Итерация 7 — Allure.** *Prerequisite (core):* value-type `Attachment` + поле `attachments` на
  `StepResult`/`StepEvent` с редактированием секретов (§8.9). step-репортинг; attachments (REST/Kafka/SQL);
  метаданные сценария; проброс timeout-диагностики.
- **Итерация 8 — Example tests.** Только технические примеры использования SDK; **без** бизнес-логики
  реального проекта.
- **Итерация 9 — YAML DSL design.** Черновик схемы; план парсера; план раннера (без реализации).
- **Итерация 10 — AI guardrails.** JSON Schema (из core-контракта); forbidden operations; правила
  генерации.

---

## 8. Итерация 0. Core contracts

> **Design-only.** Этот раздел закрывает архитектурные блокеры уровня core **до** старта реализации
> `stand-test-core`. Здесь нет кода — только контракты. Все имена сущностей — «будущие», описаны по
> зоне ответственности.

### 8.1 Single Scenario Model Pipeline

И **Java DSL**, и **YAML DSL** обязаны приводиться к одной внутренней `Scenario Model`. Единый pipeline:

```text
Java DSL / YAML DSL
    ↓
Scenario Model          (immutable)
    ↓
Scenario Validator      (whitelist стендов/датасорсов/схем, forbidden operations, обязательные поля)
    ↓
Scenario Runner         (владеет ScenarioContext + VariableStore)
    ↓
Step Executor SPI       (контракт в core)
    ↓
Adapter Step Executors: REST / Kafka / DB / gRPC
    ↓
Real DEV/IFT stand
```

**Критически важно: Java DSL не должен выполнять IO прямо в fluent-chain.**

Запрещённый подход (eager-IO в цепочке — каждый вызов сразу идёт на стенд):

```java
// ЗАПРЕЩЕНО: исполняет HTTP-запрос прямо в цепочке, минуя Scenario Model + Validator.
stand.rest("client-service")
    .post("/api/request")
    .expectStatus(200);
```

**Правильный подход.** Java DSL — это **lazy builder**, который строит immutable `Scenario Model`,
после чего сценарий исполняется через единый `ScenarioValidator` + `ScenarioRunner`. Целевой стиль
(draft, см. полный пример в [§10](#10-public-api-sketch-java-dsl)):

```java
var scenario = Scenario.builder("example-flow")
    .env("ift")
    .step(RestStep.post("client-service", "/api/request")
        .body("fixtures/request.json")
        .injectCorrelationId()
        .expectStatus(200)
        .capture("requestId", "$.requestId"))
    .step(KafkaStep.expect("response-topic")
        .correlationIdFromContext()
        .withinSeconds(30)
        .assertPath("$.status", "SUCCESS"))
    .step(DbStep.expectEventually("mainDb")
        .query("select status from request where id = :requestId")
        .paramFromContext("requestId")
        .withinSeconds(20)
        .expectSingleValue("SUCCESS"))
    .build();              // immutable Scenario Model

stand.run(scenario);      // Validator → Runner → StepExecutor SPI → adapters
```

**Анти-правило (обязательно):**

> **Imperative eager-IO Java API запрещён**, потому что он обходит `Scenario Validator`, forbidden
> operations, environment whitelist и единый `Runner`. Java DSL обязан быть lazy builder’ом,
> производящим immutable `Scenario Model`, исполняемую тем же конвейером, что и YAML.

### 8.2 ScenarioContext, VariableStore и capture/resolve

Конфликт, который снимаем: value-объекты должны быть immutable, но `capture("requestId", …)` обязан
сохранять переменные между шагами. Модель:

- **`ScenarioContext`** содержит **immutable** метаданные прогона:
  - `scenarioId`;
  - `testRunId`;
  - `correlationId`;
  - `environment`;
  - tags / labels;
  - `createdAt`.
- **`VariableStore`** — отдельное **мутабельное** хранилище переменных исполнения.
- `VariableStore` принадлежит `ScenarioRunner`.
- **Один запуск сценария = один изолированный `VariableStore`.**
- `VariableStore` **не** static / global / thread-local по умолчанию.
- При parallel execution каждый сценарий получает свой store.
- Capture из REST/Kafka/DB/gRPC пишет значения в `VariableStore`.
- Resolve `${requestId}` читает значения из `VariableStore`.
- Правило immutability относится к value-объектам и определению сценария, но **не** к runtime
  execution store.

Контракт:

```text
Scenario definition is immutable.
Scenario metadata value objects are immutable.
Runtime variables are stored in a per-scenario VariableStore owned by ScenarioRunner.
```

**Thread-confinement:**

- один `VariableStore` на один scenario run;
- **запрещено** шарить store между тестами;
- parallel execution безопасен при уникальных `testRunId` и `correlationId` (см.
  [§15](#15-parallel-execution-and-isolation)).

### 8.3 Result and failure semantics

SDK имеет внутренние модели результата — `StepResult`, `ScenarioResult`, `StepStatus` — но
**pass/fail JUnit определяется через исключения, совместимые с JUnit**. Правила:

1. Если SDK-assertion не прошёл — SDK **бросает** ошибку, совместимую с JUnit failure.
2. Для assertion failures используется наследник `AssertionError` — будущий `StandTestAssertionError`
   (JUnit/Allure трактуют его нативно как падение).
3. Для инфраструктурных ошибок — runtime exception, будущий `StandTestException`.
4. `StepStatus.FAILED` **не** должен молча сохраняться без падения теста.
5. По умолчанию — **short-circuit policy**: при падении critical-шага сценарий останавливается,
   ошибка пробрасывается в JUnit, JUnit показывает failed test.
6. **Collect-all mode** — возможная post-MVP опция, **не** дефолт.
7. Allure получает failure diagnostics из того же `ScenarioResult`/`StepResult` (через reporting-event
   SPI), а не из отдельного источника.

> Переформулировка §1: SDK **не заменяет** JUnit как test engine, но SDK-level assertions
> пробрасываются как **JUnit-compatible failures через `AssertionError`**.

### 8.4 CorrelationId ownership и outbound injection

Снимаем противоречие «инвариант требует проброс correlationId, а примеры каптят его из ответа».
Правильная модель:

- `correlationId` **генерируется SDK** в начале scenario run и входит в `ScenarioContext`.
- REST/Kafka/gRPC-адаптеры **автоматически инжектят** `correlationId` в **исходящие** вызовы:
  - **REST** — header (например, `X-Correlation-Id`); имя header **конфигурируется** через
    environment/config model;
  - **Kafka** — header / key / payload-field; способ **конфигурируется**;
  - **gRPC** — metadata.
- **DB-шаги** используют `correlationId` только для query/assert/cleanup, если применимо.
- Capture `correlationId` **из ответа сервиса не является основным сценарием**.
- Capture из response — для **service-generated** id, которые SDK не может знать заранее: `requestId`,
  `operationId`, `entityId`, external business id.

**Правило:**

> SDK-owned `correlationId` должен пробрасываться outbound **до** триггерящего действия. Capture
> `correlationId` из ответа сервиса допустим только как fallback/compatibility-режим.

Java DSL и YAML draft (см. §10–§11) обновлены так, что `correlationId` SDK-owned и явно инжектится в
исходящий REST/Kafka/gRPC-запрос (`injectCorrelationId()` / `correlationIdFromContext()` /
`injectCorrelationId: true`).

**Носители correlationId для Kafka (inject на `send` / extract при `expect`-match).** Носитель задаётся
`CorrelationConfig(source, name)` из `TopicDefinition.correlation`:

| `source` | inject (send) | extract / match (expect) | семантика `name` |
|---|---|---|---|
| `HEADER` | header `name` = `correlationId` (UTF-8 байты) | header `name`, декод UTF-8 | имя header |
| `KEY` | `ProducerRecord.key()` = `correlationId` | `record.key()` | **игнорируется** (ключ целиком = correlationId) |
| `PAYLOAD_FIELD` | JSON-поле `name` в value = `correlationId` | JSON-поле `name` из value | имя **top-level** поля (dotted-path/JSONPath — отложено) |

Сравнение — со строкой `ScenarioContext.correlationId().value()`. **HEADER** — носитель по умолчанию для
MVP (полностью специфицирован и реализован в REST); `KEY`/`PAYLOAD_FIELD` для Kafka — следующая
подытерация. Для `KEY` поле `name` `CorrelationConfig` обязательно non-blank (контракт core), но
**игнорируется** при матче — допустимо положить туда сентинел вроде `key`.

### 8.5 Module ownership: Validator, Runner, Step Executor SPI, StandClient

Привязка компонентов конвейера к модулям:

**`stand-test-core` содержит контракты** (полный список — в [§4 stand-test-core](#stand-test-core)):
`Scenario`, `ScenarioStep`, `ScenarioContext`, `VariableStore`, `VariableResolver`,
`ScenarioValidator`, `ValidationResult`, `ScenarioRunner` (interface/SPI), `StepExecutor` SPI,
`StepExecutionContext`, `StepResult`, `ScenarioResult`, `StepStatus`, `StandClient` (контракт-фасад),
`ForbiddenOperation`, `EnvironmentRegistry`-контракты, `StepEvent`/`ReportingEvent` SPI,
`ResourceScope` (run-scoped реестр `AutoCloseable`-ресурсов, §8.7) и опциональный хук
`StepExecutor.prepare` (§8.7). Только модели, интерфейсы и базовые контракты — **без** реализации
REST/Kafka/DB/gRPC.

**Adapter-модули содержат реализации `StepExecutor`** и typed step-definitions:

- `stand-test-rest` — `RestStep` + REST executor;
- `stand-test-kafka` — `KafkaStep` + Kafka executor;
- `stand-test-db` — `DbStep` + DB executor;
- `stand-test-grpc` — `GrpcStep` + gRPC executor.

Каждый адаптер **регистрирует свой `StepExecutor` через `ServiceLoader`** — файл
`META-INF/services/ru.alfa.stand.test.core.execution.StepExecutor` с FQCN реализации (класс public,
public no-arg конструктор). `StandClient`/JUnit-обвязка собирает исполнители через
`ServiceLoader.load(StepExecutor.class)` за `DefaultScenarioRunner`; незарегистрированный тип шага →
`StandTestException` в рантайме. Подключение `testImplementation` на адаптер делает его step-типы
исполняемыми **без** wiring-кода.

**Решение по step-model (зафиксировано — выбран один подход).** core владеет **generic** моделью шага
(`ScenarioStep` = тип шага + типизированные параметры) и `StepExecutor` SPI; **typed step definitions
(`RestStep`/`KafkaStep`/`DbStep`/`GrpcStep`) живут в adapter-модулях** и собирают core-`ScenarioStep`.
Следствия:

- Java DSL, использующий `RestStep`/`KafkaStep`/…, compile-time зависит от соответствующих
  adapter-модулей — это нормально: тест-проект и так подключает нужные адаптеры;
- `scenario-yaml` строит **generic** `ScenarioStep` по типу шага и резолвит исполнитель через
  `StepExecutor` SPI в рантайме → **зависит только от core**, без compile-рёбер на адаптеры и без
  дублирования раннера;
- core остаётся без зависимостей на адаптеры; оба входа сходятся к одной generic-модели.

**`stand-test-junit` содержит JUnit 5 bridge:** extension; lifecycle; запуск сценария через
`ScenarioRunner`; преобразование SDK-падений в JUnit-failures; предоставление `StandClient`.

**`stand-test-allure` содержит listener/consumer** для `StepEvent`/`ReportingEvent`. Allure **не**
является hard-зависимостью core.

### 8.6 Forbidden operations — единый источник истины

- Forbidden operations имеют **единый источник истины в core** (`ForbiddenOperation` + правила,
  которые потребляет `ScenarioValidator`).
- `stand-test-ai-schema` **не** ведёт отдельный независимый список запретов.
- `ai-schema` **генерирует/использует** ограничения из core-контракта.
- Иначе появится drift между runtime-валидатором и AI-схемой (риск зафиксирован в
  [§19](#19-риски)).

### 8.7 Async-expect: пред-вооружение консьюмера и run-scoped ресурсы

Снимаем `KAFKA-SEEK-RACE` (§19). **Проблема:** раннер исполняет шаги **строго последовательно**
(`DefaultScenarioRunner` — без look-ahead), а `correlationId` SDK-owned и инжектится **до** триггера
(§8.4). Если `kafka.expect` создаёт и позиционирует консьюмер (`seekToEnd`, start-from-now) только в
своём шаге — это происходит **после** триггерящего REST-шага, и сообщение теряется. «start-from-now»
обязателен (чтобы не реигрывать историю топика), но позиция должна быть зафиксирована **до** действия.

**Решение — пред-вооружение (pre-arm) консьюмеров отдельной фазой раннера, без знания core о Kafka:**

- `StepExecutor` SPI получает **опциональный** хук `default void prepare(ScenarioStep step,
  StepExecutionContext context) {}` (по умолчанию no-op).
- Перед основным циклом раннер проходит шаги **в порядке объявления** и вызывает `executor.prepare(...)`
  для каждого. Kafka-executor в `prepare` для `kafka.expect`-шага вооружает **один консьюмер на
  топик-алиас на прогон** (idempotent — если консьюмер для топика в этом прогоне уже вооружён, повторно
  не создаётся): run-scoped `group.id` (включает `testRunId`), `assign(partitionsFor(topic))` →
  `seekToEnd` → `position(...)` (форсирует seek), и регистрирует его в **`ResourceScope`** под ключом
  топик-алиаса (ниже). REST/DB `prepare` — no-op.
- Все expect-консьюмеры спозиционированы на конец лога **до выполнения любого шага** → до любого
  триггера → race исключён; примеры §10/§11 (rest.post раньше, kafka.expect позже) корректны как
  написаны, без дополнительного `arm`-шага.
- `kafka.expect.execute()` достаёт консьюмер из `ResourceScope` **по ключу топик-алиаса** и поллит его
  до match/timeout (через `Awaiter`, см. §4 stand-test-kafka).
- **Consume-and-advance (обязательно).** Консьюмер на топик — **общий** для всех `kafka.expect` на этом
  топике в прогоне и **продвигается** по мере чтения: каждый expect начинает с позиции, где остановился
  предыдущий, и «потребляет» (продвигает offset за) выбранное сообщение. Это снимает silent false-pass
  при **нескольких триггер→expect на одном топике**: `correlationId` уникален на **прогон** (не на шаг,
  §8.2/§15), поэтому все сообщения прогона на топике несут один и тот же `correlationId`, и **без**
  продвижения второй expect повторно выбрал бы первое (устаревшее) сообщение. С продвижением N-й expect
  видит N-е сообщение → соответствие триггер↔expect сохраняется.
- **Дискриминатор для неоднозначных потоков.** Если на один топик в прогоне приходит несколько
  correlationId-совпадающих сообщений **не** в строгом порядке expect-шагов, selection только по
  `correlationId` неоднозначна — нужен per-trigger дискриминатор (различный `key` на `send` и
  соответствующий `key`-селектор на `expect`); selection пропускает (не потребляя как «выбранное»)
  сообщения, не прошедшие дискриминатор.

**`ResourceScope` — run-scoped **keyed** реестр закрываемых ресурсов (новый core-контракт):**

- новый компонент в `StepExecutionContext` (рядом с `VariableStore`), **отдельный** от него:
  `VariableStore` хранит value-объекты (коэрсятся в `String`), а `ResourceScope` — живые `AutoCloseable`
  (например `KafkaConsumer`), привязанные к прогону;
- **keyed-реестр:** `register(key, AutoCloseable)` (**fail-fast на дубликат ключа** — бросает
  `StandTestException`; per-key идемпотентность обеспечивает **адаптер**, проверяя `contains(key)` перед
  `register`, как делает Kafka-executor) + `contains(key)`/`get(key)` (lookup из `prepare`/`execute`) +
  `closeAll()`; ключ Kafka-консьюмера — **топик-алиас**, так что `prepare` и все `execute`
  по этому топику детерминированно делят один продвигающийся консьюмер;
- один `ResourceScope` на scenario run, владелец — `ScenarioRunner`; `closeAll()` в `finally` прогона —
  гарантия отсутствия утечек консьюмеров/соединений;
- generic: тем же механизмом DB/gRPC-адаптеры держат per-run соединения (ключ — datasource/target-алиас);
  core по-прежнему **не** зависит от адаптеров.

> **Контракт порядка (нормативно):** для async-expect шага, ловящего эффект более раннего триггера,
> раннер обязан вызвать `prepare` (позиционирование) для **всех** шагов до выполнения **первого** шага.
> Реализация требует расширения core-SPI — `StepExecutor.prepare` (default no-op, обратносовместимо) и
> `ResourceScope` в `StepExecutionContext`; это prerequisite Итерации 5 (§7).

### 8.8 Безопасность DB: классификация SQL и write-guard

Снимаем блокер уровня контракта для Итерации 6 (DB). **Проблема:** §4 stand-test-db определяет destructive
SQL *семантически* («`delete`/`update` без фильтра по `testRunId`», «запись вне whitelisted-схемы») и
помечает ограничения **обязательными**, но не задаёт, как это **достоверно извлечь из строки SQL** —
комментарии, строковые литералы, мульти-statement-батчи, `search_path`/квалификация схемы и биндинг
параметров делают наивный матчинг небезопасным (риск пропустить destructive-операцию на реальном
DEV/IFT-стенде → потеря данных). Это DB-аналог `KAFKA-SEEK-RACE` (§8.7): нетривиальный механизм,
объявленный обязательным, но не специфицированный. Здесь он фиксируется до старта реализации.

**Где форсятся guardrails (нормативно — и текущий статус).** Целевая модель — defense-in-depth в два слоя:
проверки, выводимые из **модели сценария** без IO, выполняет **`ScenarioValidator` статически до прогона**,
потребляя `ForbiddenOperation` (§8.6; это сохраняет единый источник истины и переиспользование в `ai-schema`,
§4 stand-test-ai-schema), а DB-адаптер форсит те же инварианты **повторно в рантайме** (эффективная схема
соединения, параметризованный bind). Для этого core получает **statement-классификатор**
(`SqlStatementClassifier` + `SqlClassification`/`SqlStatementKind` в `core.validation`, поверх общего
`SqlSpanScanner`) — **prerequisite Итерации 6** (§7), потребляемый и валидатором, и рантайм-guard'ом адаптера.
**Статус (2026-06-30): статическая половина отложена** — `ScenarioValidator` ещё **не** потребляет
классификатор (нужен registry-aware проход, которого пока нет; согласовано с уже существующей отсрочкой
env/forbidden-op-проверок), а DB `prepare` остаётся no-op (§8.7). Гарантия безопасности (destructive SQL не
доходит до стенда) держится **рантайм-guard'ом в адаптере** уже сейчас; статический wiring — known follow-up
(переиспользовать тот же классификатор, чтобы исключить дрейф статики и рантайма). Новый env-контракт **не**
требуется: `DatasourceDefinition` (`allowedSchemas`/`writeAllowed`, §9) уже реализован.

**Ограниченная грамматика (MVP — fail-closed):**

- **Один statement на шаг.** После вырезания комментариев и строковых литералов наличие разделителя
  statement'ов (`;` в середине) или непарсимый ввод → **reject** (`StandTestException`), никогда не
  «allow по умолчанию».
- **Классификация по ведущему ключевому слову:** `SELECT`/`WITH … SELECT` → **read**;
  `INSERT`/`UPDATE`/`DELETE` → **write**; `TRUNCATE`/`DROP`/`ALTER`/`CREATE`/`GRANT`/… → **DDL/destructive**.
- **Fail-closed уточнения (ведущее слово обманчиво):** `SELECT … INTO` → **reject** (он пишет, несмотря на
  ведущий `SELECT`); `INSERT … ON CONFLICT` / `ON DUPLICATE KEY` (upsert) → **reject**; `WITH …`, встраивающий
  data-modifying-ключевое слово (`INSERT`/`UPDATE`/`DELETE`) или `INTO`, → **reject** (а не «read по ведущему
  `WITH`»). Лексер вырезает комментарии/литералы/кавычки/`$$…$$` через общий `core.validation.SqlSpanScanner`;
  полный перечень осознанных fail-closed-кейсов и dialect-trade-offs (dollar-quoting, backtick-идентификаторы,
  `GO`/`/` batch-сепараторы, `[bracketed]` как array-subscript) — в `docs/arch/stand-test-db-decisions.md`.
- **readonly по умолчанию:** read разрешён всегда; write разрешён **только** на шаге `db.seed`/`db.cleanup`
  **и** при `DatasourceDefinition.writeAllowed == true` (иначе `DESTRUCTIVE_SQL_WITHOUT_ALLOW`). DDL/destructive
  в MVP запрещены всегда (отдельного destructive-allow-флага MVP не вводит).
- **Schema-whitelist:** таблица в write-statement обязана быть **schema-qualified ровно 2 частями**
  (`schema.table`, напр. `test_data.orders`), и эта схема ∈ `allowedSchemas`; неквалифицированная таблица
  **или** 3-частная `catalog.schema.table` в write → reject (доказуемо безопасна только 2-частная форма).
  Сравнение схемы — по **lower-case целевого** идентификатора (`Locale.ROOT`), поэтому `allowedSchemas`
  держит физические нижне-регистровые имена (как фолдит unquoted-идентификатор PostgreSQL). Для read
  квалификация не требуется.
- **`testRunId`-предикат для `UPDATE`/`DELETE`:** вместо парсинга авторского `WHERE` шаг обязан **декларировать**
  предикат явным маркером (`DbStep.whereTestRunId("test_run_id")`). Write-guard гейтит по **самому факту**
  объявленного маркера (булев признак «шаг несёт `WHERE_TEST_RUN_ID_COLUMN`»), а **не** по текстовому наличию
  `:testRunId` в SQL: подстрочный матч тривиально обходится (`UPDATE t SET note = :testRunId` или
  `DELETE … WHERE id = :testRunId OR 1=1` содержат подстроку, но не ограничивают строки по прогону). Адаптер
  **сам дописывает** `where <col> = :testRunId` и **запрещает** собственный `WHERE` в SQL шага (механизм — ниже,
  «Чтение и assertion»); `UPDATE`/`DELETE` без объявленного маркера → destructive → запрет. **Не регрессировать**
  к подстрочной проверке — это была CRITICAL-дыра, найденная post-impl ревью (`docs/arch/stand-test-db-decisions.md`, §8.8).
- **Только параметризованные binds.** Значения подставляются через `PreparedStatement` (`:name` → `?`,
  см. §4 stand-test-db), не строковой склейкой; это и закрывает SQL-инъекцию.

**`seed`/`testRunId`-тегирование (контракт).** `testRunId` проставляет **автор** seed-SQL через built-in
`${testRunId}` (например колонка `test_run_id = :testRunId`), адаптер его **не** инъектит автоматически;
`db.cleanup` затем работает по этой колонке. Так «seed помечает данные `testRunId`» становится конкретным.

**Чтение и assertion (нормативно).** `db.query` — **разовое** read-исполнение (без `Awaiter`): выполняет
SELECT и **каптит** значения колонок в `VariableStore` (`capture(name, column)`) для подготовки `${...}`
следующим шагам; ассертов не делает. `db.expectEventually` поллит SELECT через `stand-test-await` и
проверяет `expectSingleValue`: результат обязан быть **ровно одна строка, первый столбец**; **0 строк** =
«ещё не готово» (poll продолжается; на timeout → `StandTestAssertionError`); **>1 строки** →
`StandTestException` (неоднозначно). Сравнение значения — type-aware, как в REST/Kafka (числа по значению;
иная смена типа — несовпадение). Маркер `whereTestRunId(col)` **дописывает** предикат `where <col> =
:testRunId` (built-in `${testRunId}`); SQL шага не должен нести собственный `WHERE` (единственный источник
предиката), иначе reject.

**Failure-маппинг (§8.3).** Несовпадение `expectSingleValue` / timeout → `StandTestAssertionError`
(падение теста); reject классификатора, нарушение whitelist/write-guard, `SQLException`, ошибка
соединения/резолва ссылки → `StandTestException` (инфраструктура/конфиг). Новой константы
`ForbiddenOperation` **не** требуется — переиспользуются `DESTRUCTIVE_SQL_WITHOUT_ALLOW`,
`NON_WHITELISTED_DATASOURCE`, `RAW_JDBC_CLIENT` (§8.6).

> **Известное ограничение MVP (forward).** `db.cleanup` — **явный шаг**, не авто-teardown: раннер
> short-circuit'ит на первом падении и не имеет teardown-хука (§8.3), поэтому cleanup **не отработает после
> упавшего шага**. Для «черновика cleanup» это приемлемо; полноценный безопасный cleanup-движок и
> finally/teardown-хук — отложены (§4 stand-test-db).

### 8.9 Отчётность Allure: контракт attachments (prerequisite Итерации 7)

Снимаем блокер уровня контракта для Итерации 7 (Allure). **Проблема:** §4 `stand-test-allure` помечает
**обязательными** attachments REST request/response, Kafka-сообщений, SQL query/result, gRPC и «переменные
в отчёте», но **не задаёт, как payload доходит до Allure-консьюмера**. Единственный канал отчётности —
`ReportingEventPublisher` → `StepEvent`, а адаптеры кладут в `StepEvent.diagnostics` лишь *метаданные*
(`http.method/path/status`, `kafka.topic/key/offset`, `db.operation/datasource/…`) — тел запросов/ответов,
payload сообщений, текста SQL и строк результата там нет. Это DB/Kafka-аналог `KAFKA-SEEK-RACE` (§8.7) и
DB-write-guard (§8.8): нетривиальный механизм, объявленный обязательным, но не специфицированный.
Фиксируется до старта реализации.

**Где владеется (нормативно).** Attachments — **first-class контракт core**, а не свободные значения в
`diagnostics` (которые принадлежат логам, §17). Core получает иммутабельный value-type `Attachment` и поле
`attachments` на `StepResult` и `StepEvent` — **prerequisite Итерации 7** (§7), как `ResourceScope`/
`StepExecutor.prepare` был prerequisite Итерации 5, а `SqlStatementClassifier` — Итерации 6. Адаптеры
**производят** attachments (у них есть request/response/SQL/payload); раннер копирует
`StepResult.attachments` в `StepEvent(FINISHED).attachments`; **`stand-test-allure` потребляет** их из
события и рендерит как Allure-attachments. Core по-прежнему не зависит ни от Allure, ни от транспортов.

**Контракт `Attachment` (MVP — text-first).**

- `Attachment` = иммутабельный `record(String name, String mediaType, String content)`: имя для отчёта,
  MIME (`application/json` / `text/plain` / `application/sql`) и **текстовое** содержимое. Текст покрывает
  MVP-кейсы (JSON request/response, SQL, string-payload Kafka); бинарные (`byte[]`) и ленивые
  (`Supplier`-провайдеры) вложения — отложены.
- Attachments едут на **FINISHED**-событии (`StepEvent.phase == FINISHED` и соответствующем `StepResult`);
  STARTED их не несёт (к финишу адаптер знает и запрос, и ответ).
- `attachments` — defensively-copied `List<Attachment>`, по умолчанию пустой; `NoOpReportingEventPublisher`
  игнорирует его с нулевой стоимостью.

**Что прикладывает каждый адаптер (MVP).**

- **REST:** request (метод+путь+**редактированные** заголовки+тело) и response (статус+**редактированные**
  заголовки+тело).
- **Kafka:** send → produced key/headers/value; expect → consumed key/headers/value совпавшего сообщения.
- **DB:** **итоговый** SQL (после append `testRunId`-предиката), **имена** binds (не значения) и
  **ограниченный** preview результата (захваченные колонки / наблюдённое значение, не полный набор строк).
- **gRPC:** request/response — когда появится адаптер; контракт тот же.

**Редактирование секретов (нормативно — security-gate вложений).** Attachment строится через core-хелпер
`Attachments`, который **обязан**: (1) редактировать значения чувствительных заголовков по deny-list
(`Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` + настраиваемые имена) в
`***`; (2) **никогда** не прикладывать резолвнутые секреты (`ResolvedDatasource`/`ResolvedKafkaCluster`
url/user/password/jaas, §9) и значения binds из secret-ссылок; (3) ограничивать размер тела (truncate с
маркером `…[truncated N bytes]`). Это прямое продолжение §17 «не логировать secrets» на канал вложений —
логи такое уже не печатают, attachments не должны стать лазейкой.

**Переменные в отчёте (§4 stand-test-allure).** Снимок `VariableStore` (редактированный по тем же
правилам) прикладывается **одним** scenario-level attachment на FINISHED-сценарии — через тот же
`Attachment`-контракт, без отдельного механизма.

**Маппинг на Allure.** `stand-test-allure` реализует `ReportingEventPublisher`: `StepEvent(STARTED)` →
`AllureLifecycle.startStep` (имя = `stepType`/`stepId`); `StepEvent(FINISHED)` → статус из `StepStatus`
(FAILED/BROKEN), приложить `event.attachments()`, `stopStep`; `ScenarioEvent` → границы Allure-теста +
метки `scenarioId`/`testRunId`/`correlationId`; timeout-diagnostics берутся из `diagnostics`
FINISHED-события. Thread-confined: один прогон — один поток (как awaiter/`ResourceScope`), поэтому
STARTED/FINISHED-пары на thread-bound `AllureLifecycle` безопасны.

**Проводка.** Allure-publisher инжектится в раннер тем, кто строит прогон: junit-extension (plain JUnit)
или `spring-boot-starter`; дефолт остаётся `NoOpReportingEventPublisher` (Allure — не hard-dep core).
Адаптеры **не** знают про Allure — они лишь наполняют `StepResult.attachments`.

**Failure-mapping (§8.3).** Построение attachment — **best-effort, не влияет на pass/fail**: ошибка
редактирования/кодирования/обрезки **молча отбрасывает это вложение** (по возможности с
diagnostic-маркером), но **никогда** не роняет шаг и не подменяет `StepStatus`. Attachment — диагностика,
не ассерт; новой константы `ForbiddenOperation` не требуется.

**Отложено (post-MVP).** Бинарные/ленивые (`Supplier<byte[]>`) attachments; настраиваемые паттерны
редактирования; кастомные категории дефектов; агрегированные дашборды; полный дамп result-set.

---

## 9. Configuration / Environment model

Конфигурация окружений — это **security backbone**: точка, где применяются whitelist и secret
references, и единственный способ резолва логических алиасов в конкретные endpoints. Модель:

- **logical environment name**: `dev`, `ift`, `stage`;
- **logical service name**: `client-service` (REST/gRPC target);
- **logical topic alias**: `response-topic`;
- **logical datasource alias**: `mainDb`;
- **logical gRPC target alias**;
- **secret references** вместо секретов в коде (yaml/Java ссылаются на ключ env/secret-manager);
- **whitelist** endpoints/topics/datasources/schemas **per environment**;
- `ScenarioValidator` проверяет, что сценарий использует **только разрешённые алиасы**;
- **запрет hardcoded URLs** в сценариях;
- **запрет произвольных datasource URLs**.

Пример conceptual config (только иллюстрация в markdown — конфиги не создаются):

```yaml
environments:
  ift:
    services:
      client-service:
        baseUrl: ${CLIENT_SERVICE_URL}
        correlationHeader: X-Correlation-Id
    kafka:
      bootstrapServersRef: KAFKA_BOOTSTRAP_SERVERS     # ссылка на env/secret, не значение (§20)
      # securityProtocolRef / saslJaasConfigRef — для SASL/SSL-стендов (опц., тоже ссылки)
      topics:
        response-topic:
          name: pakt.response.ift
          correlation:
            source: header
            name: X-Correlation-Id
    datasources:
      mainDb:
        urlRef: MAIN_DB_URL
        userRef: MAIN_DB_USER
        passwordRef: MAIN_DB_PASSWORD
        allowedSchemas: [test_data, public]
        writeAllowed: false
```

Логические имена из DSL (`stand.rest("client-service")`, `db("mainDb")`, `kafka("response-topic")`)
резолвятся `EnvironmentRegistry` в endpoint + secret-ref **только** для выбранного `@StandEnv`/`env`.

**Kafka-кластер.** Адрес брокеров и креды берутся по **ссылкам**, не значениям: per-environment
`KafkaClusterDefinition` (`bootstrapServersRef` + опц. `securityProtocolRef` / `saslJaasConfigRef`),
резолвится `EnvironmentRegistry` так же, как `baseUrlRef` у REST (резолв ссылки в значение — на стороне
адаптера, см. §4 stand-test-rest). Топик-алиас (`TopicDefinition` = `alias` + реальное `name` +
носитель correlationId, §8.4) — отдельная сущность от кластера: один кластер на окружение, много
топиков. `EnvironmentDefinition` расширяется полем kafka-кластера (новый core-контракт, prerequisite
Итерации 5, §7).

---

## 10. Public API sketch (Java DSL)

> **Черновик / draft — НЕ реализуется на этом этапе.** Целевой вид Java DSL; финальные сигнатуры
> уточняются на итерациях 1–7. Java DSL — **lazy builder** (см. [§8.1](#81-single-scenario-model-pipeline)):
> сценарий **строится** как immutable `Scenario Model`, затем исполняется единым Validator + Runner.
> Никакого eager-IO в fluent-chain.

```java
// В MVP StandClient берётся из JUnit-расширения (без Spring):
//   StandTestExtension предоставляет StandClient как параметр теста / ресолвер.
//   @Autowired StandClient — это post-MVP удобство из stand-test-spring-boot-starter.

@StandTest(env = "ift")
class ExampleFlowTest {

    @Test
    void shouldProcessFlow(StandClient stand) {                 // resolved by StandTestExtension (no Spring)
        var scenario = Scenario.builder("example-flow")
                .environment("ift")
                .step(RestStep.post("client-service", "/api/request")   // RestStep — из stand-test-rest
                        .bodyFromResource("fixtures/request.json")
                        .injectCorrelationId()                          // SDK-owned correlationId → outbound header
                        .expectStatus(200)
                        .capture("requestId", "$.requestId")            // service-generated id
                        .build())
                .step(KafkaStep.expect("response-topic")                // KafkaStep — из stand-test-kafka
                        .correlationIdFromContext()
                        .withinSeconds(30)
                        .assertPath("$.status", "SUCCESS")
                        .build())
                .step(DbStep.expectEventually("mainDb")                 // DbStep — из stand-test-db
                        .sql("select status from request where id = :requestId")
                        .param("requestId", "${requestId}")
                        .withinSeconds(20)
                        .expectValue("SUCCESS")
                        .build())
                .build();                                               // immutable Scenario Model

        stand.run(scenario);   // Validator → Runner → StepExecutor SPI → adapters
    }
}
```

Подготовка данных (`given`-шаги), действие и проверки описываются как **отдельные шаги** модели —
их разделение читается так же явно, как в YAML (`given` / `then`).

**`kafka.send`** (produce-сторона, симметрично `RestStep.post`) — отдельный шаг, обычно в `given` до
триггера/ожидания:

```java
.step(KafkaStep.send("request-topic")
        .bodyFromResource("fixtures/event.json")   // или .body(inline) — classpath resource
        .key("${requestId}")                // опц. ключ партиционирования
        .injectCorrelationId()              // SDK-owned correlationId → носитель из конфига топика (§8.4)
        .build())
```

**`db.seed` / `db.cleanup`** (write-сторона, только при `writeAllowed`; см. [§8.8](#88-безопасность-db-классификация-sql-и-write-guard)) —
подготовка данных в `given` и явный soft-cleanup:

```java
.step(DbStep.seed("mainDb")                  // write — только на seed/cleanup + writeAllowed (§8.8)
        .sql("insert into test_data.request(id, test_run_id, status) values (:requestId, :testRunId, 'NEW')")
        .param("requestId", "${requestId}")  // :testRunId — built-in ${testRunId}, проставляет автор (§8.8)
        .build())
.step(DbStep.cleanup("mainDb")               // явный soft-cleanup по testRunId (не авто-teardown, §8.8)
        .sql("delete from test_data.request")            // без своего WHERE — предикат дописывает маркер
        .whereTestRunId("test_run_id")       // → where test_run_id = :testRunId, иначе destructive
        .build())
```

---

## 11. YAML DSL draft

> **Черновик / draft — НЕ реализуется на этом этапе.** Будущий декларативный формат (вход в ту же
> `Scenario Model`, §3). YAML-runner на этом этапе не создаётся. `correlationId` — **SDK-owned** и
> инжектится outbound; из ответа каптятся только service-generated id.
>
> **Формализация (Итерация 9):** `docs/arch/stand-test-scenario-yaml-design.md` — этот surface-синтаксис
> формализован там, вместе с отображением surface→internal на `GenericStep`, планом парсера (SnakeYAML) и
> раннера. Surface-имена ниже (`expectStatus`/`equals`/`assert`/`capture`/`timeout: 30s`) переводятся
> парсером во внутренние ключи `*StepParameters`.

```yaml
id: example-flow
title: Example async flow
env: ift                       # обязательный выбор whitelisted-окружения (§9)
tags: [integration, kafka, db]

given:
  - rest.post:
      service: client-service          # логический алиас, резолвится EnvironmentRegistry (§9)
      path: /api/request
      body: fixtures/request.json
      injectCorrelationId: true        # SDK-owned correlationId → X-Correlation-Id (имя из env-конфига)
      expectStatus: 200
      capture:
        requestId: $.requestId         # service-generated id, который SDK не знает заранее

then:
  - kafka.expect:
      topic: response-topic            # логический алиас топика (§9)
      correlationIdFromContext: true   # матч по SDK-owned correlationId, инжектированному выше
      timeout: 30s
      assert:
        "$.status": "SUCCESS"

  - db.expectEventually:
      datasource: mainDb               # whitelisted datasource, readonly (§4 stand-test-db, §9)
      timeout: 20s
      query: |
        select status
        from request
        where id = :requestId
      params:
        requestId: "${requestId}"
      equals: "SUCCESS"
```

**`kafka.send`** (produce, симметрично `rest.post`) — в `given` до триггера:

```yaml
given:
  - kafka.send:
      topic: request-topic             # логический алиас топика (§9)
      body: fixtures/event.json
      key: "${requestId}"              # опц.
      injectCorrelationId: true        # SDK-owned correlationId → носитель из конфига топика (§8.4)
```

**`db.seed` / `db.cleanup`** (write, только при `writeAllowed`; §8.8) — подготовка и явный soft-cleanup:

```yaml
given:
  - db.seed:                           # write — только seed/cleanup + writeAllowed (§8.8)
      datasource: mainDb
      sql: |
        insert into test_data.request(id, test_run_id, status)
        values (:requestId, :testRunId, 'NEW')
      params:
        requestId: "${requestId}"      # :testRunId — built-in ${testRunId}, проставляет автор (§8.8)

  - db.cleanup:                        # явный soft-cleanup по testRunId (не авто-teardown, §8.8)
      datasource: mainDb
      sql: delete from test_data.request
      whereTestRunId: test_run_id      # → where test_run_id = :testRunId, иначе destructive
```

---

## 12. Gradle strategy

Потребитель подключает SDK как **test-зависимости**, выравнивая версии через BOM-платформу. Координаты —
реальные (`ru.alfa.stand.test`), `<version>` подставляется при релизе (текущая `0.1.0-SNAPSHOT`).

```kotlin
testImplementation(platform("ru.alfa.stand.test:stand-test-bom:<version>"))

testImplementation("ru.alfa.stand.test:stand-test-junit")
testImplementation("ru.alfa.stand.test:stand-test-rest")
testImplementation("ru.alfa.stand.test:stand-test-kafka")
testImplementation("ru.alfa.stand.test:stand-test-db")
testImplementation("ru.alfa.stand.test:stand-test-allure")
```

Принципы:

- Версии модулей не указываются в `testImplementation(...)` — их даёт `platform(... :stand-test-bom)`.
- Подключаются только нужные адаптеры (минимальный набор для конкретного набора тестов).
- `stand-test-spring-boot-starter` подключается отдельно — только в Spring Boot тест-проектах.

---

## 13. Publishing & Versioning

- **Публикация в Nexus/Artifactory** (внутренний репозиторий; URL фиксируется при настройке релизного
  пайплайна, сейчас намеренно отложен).
- **SNAPSHOT-версии** — для разработки; **release-версии** — для потребителей.
- **SemVer policy**: `MAJOR.MINOR.PATCH`; ломающие изменения public API → `MAJOR`.
- **Backward compatibility** для public API соблюдается в пределах `MAJOR`.
- **Binary compatibility checks** — post-MVP: `japicmp` или `revapi` в CI.
- **BOM публикуется вместе с модулями** (общая версия SDK выравнивается платформой).
- Consumers подключают зависимости через `testImplementation(platform(...))` (см. §12). BOM
  публикуется первым/вместе, чтобы `platform(...)` резолвился у потребителя.
- Maven-публикации модулей должны нести POM-метаданные (name/description/scm) — наполняется при
  настройке релиза.

---

## 14. Supported JDK and bytecode compatibility

- **Java toolchain может быть современным, но bytecode target обязан быть совместим с потребителями.**
- **Рекомендуемый `--release`: 17 или 21** (LTS baseline для внутреннего test-SDK).
- Для внутреннего test-SDK предпочтительна LTS-база: **Java 17 или Java 21**.
- **Decision point / risk (текущий scaffold).** Сейчас в `gradle/libs.versions.toml` toolchain
  `java = "24"` (не-LTS) и не задан `--release`, то есть по умолчанию байткод Java 24 — потребители на
  JDK 17/21 такой артефакт **не загрузят**. Требуется решение: либо задать `--release` на LTS-baseline
  при сохранении toolchain, либо обосновать требование JDK 24 для потребителей. **Gradle сейчас не
  меняем — фиксируем решение в плане.**
- **Compatibility matrix (целевая):**

  | Параметр | Значение (целевое) |
  |---|---|
  | Build toolchain (JDK) | современный (например, 21/24) |
  | Bytecode `--release` | 17 или 21 (LTS baseline) |
  | Min consumer JDK | = выбранный `--release` |
  | Текущий scaffold | toolchain 24, `--release` не задан → **risk** |

---

## 15. Parallel execution and isolation

- Каждый scenario run получает **уникальный `testRunId`**.
- Каждый scenario run получает **уникальный `correlationId`**.
- Каждый scenario run получает **свой `VariableStore`** (см. [§8.2](#82-scenariocontext-variablestore-и-captureresolve)).
- **Kafka consumer group уникален per scenario run** (включает `testRunId`; см. §4 stand-test-kafka).
- **Async-expect консьюмеры пред-вооружаются до выполнения шагов** (§8.7): позиция фиксируется
  (`seekToEnd`) до триггера → снимает `KAFKA-SEEK-RACE`; консьюмеры живут в run-scoped `ResourceScope`
  и закрываются раннером.
- Test data изолируется через `testRunId`.
- **Cleanup не затрагивает чужие данные** (только по своему `testRunId`).
- **Static mutable state запрещён.**
- `ThreadLocal` — только при строгой причине; по умолчанию избегать.

### Реализовано (2026-07)

- **Модель по умолчанию:** классы — `concurrent`, методы — `same_thread` (инвариант «один прогон = один
  поток»), **только in-JVM** (`maxParallelForks=1`). Каноничный конфиг — `junit-platform.properties` в
  `src/test/resources` потребителя; `stand-test-example` поставляет его как эталон.
- **Fail-closed гардрейлы (enforce, не документация):**
  - `db.seed` INSERT обязан тегировать строки `:testRunId` **и объявить tag-колонку** через
    `DbStep.taggedByTestRunId("test_run_id")` (та же колонка, что фильтрует `db.cleanup`). `DbWriteGuard`
    **структурно проверяет** (через `SqlStatementClassifier.insertColumns`), что объявленная колонка есть в
    списке колонок INSERT — не просто что `:testRunId` встречается где-то в тексте; сид, тегирующий
    не-reap-колонку (`INSERT INTO t(id) VALUES (:testRunId)`), отклоняется до IO (иначе строка не
    подхватывается testRunId-scoped cleanup → утечка).
  - `kafka.expect` обязан иметь per-run **уникальный** дискриминатор — `correlationIdFromContext()` или
    `key(...)` c `${...}`-плейсхолдером (константный key отклоняется, т.к. два прогона матчили бы его на общем
    топике). Проверка в `KafkaStep.build()` и в `KafkaStepExecutor` **в `prepare()` до арминга** консьюмера
    (покрывает YAML/raw-params, fail-closed до IO как в DB).
- **Опт-аут аннотации** в `stand-test-junit` (тонкие фасады над JUnit): `@StandParallelSafe`
  (`@Execution(CONCURRENT)`), `@StandSerial` (`@Execution(SAME_THREAD)`), `@StandIsolated` (`@Isolated`);
  для взаимного исключения по одному ресурсу — нативный `@ResourceLock("<alias>")`.
- **Контракты:** `ReportingEventPublisher` и `StandTestExtension` javadoc фиксируют требование thread-safety
  разделяемого раннера/паблишера; Allure-паблишер чистит per-thread step-stack на `ScenarioPhase.FINISHED`.
- **Тесты:** `DefaultScenarioRunnerTest` (изоляция store + уникальность id на 64 потоках),
  `DefaultAwaiterTest` (изоляция attempts/diagnostics), `ParallelFrameworkExecutionExampleTest` (24 прогона
  через один `StandClient` против общей H2 — уникальные id, изоляция store/reporting, отсутствие утечек).

---

## 16. Testing strategy for SDK itself

> Запрет Testcontainers относится к **потребительским** стендовым автотестам как основной стратегии,
> но **не запрещает** использовать test doubles при тестировании **самого SDK**.

- **Unit-тесты для core** — без IO.
- **Fake / in-memory adapters** — для проверки `Runner`/`Validator` (через `StepExecutor` SPI).
- **Embedded broker** или controlled fake допустимы для unit/integration-тестов **самого SDK**.
- Реальные DEV/IFT-стенды **не обязательны** для unit-тестов SDK.
- Adapter SPI должен позволять тестировать **timeout/negative** кейсы без реального стенда.
- **Coverage gate: минимум 80 %** (см. §18 DoD).

---

## 17. Logging and observability

- **SLF4J** как logging-facade (core зависит только от `slf4j-api`).
- **MDC** для `scenarioId`, `testRunId`, `correlationId`.
- **Structured logs** там, где возможно.
- **Не логировать secrets.**
- **Timeout diagnostics** попадают и в logs, и в **reporting events** (`StepEvent`/`ReportingEvent`).
- **Allure-адаптер потребляет reporting events**, а не захардкожен в core (см.
  [§8.5](#85-module-ownership-validator-runner-step-executor-spi-standclient)).

---

## 18. Definition of Done

DoD различается для итераций с кодом и design-only итераций.

**Для code-итераций (1–8):**

- [ ] Gradle build проходит (`./gradlew build` зелёный);
- [ ] unit-тесты проходят;
- [ ] coverage ≥ 80 %;
- [ ] публичный API документирован;
- [ ] Javadoc для публичных API;
- [ ] нет `Thread.sleep`;
- [ ] нет hardcoded stand URLs;
- [ ] нет secrets в коде/yaml;
- [ ] нет raw Kafka/JDBC bypass в примерах;
- [ ] timeout-поведение покрыто тестами;
- [ ] negative-кейсы покрыты;
- [ ] проброс SDK-падений в JUnit failure покрыт тестами;
- [ ] reporting events покрыты тестами;
- [ ] есть README модуля и технические примеры usage (без бизнес-логики реального проекта);
- [ ] есть Allure-диагностика (шаги/attachments/метаданные).

**Для design-only итераций (0, 9, 10):**

- [ ] markdown-раздел обновлён;
- [ ] примеры помечены как draft;
- [ ] runtime-код не добавлен;
- [ ] раздел отревьюен и согласован;
- [ ] противоречия в плане устранены.

**Versioning / binary compatibility** — упомянуты в [§13](#13-publishing--versioning) (binary-compat
checks как post-MVP).

---

## 19. Риски

| Риск | Mitigation |
|---|---|
| SDK станет слишком большим | Жёсткие границы модулей (§4), тонкий фасад поверх зрелых инструментов, анти-правила (§20); ревью на «не лезет ли бизнес-логика в SDK». |
| Тесты будут flaky | Только общий await-механизм (§2.4), запрет `Thread.sleep`, configurable timeout + poll + diagnostics; negative/timeout-тесты в DoD. |
| Команды начнут использовать БД как основной assert | Правило §2.9 + §4 `stand-test-db` (readonly по умолчанию, БД как probe); линт/ревью; приоритет публичного API/события над БД. |
| AI начнёт генерировать опасный код | `stand-test-ai-schema`: JSON Schema + forbidden operations из единого core-источника (§8.6); запрет произвольного Java; валидатор сценариев (§3). |
| Появятся разные стили тестов | Единый DSL и единая `Scenario Model` (§3), стандартизованные идентификаторы (§2), примеры usage и стартер. |
| Стенды будут нестабильны | await-диагностика и таймауты с понятными отчётами; ретраи только через политику await; стенды только из whitelist (§2.7). |
| Секреты попадут в репозиторий | §2.8 + §9: секреты только из env/secret-manager; запрет значений в коде/yaml; проверка в DoD и (позже) pre-commit/CI-скан. |
| Kafka offset strategy будет работать неправильно | §4 `stand-test-kafka` + §8.7: уникальный group.id per run; `assign`+`seekToEnd` (start-from-now) с pre-arm в фазе `prepare`; поллинг до timeout через await; selection по `correlationId`/key; timeout-diagnostics с числом просмотренных сообщений; тесты на «не нашли». |
| Сложность поддержки Gradle multi-module | Единые convention в root `subprojects { }`, BOM-платформа, минимальные графы зависимостей, CI на каждый модуль; периодический ревью графа. |
| Несовместимость версий зависимостей | BOM-платформа выравнивает версии (§13); обновления через version catalog; smoke-проверка у потребителя. |
| Java bytecode incompatibility | §14: фиксированный `--release` на LTS baseline; CI проверяет target; toolchain 24 помечен как decision point. |
| AI schema drift from runtime validator | §8.6: единый источник forbidden-ops в core; `ai-schema` генерирует ограничения из core-контракта. |
| Java DSL bypassing validator | §8.1: lazy builder + анти-правило (§20); запрет eager-IO Java API; единый Validator для обоих входов. |
| Kafka seek race (`KAFKA-SEEK-RACE`) | **§8.7** (механизм): консьюмеры пред-вооружаются (`assign`→`seekToEnd`→`position`) в фазе `prepare` раннера **до** любого шага → до триггера; живут в run-scoped `ResourceScope`; контракт порядка нормативен. §4 `stand-test-kafka` — детали стратегии; диагностика при timeout. |
| DB adapter станет unsafe generic DB client | **§8.8** (механизм): statement-классификатор fail-closed (read/write/destructive), один statement на шаг, schema-qualified write ∈ `allowedSchemas`, обязательный `testRunId`-предикат, параметризованные binds; статически в `ScenarioValidator` + повторно в адаптере. §4 `stand-test-db`: readonly по умолчанию, write-allow flag, datasource+schema whitelist, probe-first правило, границы адаптера. |
| Parallel execution interference | §15: уникальные `testRunId`/`correlationId`, per-scenario `VariableStore`, уникальный consumer group, изоляция данных по `testRunId`, запрет static mutable state. |

---

## 20. Что нельзя делать (анти-правила)

- **Не писать свой JUnit** — интеграция через extension JUnit 5.
- **Не писать свой HTTP-клиент**, если можно использовать готовый (RestAssured/OkHttp/`java.net.http`).
- **Не писать свой Kafka-клиент с нуля** — поверх `kafka-clients`.
- **Не делать Testcontainers основой** стендового тестирования — цель библиотеки — реальные
  DEV/IFT-стенды (test doubles для тестов самого SDK допустимы — §16).
- **Не делать UI.**
- **Не начинать с YAML-runner** до стабилизации core-модели.
- **Не добавлять бизнес-логику конкретного сервиса** в SDK.
- **Не смешивать SDK и тесты конкретного проекта.**
- **Не делать DB-adapter слишком мощным unsafe-инструментом** (readonly по умолчанию, destructive —
  только с явным разрешением, datasource + schema whitelist).
- **Не выполнять IO в fluent-chain Java DSL** (imperative eager-IO API запрещён — обходит Validator и
  guardrails, см. §8.1).
- **Не использовать raw Kafka consumers/producers и raw JDBC в тестах в обход SDK.**
- **Не хардкодить stand URLs / datasource URLs** — только whitelisted-алиасы из env-конфига (§9).
- **Не вести отдельный список forbidden operations** в `ai-schema` — единый источник в core (§8.6).
- **Scaffold-консистентность.** Комментарии `Planned internal dependencies` в
  `stand-test-scenario-yaml/build.gradle.kts` и `stand-test-ai-schema/build.gradle.kts` приведены к
  целевому графу (§5): `scenario-yaml` → только `core`; `ai-schema` → только `core` (без
  `scenario-yaml` и адаптеров); adapter-модули не зависят друг от друга; core не зависит от
  адаптеров/интеграций. Менять можно только комментарии/документацию скаффолда, не добавляя реальные
  зависимости.

---

## 21. Следующий шаг после плана

После согласования этого плана и закрытия **Итерации 0 (§8 Core contracts)** следующим этапом будет
**реализация `stand-test-core`** (Итерация 1, §7):

- базовые value-объекты (`ScenarioId`, `TestRunId`, `CorrelationId`, `Environment`);
- scenario context (`ScenarioContext`) + `VariableStore`/`VariableResolver`;
- `ScenarioValidator`/`ScenarioRunner`/`StepExecutor` SPI, `StandClient`-контракт;
- result-модель (`StepResult`, `ScenarioResult`, `StepStatus`);
- исключения (`StandTestException`, `StandTestAssertionError`), reporting-event SPI;
- unit-тесты на публичный API core.

> **Принятое отклонение (Итерация 1, C-2).** `Environment` из списка value-объектов выше реализован как
> голый `String` (в `ScenarioContext`/`Scenario`/`EnvironmentRegistry`/`EnvironmentDefinition.name`), а
> не как отдельный value-record. Это осознанное MVP-упрощение: окружение валидируется там, где несёт
> смысл (`ScenarioContext` отклоняет blank; `DefaultScenarioValidator` — `ENVIRONMENT_REQUIRED`), а
> `Scenario` остаётся permissive (blank ловит валидатор, а не билдер). Введение типа `Environment`/
> `EnvironmentName` отложено как будущее (ломающее) изменение. См.
> `stand-test-core/docs/stand-test-core-remediation-plan.md` (C-2).

Реализация адаптеров (REST/Kafka/DB/gRPC), JUnit-расширения, YAML-runner и AI-schema на этом шаге
**не начинается** — строго по порядку итераций из §7.
