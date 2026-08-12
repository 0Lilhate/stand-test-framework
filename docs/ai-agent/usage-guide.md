# Руководство пользователя: как работать с китом

Как превратить текстовый бизнес-кейс в автотест на `stand-test-sdk` силами AI-агента (Claude Code или
opencode). Здесь — только порядок действий, входы команд и что делать с их выводом. Устройство кита —
[`README.md`](README.md), контракт базы знаний — [`knowledge-base/README.md`](knowledge-base/README.md),
правила, которые агент обязан соблюдать, — `.claude/rules/`.

---

## 1. Куда идти со своей задачей

| Задача | Команда |
|---|---|
| Есть текстовый кейс на REST/Kafka/DB/gRPC → нужен тест | `/stand-test-generate-java-test` |
| Есть кейс, который проходит по экрану → нужен UI-тест | `/stand-test-generate-ui-test` |
| Первый день, база знаний пуста | `/stand-test-bootstrap-kb` |
| Есть OpenAPI / AsyncAPI / proto / DDL | `/stand-test-kb-update` |
| Есть ФС / ТЗ / BRD / DOCX / PDF | `/stand-test-ingest-spec` → `/stand-test-review-kb-candidates` → `/stand-test-apply-kb-candidates` |
| Нужно записать окружение в конфиг SDK | `/stand-test-generate-env` |
| Тест упал | `/stand-test-debug` |
| Проверить, что кит установлен правильно | `/stand-test-kit-doctor` |

Узкие команды — срезы зонтичных: `/stand-test-design` (анализ→дизайн), `/stand-test-java`,
`/stand-test-yaml`, `/stand-test-validate`; для UI — `/stand-test-ui-design`,
`/stand-test-ui-discover`, `/stand-test-ui-java`, `/stand-test-ui-validate`. Вызов узкой команды **не
отменяет** стадии до неё.

---

## 2. Установка (однократно, в consumer-репо)

```bash
# 1. Бандл — строго по манифесту, не копированием каталога
node <sdk-repo>/docs/ai-agent/install.mjs <consumer-repo>            # dry-run: что будет записано
node <sdk-repo>/docs/ai-agent/install.mjs <consumer-repo> --apply

# для opencode: --host opencode, затем поднять opencode.json в корень <consumer-repo>

# 2. Контракт KB — вручную (install.mjs его не везёт)
cd <consumer-repo>
mkdir -p knowledge-base
cp -R <sdk-repo>/docs/ai-agent/knowledge-base/schema knowledge-base/schema

# 3. Проверка установки
node .claude/hooks/stand-guard.mjs doctor
```

`install.mjs` не трогает локально изменённые файлы без `--force` и кладёт рядом `MANIFEST.json`,
по которому `doctor` потом отвечает: какая версия кита стоит, что не доехало, что отредактировано
после установки, все ли хуки подключены.

Коллекции `services/`, `endpoints/`, `kafka/`, `db/`, `grpc/`, `environments/`, `mappings/` создаст
первый `/stand-test-kb-update` или `/stand-test-bootstrap-kb`.

### Что должно быть в проекте до первого прогона

| Что | Где |
|---|---|
| Модули SDK на тестовом classpath | BOM + `junit` (или starter) + адаптеры + `config`/`allure` + `io.qameta.allure:allure-junit5` |
| Реестр окружений | `src/test/resources/stand-test-environments.yml` или `application.yml` → `stand.test.environments.*` |
| База знаний | `<consumer-repo>/knowledge-base/` — **в корне репо**, читает только агент |
| Фикстуры | `src/test/resources/fixtures/...` — их читает SDK в рантайме |

Для JSON-трека дополнительных зависимостей **не нужно**: JSON-Schema-валидатор требовался ради схемы
из удалённого `stand-test-ai-schema`, а документ теперь проверяют `AiScenarioParser` (fail-closed) и
рантайм-валидатор.

### Отличия хостов

| | Claude Code | opencode |
|---|---|---|
| Запуск гарда | `settings.json` | плагин `.opencode/plugin/stand-guard.js` (подхватывается сам) |
| Отказ в записи файла с блокирующей находкой | есть | есть |
| Отказ в опасной bash-команде | есть | есть |
| Чтение результатов прогона из JUnit XML | есть | есть |
| Гейт на завершении сессии | держит сессию | возвращает работу новым ходом; одноразовый `opencode run` может выйти раньше |
| Субагенты (стадии 2, 4, 8, 11) | есть | есть |

Под opencode перед тем, как объявить работу законченной, запустите вручную:
`node .opencode/hooks/stand-guard.mjs stop`.

---

## 3. Первый час: наполнить пустую KB

Пустая KB превращает каждую деталь контракта в блокирующий вопрос. Стартовое наполнение — из того,
что в проекте уже есть:

```text
/stand-test-bootstrap-kb
--from-registry              # алиасы из реестра окружений (high confidence)
--from-tests src/test/java   # эндпоинты/топики/таблицы из существующих SDK-тестов (medium, с file:line)
mode: dry-run                # потом apply
```

Пишет только кандидатов; в curated KB они попадут через обычный гейт review → apply.

---

## 4. Протокольная ветка: текстовый кейс → Java-тест

```text
/stand-test-generate-java-test
target module: <ваш тестовый модуль>
target package: ru.<org>.tks.stand
environment: ift
mode: draft                  # draft — предложить в ответе; apply — записать файлы после гейтов

Кейс:
Проверить подключение подписки клиенту.
Предусловие: клиент существует в Таксе.
Действие: POST /rest c clientCode, packageCode и телом Connect (packagePeriod = 1M).
Ожидания: HTTP 201; в ответе code = <packageCode>; connection.stateCode заполнен.
```

Стадии, ни одна не пропускается:

| № | Стадия | Результат |
|---|---|---|
| 1 | анализ кейса | цель, предусловия, триггер, ожидания, недостающее |
| 2 | kb-lookup | контракты резолвятся в записи KB — или становятся `missing` |
| 3 | блокирующие вопросы | **остановка, вопрос человеку** |
| 4 | environment mapping | алиасы, correlation/auth/write-allowed, нужные env-переменные |
| 5 | scenario design | шаги, captures, ассерты, ожидания, cleanup, выбор трека |
| 6 | авторинг | Java DSL (по умолчанию) или AI-формат |
| 7 | фикстуры | для каждого body/payload/request |
| 8 | safety review | **гейт** — любой BLOCK → перегенерация |
| 9 | компиляция | `./gradlew compileTestJava checkstyleTest` |
| 10 | прогон | skip-gate всегда; реальный прогон — только против настроенного стенда |
| 11 | quality review | отчёт готовности → **решение человека** |

**Где агент остановится и спросит:** конкретные значения данных стенда (`clientCode`, `packageCode`),
способ проверки предусловия, значения обязательных заголовков, отсутствующий алиас, права на запись
в БД, стратегия корреляции. Чего нет в KB — вернётся как `missing`, а не будет выдумано.

**Как писать кейс, чтобы вопросов не было:**
[`example-test-case-specification.md`](example-test-case-specification.md) — точные ожидаемые
значения, SLA у каждого ожидания, явный вердикт по данным и очистке. Обратный пример (сырой кейс, на
котором видно, что именно переспрашивает анализ) —
[`example-text-case.md`](.claude/skills/stand-test-case-analysis/example-text-case.md).

**Тест генерируется с `@EnabledIfEnvironmentVariable`** — без env-переменных стенда он пропускается,
а не падает.

---

## 5. Наполнение базы знаний

### 5.1 Машинный контракт: OpenAPI / AsyncAPI / proto / SQL

```text
/stand-test-kb-update
source: specs/tks-client-pckg-v10.yaml     # путь к файлу или вставленный текст спеки
sourceType: openapi                        # openapi | asyncapi | proto | sql | markdown | application-yml | auto
mode: dry-run                              # потом apply
```

Деривация механическая:

| Что | Правило | Пример |
|---|---|---|
| id сервиса | `info.title`, точки → дефисы | `tks.client.pckg` → `tks-client-pckg` |
| id эндпоинта | `operationId`; если он совпадает с HTTP-методом — `<method>-<path-slug>` | `get-rest`, `post-rest` |
| base URL | в KB попадает только **имя** env-переменной | `TKS_CLIENT_PCKG_BASE_URL` |
| схемы тел | `components.schemas` → `knowledge-base/schemas/rest/<service>/*.schema.json` | `schemaRef` — путь от корня KB |
| фикстуры | `fixtureTemplate` — **classpath**-путь в `src/test/resources` | их читает SDK, а не агент |
| `domain`/`tags` | из сегментов `info.title` | domain `tks`, tags `tks`/`client`/`pckg` |
| имя файла | строго `<collection>/<service-id>.yml` | адресное чтение большой KB |

Пример записи после apply:

```yaml
# services/tks-client-pckg.yml
services:
  - id: tks-client-pckg
    name: tks.client.pckg
    domain: tks
    environments: [ift]
    endpoints: [get-rest, post-rest]
    tags: [tks, client, pckg]

# endpoints/tks-client-pckg.yml
endpoints:
  - id: post-rest
    serviceId: tks-client-pckg
    method: POST
    path: /rest
    requiredHeaders: [X-External-System-Code, X-External-User-Code]
    query:
      required: [clientCode, packageCode]
    request:
      contentType: application/json
      schemaRef: schemas/rest/tks-client-pckg/connect.schema.json
      fixtureTemplate: fixtures/tks-client-pckg/connect.json
      requiredFields: [clientCode, packageCode, packagePeriod]
    response:
      status: { success: 201 }
      schemaRef: schemas/rest/tks-client-pckg/connected-package.schema.json
    allowedEnvironments: [ift]

# environments/ift.yml
environments:
  - id: ift
    services:
      - serviceId: tks-client-pckg
        baseUrlRef: TKS_CLIENT_PCKG_BASE_URL
```

Dry-run-отчёт вернёт вопросы, которые агент не решает сам: два success-кода на одну операцию,
отсутствующий в спеке correlation-заголовок (без него `injectCorrelationId()` недоступен),
подтверждение имени env-переменной. Ответьте — и повторите с `mode: apply`.

**Переименовать id можно только до apply** — после на них ссылаются mappings и тесты.
Ошибки 400/422/500 из спеки в KB не попадают: негативные ожидания описывает кейс.

### 5.2 Неструктурированный документ: ФС / ТЗ / BRD / DOCX / PDF

Три команды, staging-слой между документом и curated KB:

```text
/stand-test-ingest-spec
source: docs/source/ФС ПК ПАКТ.Льготы.2025.04.17.docx
documentType: docx           # auto | docx | pdf | markdown | txt | html
domain: pakt
mode: create-candidates      # dry-run по умолчанию — только отчёт

/stand-test-review-kb-candidates
document-id: fs-pk-pakt-lgoty-2025-04-17

/stand-test-apply-kb-candidates
document-id: fs-pk-pakt-lgoty-2025-04-17
mode: dry-run                # потом --apply
```

Что появится в `knowledge-base/candidates/<document-id>/`:

| Файл | Содержимое |
|---|---|
| `_source/` | **gitignored**: исходник + `normalized.md` + `manifest.json` |
| `source-document.yml` | провенанс-корень: id, имя, версия, sha256, systems |
| `services` / `endpoints` / `kafka-topics` / `db` `.candidates.yml` | кандидаты контрактов |
| `business-flows` / `business-rules` / `test-scenarios` / `glossary` `.candidates.yml` | семантика домена |
| `unresolved.candidates.yml` | **гэпы**: чего в документе нет |
| `conflicts.candidates.yml` | расхождения candidate ↔ curated и candidate ↔ candidate |
| `review-decisions.yml` | решения человека |
| `extraction-report.md` | поверхность ревью |

Правила, определяющие, что вы увидите:

- **у каждого кандидата есть `source` (documentId + quote + page/section) и `confidence`**
  (`high`/`medium`/`low`);
- **чего в документе нет — уходит в `unresolved`, а не додумывается.** Документ задаёт success-код,
  но не задаёт метод и path → `missing-http-method` / `missing-endpoint-path` с
  `blockingFor: [endpoint]`, а не выдуманный `POST /process`;
- **промоутится только** `review.decision: approved` + `confidence: high` (или проголосованный
  `medium`) + отсутствие конфликта. `low`, `partial`, `promotionBlocked` — не промоутятся;
- **apply никогда не удаляет, не переименовывает id и не перезаписывает ручное поле.**

Ревью классифицирует каждого кандидата: **apply-eligible** / **needs-tick** (нужна явная галочка) /
**blocked**. Готовый пример прогона лежит в репозитории SDK:
[`knowledge-base/candidates/fs-pk-pakt-lgoty-2025-04-17/`](knowledge-base/candidates/fs-pk-pakt-lgoty-2025-04-17/)
— из него промоутнуты только 3 service-идентичности, эндпоинты и топики остались в `unresolved`,
потому что документ не задаёт их контракт.

### 5.3 Пришла новая версия спеки

Тот же `/stand-test-kb-update` с новым файлом. Диф разложится на:

| Категория | Что происходит |
|---|---|
| `unchanged` | ничего |
| `updated` | перезаписываются только source-derived значения |
| `conflicts` | ручная правка разошлась со спекой — решает человек |
| `removed-candidates` | **только отчёт** — из KB ничего не удаляется автоматически |

Id и алиасы не переименовываются никогда.

Когда один endpoint описан разными источниками, действует **ранг**:

```
openapi / asyncapi / proto / sql  (1)  >  application-yml  (2)  >  markdown  (3)  >  пересказ текстом  (4)
```

Поле, записанное источником ранга N, обновляется только источником того же или более сильного ранга;
более слабый, расходящийся с сильным, даёт `conflict`. Ручные правки стоят выше ранга 1. Ранг
фиксируется в отчёте («Derived from»).

### 5.4 Массовая загрузка

- Каждая спека — **отдельный** прогон со своим дифом и своим одобрением.
- Порядок: сначала машинные контракты, затем проза — так проза только дополняет, а расхождения сразу
  лягут в `conflicts`.
- Из markdown/вики извлекается **только явно заявленное**: дока без точного пути даст кандидата с
  вопросом, а не додуманный path.
- Корпус >10 сервисов — обязательны конвенции из
  [`knowledge-base/README.md`](knowledge-base/README.md) §Conventions for large KBs: файл на сервис,
  `domain` + `tags` в каждом service-entry, `mappings/` хранится в репо.

---

## 6. KB → конфигурация окружения

```text
/stand-test-generate-env
environment: ift
target: src/test/resources/application-test.yml   # или stand-test-environments.yml для plain JUnit
include modules: rest,db                          # опционально
mode: dry-run                                     # потом apply
```

Diff затрагивает **только** поддерево `stand.test.environments.<env>` (или `environments.<env>` в
файловом формате); всё постороннее не трогается.

```yaml
stand:
  test:
    environments:
      ift:
        services:
          tks-client-pckg:
            base-url-ref: TKS_CLIENT_PCKG_BASE_URL
```

Перед реальным прогоном экспортируйте переменные — их значения живут только в окружении:

```bash
export TKS_CLIENT_PCKG_BASE_URL="http://<стенд>:19080/tks/client/pckg/v10"
```

`*-ref` — **голое имя** переменной. Плейсхолдер `${...}` внутри `*-ref` запрещён: Spring раскроет его
до того, как SDK увидит ссылку, и результат будет прочитан как имя переменной.

---

## 7. UI-ветка: кейс на экране

Отдельная ветка нужна из-за одной асимметрии: REST-контракт можно прочитать в спеке, а `data-testid`
существует только в DOM работающего приложения. Отсюда порядок источников в три уровня:

> **база знаний → живой DEV/IFT-стенд → вопрос человеку.**
> «Нет в KB» — повод пойти и посмотреть, а не повод придумать и не повод сразу спросить.

```text
/stand-test-generate-ui-test
target module: <ваш тестовый модуль>
target package: ru.<org>.portal.stand
environment: ift

Кейс: (бланк — .claude/skills/stand-test-ui-case-intake/ui-case-template.md)
Приложение: client-portal, роль client.
Экран «Новая заявка» (/applications/new).
Ввести сумму 100000 и внешний номер, нажать «Подтвердить».
Ожидания: поле «Статус» показывает `Принята` не позже 20 с; появляется номер вида AP-<цифры>;
заявка с этим номером есть в applications-service со статусом ACCEPTED.
```

| № | Стадия | Результат |
|---|---|---|
| 1 | intake | структурная форма кейса, **необратимые** шаги помечены → `UiCase.md` |
| 2 | полнота | **гейт**: `READY` / `BLOCKED` |
| 3 | разведка | живой ift по алиасу под **учёткой разведки**, до необратимых кнопок не доходит → `UiDiscoveryReport.md` |
| 4 | дизайн | шаги, `ui.login` первым с ролью, ограниченные ожидания, связка с бэкендом |
| 5 | Page Objects | класс на экран; локаторы живут **только** там |
| 6 | авторинг | JUnit-тест из фабрик Page Object; трек только Java |
| — | компиляция | `compileTestJava checkstyleTest` по тесту **и** Page Object'ам |
| 7 | safety | **гейт**, отдельный контекст |
| 8 | quality | **гейт**, отдельный контекст, против исходного кейса |
| 9 | отчёт | восемь разделов + снимок исходной выдачи → `ui-generation/<scenario-id>/` |

### Что нужно настроить до первого UI-прогона

В реестре окружений — секция `ui-applications` с алиасом приложения:

```yaml
version: 5
environments:
  ift:
    ui-applications:
      client-portal:
        base-url-ref: CLIENT_PORTAL_IFT_URL
        auth:
          scheme: FORM                                   # NONE | FORM | STORAGE_STATE | SSO
          credentials-pool-ref: CLIENT_PORTAL_TEST_USERS # реестр учёток по ролям
          roles: [client, operator]                      # объявлены роли ⇒ ui.login обязан назвать одну
          discovery-account-ref: CLIENT_PORTAL_DISCOVERY # ограниченная учётка разведки
          challenge: none                                # none | mfa | otp | captcha
          login:
            path: /login
            username-locator: testId=login-username
            password-locator: testId=login-password
            submit-locator: "role=button:Sign in"
            signed-in-locator: testId=user-menu
```

- **Приложению с одной учёткой** реестр не нужен: с версии формата 5 пара
  `credentials-username`/`credentials-password` несёт **значения**, а
  `credentials-username-ref`/`credentials-password-ref` — имена переменных. Паролю дефолт не дают
  никогда — хук отвергает такую строку находкой `SECRET_IN_SOURCE`.
- **Без `discovery-account-ref`** всё, что за входом, разведать нельзя — это вопрос к человеку, а не
  повод взять учётку из рабочего пула.
- **Нужен канал автоматизации браузера** (MCP-сервер `playwright` едет в бандле; `mcp_*` стоит в
  `ask`, поэтому каждое действие видно). Без канала разведка блокируется: пустой отчёт разведки —
  правильный исход, выдуманный — нет.

### Где UI-ветка остановится и спросит

Роль потока; ожидаемое значение, которого на экране не видно; необратимость шага, если по подписи не
понять; сущность-предусловие, которую нельзя ни создать в прогоне, ни проверить пробой; отсутствующий
алиас или `discovery-account-ref`; объявленный `challenge` без обработчика — обхода MFA/OTP/CAPTCHA в
SDK нет и не будет.

### Артефакты падения — есть и не заказываются

Упавший `ui.*`-шаг сам прикладывает: скриншот (зоны `asSensitive()` закрашены **до** снимка), логи
консоли, ленту сетевых запросов, а при `trace: on-failure` — Playwright-трейс. Зелёный шаг не
оставляет ничего. Срок жизни — `stand.test.ui.artifacts.retention.days` (7 дней).

### Чего в `stand-test-ui` нет — и агент этого не сгенерирует

Скриншот или трейс **по требованию**; проверки адресной строки, заголовка, консоли; перехват сети;
`select`/`hover`/загрузка файлов/drag-and-drop; вкладки и iframe; визуальные регрессы; `ui.*` в
декларативном AI-формате. Полный список —
[`ui-sdk-surface-checklist.md`](.claude/skills/stand-test-ui-java-authoring/ui-sdk-surface-checklist.md).

### Материалы для команды

- Разбор одного кейса по всем девяти стадиям —
  [`example-ui-test-case-walkthrough.md`](example-ui-test-case-walkthrough.md);
- бланк кейса (13 разделов, ни один не удаляется) —
  [`ui-case-template.md`](.claude/skills/stand-test-ui-case-intake/ui-case-template.md);
  заполненный пример — [`example-ui-case.md`](.claude/skills/stand-test-ui-case-intake/example-ui-case.md).

---

## 8. Упавший тест

```text
/stand-test-debug
```

На вход — вывод теста/стектрейс, логи CI, Allure-результаты, исходник теста, реестр, `ScenarioDesign.md`.
Отчёт классифицирует причину и предлагает фикс. Ключи поиска извлекаются из сообщения:
`correlationId` — по логам сервисов и сообщениям Kafka, `testRunId` — по строкам БД (`test_run_id`) и
id консьюмер-группы.

Правило ветки: **падение не прячется.** Удаление ассерта, `@Disabled` без номера задачи, новый `catch`
и раздутый таймаут отвергаются хуком в момент правки.

---

## 9. Инструменты проверки (работают без модели)

```bash
node .claude/hooks/stand-guard.mjs doctor          # версия кита, что не доехало, что правили, хуки
node .claude/hooks/stand-guard.mjs scan <путь>     # те же находки, что и при записи
node .claude/hooks/stand-guard.mjs scan --format sarif --exit-code   # в CI: аннотации к MR
node .claude/hooks/stand-guard.mjs kb-status       # путь реестра, что уже в KB, каких алиасов нет
node .claude/hooks/stand-guard.mjs kb-validate     # валидация KB по схемам
node .claude/hooks/stand-guard.mjs alias-check     # алиасы реестра ⇄ KB
node .claude/hooks/stand-guard.mjs kpi-locators src/test/java          # доля хрупких локаторов
node .claude/hooks/stand-guard.mjs kpi-locators src/test/java --json
```

`kpi-locators` считает статически по Page Object'ам долю локаторов не по `data-testid` и печатает
`файл:строка` каждого хрупкого — это готовая заявка продуктовой команде на `data-testid`. Прогон
никогда не блокирует; пустой каталог даёт «знаменатель 0», а не 0%.

Пачка кейсов разом:

```bash
node .claude/hooks/stand-batch.mjs cases/ --dry-run
node .claude/hooks/stand-batch.mjs cases/
```

Одна headless-сессия на кейс, последовательно (состояние гейтов — одно на проект, параллельные сессии
затёрли бы друг друга). `NEEDS-HUMAN` в отчёте — корректный исход: в headless-прогоне некому отвечать
на блокирующие вопросы стадии 3.

---

## 10. Что хук отвергает автоматически

| Когда | Что |
|---|---|
| перед каждой записью | файл сканируется по `detectors.json` (26 находок: 18 протокольных + 8 UI); любая блокирующая — **запись отвергается** |
| там же | удалённая ассерция, `@Disabled` без задачи, новый `catch`, выросший таймаут |
| запись в `.claude/**` | запрещена всегда — кит обновляется переустановкой по манифесту |
| запись в `knowledge-base/schema/**` | запрещена всегда |
| запись в курируемые коллекции KB | только внутри пермита: `kb-write-permit --reason promote\|update\|repair <файлы>` |
| перед bash-командой | `rm -rf`, креденшелы в командной строке, прямой DML, `git push`, **запись файла шеллом** (`cat >`, `tee`, `sed -i`, `cp` в дерево) |
| после `./gradlew test` | читаются `TEST-*.xml`: `skipped=N` при `tests=N` — предупреждение, что стенд не был затронут |
| при завершении сессии | не даёт закончить исполняемый артефакт без пройденного safety-review и тест без записи в `knowledge-base/mappings/` |

Над прозой (`.md`, `.txt`, `.adoc`) не работают находки про доставку (адрес, SQL, ожидание, транспорт);
находки про раскрытие (секрет, ПД) работают везде.

**Чистый прогон хука ≠ чистое ревью.** В UI-ветке глазами проверяются U4 (семантическая половина),
U8, U10, U11a/b, U12, U14, U15, U18, U19 — перечень в
`ui-safety-checklist.md`.

---

## 11. Жёсткие правила, которые нельзя обойти

- **Только логические алиасы** — никаких URL, хостов, портов, JDBC-строк в тестах и сценариях.
- **Никаких секретов** — авторизация только из реестра через `*-ref`.
- **Никакого production** ни в одном тестовом реестре.
- **Никакого `Thread.sleep`**, Awaitility и ручного поллинга — только `*.expectEventually` с
  ограниченным таймаутом.
- **`testRunId`/`correlationId` принадлежат SDK** — не выдумываются и не хардкодятся.
- **Ничего не выдумывается**: нет записи в KB → `missing` → вопрос человеку.
- **Гейты не совещательные**: BLOCK на safety-review означает перегенерацию, а не объяснение, почему
  здесь можно.
- **Мерж делает человек.**

Полный текст — `.claude/rules/stand-test-guardrails.md` (протокол),
`.claude/rules/stand-test-ui-guardrails.md` (UI), `.claude/rules/stand-test-pipeline.md` (порядок стадий).

---

## 12. Ограничения текущей версии

- **Предварительной проверки декларативного документа JSON Schema больше нет.** Модуль
  `stand-test-ai-schema` удалён 12.08.2026; нарушение ловится при разборе (`AiScenarioParser`,
  fail-closed) и рантайм-валидатором — после загрузки, а не до неё. Ассеты кита, ссылающиеся на
  ресурсы этого модуля (`/stand-test-yaml`, схемная часть `/stand-test-validate`), указывают на то,
  чего нет.
- **KB валидируется на стороне потребителя** командой `kb-validate` кита: тесты, которые проверяли
  контракт в SDK-репо, ушли вместе с модулем.
- **Ничто машинно не сверяет кит с SDK.** Ассет, заявляющий изменившуюся возможность SDK, будет
  просто неверным и останется зелёным.
- **`ui.*` — только Java-трек**, декларативного формата у него нет.
- **gRPC — только unary**; ассерты Kafka и DB — только на равенство.
