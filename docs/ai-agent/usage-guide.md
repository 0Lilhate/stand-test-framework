# Инструкция: от OpenAPI-спеки до Java-автотеста

Практический walkthrough по AI-authoring-киту `stand-test-sdk`. Два входа в базу знаний:
**машинный контракт** (OpenAPI/AsyncAPI/proto/SQL) через `/stand-test-kb-update` — §2, на примере
спецификации `tks.client.pckg` v10; и **неструктурированный документ** (ФС/DOCX/PDF/BRD/ТЗ) через
candidate-first конвейер `/stand-test-ingest-spec` → review → apply — §2б, на примере ФС
`ПК ПАКТ.Льготы 2025.04.17`. Общая справка по киту — [`README.md`](README.md), контракт базы знаний —
[`knowledge-base/README.md`](knowledge-base/README.md), staging-слой кандидатов —
[`knowledge-base/candidates/README.md`](knowledge-base/candidates/README.md).

## 0. Что где лежит

| Артефакт | Расположение |
|---|---|
| Кит агента (скиллы/команды/правила) | `docs/ai-agent/.claude/` → копируется в `.claude/` consumer-репо |
| Контракт KB (схемы + примеры) | `docs/ai-agent/knowledge-base/` |
| KB вашего проекта | `<consumer>/knowledge-base/{schema,services,endpoints,kafka,db,grpc,environments,mappings,schemas}/` — **в корне репо**, не в `src/test/resources`: KB читает только агент с файловой системы, тестам и SDK она не нужна |
| Staging кандидатов (для §2б) | `<consumer>/knowledge-base/candidates/<document-id>/` — куда `/stand-test-ingest-spec` кладёт кандидатов из неструктурированных доков ДО ревью и промоута; исходный бинарник — в gitignored `_source/` |
| Фикстуры и registry | `src/test/resources/` — их, наоборот, читает SDK в рантайме (`bodyFromResource`, `application.yml`/`stand-test-environments.yml`) |
| Команды (машинный контракт) | `/stand-test-kb-update`, `/stand-test-generate-env`, `/stand-test-generate-java-test`, `/stand-test-validate`, `/stand-test-review-generated-test`, `/stand-test-debug` |
| Команды (неструктурированный документ) | `/stand-test-ingest-spec`, `/stand-test-review-kb-candidates`, `/stand-test-apply-kb-candidates` |

## 1. Установка (однократно, в consumer-репо)

```bash
cp -R <sdk-repo>/docs/ai-agent/.claude/* <consumer>/.claude/
mkdir -p knowledge-base                                            # KB живёт в КОРНЕ consumer-репо
cp -R <sdk-repo>/docs/ai-agent/knowledge-base/schema knowledge-base/schema
# каталоги services/endpoints/kafka/db/grpc/environments/mappings/schemas создаст первый kb-update
```

Плюс SDK-модули в `testImplementation` (BOM + junit или starter + адаптеры + config/allure +
`io.qameta.allure:allure-junit5`) — см. [`README.md`](README.md) §Installation.

## 2. Шаг 1: спека → база знаний (`/stand-test-kb-update`)

Сохраните спеку (например, `specs/tks-client-pckg-v10.yaml`) и вызовите:

```text
/stand-test-kb-update
source: specs/tks-client-pckg-v10.yaml
sourceType: openapi
mode: dry-run
```

Агент распарсит спеку и **механически** выведет кандидатов в KB. Правила деривации на этом примере:

- **id сервиса**: `info.title` `tks.client.pckg` → точки в дефисы → `tks-client-pckg`;
- **id endpoint'ов**: `operationId` (`get`/`post`) совпадает с HTTP-методом → бесполезен →
  fallback `<method>-<path-slug>`: `get-rest`, `post-rest`. Переименовать можно только на этом
  шаге, до apply — после apply id заморожены (на них ссылаются mappings и тесты);
- **base URL**: `servers` параметризован (`{schema}://{host}:{port}{basePath}`) → в KB попадает
  только **имя** env-переменной `TKS_CLIENT_PCKG_BASE_URL`; её значение на стенде включает
  basePath (`http://<host>:19080/tks/client/pckg/v10`), а `path` в KB остаётся `/rest`;
- **схемы тел**: `components.schemas` извлекаются в файлы
  `knowledge-base/schemas/rest/tks-client-pckg/*.schema.json` (`schemaRef` — путь относительно
  корня KB; эти файлы, как и вся KB, нужны только агенту). **Фикстуры — исключение**:
  `fixtureTemplate: fixtures/...` — это classpath-путь в `src/test/resources`, потому что
  фикстуры читает сам SDK через `bodyFromResource`/`requestFromResource`;
- **`domain`/`tags`**: из сегментов `info.title` (`tks.client.pckg` → domain `tks`, tags
  `tks`/`client`/`pckg`) — при большом корпусе именно они снимают неоднозначность имён на
  kb-lookup;
- **имена файлов**: строго `<collection>/<service-id>.yml` — это позволяет lookup'у читать
  большую KB адресно (сначала `services/`, затем только файлы совпавших сервисов).

Кандидаты, которые лягут в файлы после apply:

```yaml
# services/tks-client-pckg.yml
services:
  - id: tks-client-pckg
    name: tks.client.pckg
    description: REST API получения и подключения ТП/ПУ/Подписок клиентом из ПО Такса
    domain: tks
    environments: [ift]
    endpoints: [get-rest, post-rest]
    tags: [tks, client, pckg]
    # correlation: ОТСУТСТВУЕТ в спеке — вопрос человеку (см. ниже)

# endpoints/tks-client-pckg.yml
endpoints:
  - id: get-rest
    serviceId: tks-client-pckg
    method: GET
    path: /rest
    description: Получение клиентом ТП/ПУ/Подписок по коду
    requiredHeaders: [X-External-System-Code, X-External-User-Code]
    query:
      required: [clientCode, packageCode]
    response:
      status: { success: 200 }
      schemaRef: schemas/rest/tks-client-pckg/package-with-prices.schema.json
    allowedEnvironments: [ift]

  - id: post-rest
    serviceId: tks-client-pckg
    method: POST
    path: /rest
    description: Подключение клиентом ТП/ПУ/Подписки
    requiredHeaders: [X-External-System-Code, X-External-User-Code]
    query:
      required: [clientCode, packageCode]
    request:
      contentType: application/json
      schemaRef: schemas/rest/tks-client-pckg/connect.schema.json
      fixtureTemplate: fixtures/tks-client-pckg/connect.json
      requiredFields: [clientCode, packageCode, packagePeriod]   # из Connect.required
    response:
      status: { success: 201 }        # ← см. conflict №1 ниже
      schemaRef: schemas/rest/tks-client-pckg/connected-package.schema.json
    allowedEnvironments: [ift]

# environments/ift.yml (добавка)
environments:
  - id: ift
    services:
      - serviceId: tks-client-pckg
        baseUrlRef: TKS_CLIENT_PCKG_BASE_URL
```

Dry-run отчёт покажет **вопросы человеку** — агент не решает их сам:

1. **POST декларирует два success-кода (200 и 201)** — контракт KB хранит один канонический;
   выберите (обычно 201 Created для подключения).
2. **Correlation-заголовок в спеке не описан** — если у сервиса есть сквозной заголовок
   (X-Correlation-Id и т.п.), добавьте `correlation:` в service-entry; без него
   `injectCorrelationId()` в тестах недоступен.
3. `X-External-System-Code`/`X-External-User-Code` попали в `requiredHeaders` как не-секретные
   заголовки; **значения** (`TEST`) в KB не хранятся — их задаёт кейс/фикстура.
4. Имя `TKS_CLIENT_PCKG_BASE_URL` — подтверждение, что переменная будет заведена на стенде.

После ответов повторите с `mode: apply` — агент запишет файлы (коллекции отсортированы по id,
ключи в порядке схемы), прогонит валидацию KB и чеклист `kb-entry-review-checklist.md`.
Ошибки 400/422/500 из спеки в контракт не попадают — негативные ожидания описывает кейс.

## 2б. Другой вход: неструктурированный документ (ФС/DOCX/PDF/BRD/ТЗ)

`/stand-test-kb-update` (§2) — для **машинных** контрактов (OpenAPI/AsyncAPI/proto/SQL): парс
детерминирован, кандидаты сразу проецируются в KB. Для **прозы** (ФС, BRD, ТЗ, Confluence-экспорт)
вход неоднозначен, поэтому работает отдельный **candidate-first** конвейер: сначала кандидаты со
*provenance* и *confidence* в staging-слой, затем ревью человеком, и только одобренное подмножество
промоутится в curated KB. Три команды:

```text
/stand-test-ingest-spec          # документ → knowledge-base/candidates/<doc-id>/  (dry-run по умолчанию)
/stand-test-review-kb-candidates # проверка кандидатов одного документа → отчёт-гейт для человека
/stand-test-apply-kb-candidates  # одобренное подмножество → curated KB           (dry-run по умолчанию)
```

Разбор на реальном примере — ФС `ПК ПАКТ.Льготы 2025.04.17` (DOCX; интеграция МУП↔ПК в Таксе). Полный
результат этого прогона лежит в репозитории SDK:
[`knowledge-base/candidates/fs-pk-pakt-lgoty-2025-04-17/`](knowledge-base/candidates/fs-pk-pakt-lgoty-2025-04-17/).

### Шаг A — ingest (`/stand-test-ingest-spec`)

```text
/stand-test-ingest-spec
source: docs/source/ФС ПК ПАКТ.Льготы.2025.04.17.docx
documentType: docx
mode: create-candidates          # dry-run по умолчанию только печатает отчёт
```

Агент читает документ через внешний конвертер (docx→markdown), режет на секции по anchor-индексу
(heading→page→section→quote) и извлекает контракты/потоки/правила/сценарии. Результат —
`knowledge-base/candidates/fs-pk-pakt-lgoty-2025-04-17/`:

```
fs-pk-pakt-lgoty-2025-04-17/
  _source/            # GITIGNORED: исходный .docx + normalized.md + manifest.json (никогда не коммитятся)
  source-document.yml # провенанс-корень: id, имя, версия, sha256, systems
  services.candidates.yml       # { candidates: [...] } candidateType: service
  endpoints.candidates.yml      # candidateType: endpoint
  kafka-topics.candidates.yml   # candidateType: kafka-topic
  db.candidates.yml             # datasource / db-table / db-probe
  business-flows.candidates.yml # семантические потоки (trigger→steps→result)
  business-rules.candidates.yml # правила / критерии приёмки
  test-scenarios.candidates.yml # кандидаты автотестов (кормят case-analysis)
  glossary.candidates.yml       # словарь домена (ЛТР/ИТР/ПК/МУП/ТК…)
  unresolved.candidates.yml     # ГЭПЫ: то, чего в документе нет
  conflicts.candidates.yml      # расхождения candidate↔curated / candidate↔candidate
  review-decisions.yml          # решения человека по семантическим семьям
  extraction-report.md          # поверхность ревью
```

**Три инварианта, машинно защищающие целостность:**

- **Провенанс обязателен.** Каждый кандидат несёт `source` (documentId + documentName + **quote** +
  page/section) и `confidence` (`high|medium|low`). Пример service-кандидата:
  ```yaml
  # services.candidates.yml
  - candidateId: pk-product-catalog
    candidateType: service
    confidence: high
    source:
      documentName: "ФС ПК ПАКТ.Льготы 2025.04.17"
      page: 8
      section: "2.3"
      quote: "Реализации в ПК внутреннего REST-сервиса Таксы для передачи запроса по ЛТР/ИТР…"
    normalized: { id: pk-product-catalog, title: "ПК — Продуктовый каталог (Такса)" }
  ```
- **Unresolved вместо догадок.** Документ описывает внутренний REST-сервис обработки ЛТР/ИТР
  (§4.2.1.2 задаёт даже success-код 200), но **не задаёт HTTP-метод и path** — агент НЕ выдумывает
  `POST /process`, а эмитит в `unresolved.candidates.yml` элемент вида
  `type: missing-http-method` / `missing-endpoint-path` с `blockingFor: [endpoint]`. То же для имени
  Kafka-топика, schema-квалификатора БД и testRunId-колонки.
- **Стерильность.** Любой free-text отвергает URL/JDBC/секреты (схемы `noUrl`/`secretShape`);
  дословный источник остаётся только в gitignored `_source/`, в кандидатах — редактированная `quote`.

Все committed-кандидаты валидируются в CI: `KbCandidateSchemaValidationTest` глоббит
`candidates/**/*.yml`, сопоставляет каждый файл с его candidate-схемой по top-level ключу и прогоняет
секрет/URL-скан — битый, provenance-неполный или несущий секрет кандидат валит билд.

### Шаг B — review (`/stand-test-review-kb-candidates`)

```text
/stand-test-review-kb-candidates
document-id: fs-pk-pakt-lgoty-2025-04-17
```

Перевалидирует кандидатов против схем, группирует по `candidateType` с `confidence`/`Derived from`,
поднимает low-confidence, unresolved и conflicts, прогоняет secret/URL/production-скан и
классифицирует каждого: **apply-eligible** (`high`, одобрен) / **needs-tick** (`medium` — нужна
явная галочка человека) / **blocked** (`low`, конфликт, partial, `promotionBlocked`). Curated KB не
трогает — только отчёт-гейт для человеческого решения; решения записываются в
`review-decisions.yml` (для семантических семей) и в `review`-блок кандидата.

### Шаг C — apply (`/stand-test-apply-kb-candidates`)

```text
/stand-test-apply-kb-candidates
document-id: fs-pk-pakt-lgoty-2025-04-17
mode: dry-run                    # потом --apply
```

Промоутит **только** одобренное человеком, `high`-confidence и conflict-free подмножество: проецирует
кандидата в curated-entry (снимает provenance/confidence/naturalKey), перевалидирует против
**строгой** `stand-test-knowledge-base.schema.json`, и единственным детерминированным писателем
(`stand-test-kb-update`) пишет файлы + строку в `promotion-log.yml`. Никогда не удаляет, не
переименовывает id и не перезаписывает ручное поле без разрешённого человеком конфликта.

**Честный итог этого пилота — он показывает целостность конвейера, а не полноту:**

- **Промоутнуты только 3 high-confidence SERVICE-идентичности** — `pk-product-catalog`,
  `mup-subscription-module`, `pakt-lgoty` — в
  [`services/pakt-lgoty.yml`](knowledge-base/services/pakt-lgoty.yml). `tk-tariff-calculator`
  (`medium`) — held, `dwh` (`low`) — held.
- **Эндпоинты / Kafka-топики / БД НЕ промоутнуты**: их method/path/имя/схема остаются в `unresolved`,
  а curated `service` требует ещё и `environments`, которого ФС не задаёт. Промоут потребовал бы
  выдумывания — `apply` отказывается (это и есть «no invented contracts»).
- `environments: [ift]` у промоутнутых сервисов — **записанное человеческое допущение** (ФС не задаёт
  env/base-url), явно помеченное в самом файле, в `promotion-log.yml` и в `review-decisions.yml`
  (`disposition: promoted-identities-only`).
- Итог: `kb-lookup` на кейсе про ПАКТ.Льготы теперь резолвит 3 реальных сервиса, но конкретный
  endpoint по-прежнему `missing` — что честно (см. §4: агент вернёт blocking-вопрос, а не выдумает
  path). Достроить контракт можно доработкой ФС («Этап 2») или отдельным OpenAPI/proto через §2.

Дальше — как в §3–4: `/stand-test-generate-env` по промоутнутым сервисам, затем
`/stand-test-generate-java-test` по текстовому кейсу.

## 3. Шаг 2: KB → env-конфигурация (`/stand-test-generate-env`)

```text
/stand-test-generate-env
environment: ift
target: src/test/resources/application-test.yml   # или stand-test-environments.yml для plain JUnit
mode: dry-run
```

Для этой спеки diff добавит (starter-поверхность; refs — **голые имена** переменных,
`${...}`-плейсхолдеры внутри `*-ref` на starter-поверхности запрещены — Spring зарезолвил бы их
в значения до того, как SDK увидит ссылку):

```yaml
stand:
  test:
    environments:
      ift:
        services:
          tks-client-pckg:
            base-url-ref: TKS_CLIENT_PCKG_BASE_URL
```

Всё постороннее в файле не тронется: управляемое поддерево — только
`stand.test.environments.<env>`. Убедившись в diff — `mode: apply`. Перед реальным прогоном
экспортируйте переменную (значение живёт только в окружении, не в git):

```bash
export TKS_CLIENT_PCKG_BASE_URL="http://<стенд>:19080/tks/client/pckg/v10"
```

## 4. Шаг 3: текстовый кейс → тест (`/stand-test-generate-java-test`)

```text
/stand-test-generate-java-test
target module: <ваш тестовый модуль>
target package: ru.<org>.tks.stand
environment: ift
mode: draft

Кейс:
Проверить подключение подписки клиенту.
Предусловие: клиент существует в Таксе.
Действие: POST /rest c clientCode, packageCode и телом Connect (packagePeriod = 1M).
Ожидания: HTTP 201; в ответе code = <packageCode>; connection.stateCode заполнен.
```

Агент пройдёт стадии: анализ → **kb-lookup** (найдёт `tks-client-pckg`/`post-rest`; чего нет в
KB — вернёт в `missing`, не выдумает) → environment mapping → scenario design → генерация класса
и фикстуры `fixtures/tks-client-pckg/connect.json` → safety review →
`./gradlew compileTestJava checkstyleTest` → `/stand-test-validate` (skip-gate: без env-переменных
тест обязан SKIP, не падать).

**Blocking-точки, где агент остановится и спросит**: конкретные `clientCode`/`packageCode`
(данные стенда), способ проверки предусловия «клиент существует», значения X-External-заголовков.
Без `correlation:` в registry событийные/Kafka-проверки для этой API недоступны — kb-lookup явно
сообщит об этом.

## 5. Обновление спеки (пришла v11) и приоритет источников

Тот же `/stand-test-kb-update` с новым файлом: неизменённые entries → `unchanged`; новые
поля → `updated` (перезаписываются только source-derived значения); ручные правки, разошедшиеся
со спекой → `conflicts` (решает человек); исчезнувшие операции → `removed-candidates`
(**только отчёт — из KB ничего не удаляется автоматически**). Id и алиасы не переименовываются
никогда.

Когда один и тот же endpoint описан разными видами источников, действует **ранг**:
`openapi`/`asyncapi`/`proto`/`sql` (1) > `application-yml` (2) > `markdown` (3) > пересказ
текстом (4). Поле, записанное источником ранга N, обновляется только источником того же или
более сильного ранга; более слабый источник, расходящийся с сильным, даёт `conflict`, а не
перезапись. Ручные правки стоят выше ранга 1 — их меняет только человек. Ранг источника каждого
entry фиксируется в отчёте («Derived from»), так что правило применяется механически и на
следующих прогонах.

## 5а. Массовая загрузка: много спек + разная документация

- Каждая спека — **отдельный** прогон `/stand-test-kb-update` (свой diff, своё одобрение);
  порядок загрузки: сначала машинные контракты (OpenAPI/proto/DDL), затем прозаические доки —
  так прозе останется только дополнять, а расхождения с контрактами сразу лягут в `conflicts`.
- Markdown/вики/переписка — легитимный вход, но извлекается **только явно заявленное**
  («extract ONLY what is stated»): дока без точного пути даст кандидата с вопросом, а не
  додуманный path. Плохая дока делает цепочку «вопросной», но не недетерминированной.
- При корпусе >10 сервисов обязательны конвенции из
  [`knowledge-base/README.md`](knowledge-base/README.md) §Conventions for large KBs: файл на
  сервис, `domain` + `tags` в каждом service-entry, `mappings/` хранится в репо (история
  сопоставлений — корпус дизамбигуации для следующих lookup'ов).

## 6. Жёсткие правила (что гарантирует детерминизм)

- Один контракт: каждый KB-файл валиден против
  [`knowledge-base/schema/stand-test-knowledge-base.schema.json`](knowledge-base/schema/stand-test-knowledge-base.schema.json)
  (`additionalProperties: false` — «лишнее» поле не пройдёт).
- В KB нет и не может быть: секретов, URL/JDBC/host:port (невыразимы паттернами схемы),
  production-окружений, произвольного SQL (только lowercase `select`-пробы), скриптов.
- Агент **не выдумывает** контрактные детали: нет entry → `missing` → вопрос человеку; новые
  entries — только через `/stand-test-kb-update` с dry-run и одобрением.
- Проверка контракта в SDK-репо: `./gradlew :stand-test-ai-schema:test`; в consumer-репо —
  скопируйте `KnowledgeBaseSchemaValidationTest` по инструкции из
  [`knowledge-base/README.md`](knowledge-base/README.md).
