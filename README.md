# stand-test-sdk

Внутренний Java **test SDK** для написания интеграционных/e2e-автотестов против **реальных стендов
DEV/IFT**. Это тонкий фасад над зрелыми инструментами (WebClient, `kafka-clients`, JDBC, gRPC, JUnit 5,
Allure), который даёт каждой команде один согласованный способ описать сценарий, дождаться асинхронных
эффектов (без `Thread.sleep`), скоррелировать вызовы (`scenarioId`/`testRunId`/`correlationId` принадлежат
SDK) и отчитаться о результатах — плюс ограниченный декларативный формат, безопасный для AI-генерации
тестов.

Оба входных DSL сходятся в одну immutable-модель; исполняется только она:

```
Java DSL (ленивый билдер) ─┐
                           ├─▶ Scenario Model ─▶ ScenarioValidator ─▶ ScenarioRunner ─▶ StepExecutor SPI ─▶ адаптеры ─▶ реальный стенд DEV/IFT
YAML DSL ──────────────────┘
```

Архитектурный источник истины — [docs/arch/stand-test-sdk-implementation-plan.md](docs/arch/stand-test-sdk-implementation-plan.md).
Компактный обзор устройства библиотеки для контрибьюторов (с диаграммами) —
[docs/arch/architecture-overview.md](docs/arch/architecture-overview.md).

## Модули

| Модуль | Что это |
|--------|---------|
| [stand-test-core](stand-test-core/README.md) | Модель сценария, SPI, валидация, guardrails, модели результатов/событий — только JDK, без зависимостей на адаптеры |
| [stand-test-await](stand-test-await/README.md) | Единый механизм ожидания (детерминированный поллинг, инъецируемый источник времени) |
| [stand-test-junit](stand-test-junit/README.md) | JUnit 5 extension `@StandTest` — инжектит `StandClient`, собранный через `ServiceLoader` |
| [stand-test-rest](stand-test-rest/README.md) | REST-шаги (`RestStep`), инъекция correlation-заголовка, JSON-ассерты и captures |
| [stand-test-kafka](stand-test-kafka/README.md) | Kafka-шаги (`kafka.send`/`kafka.expect`), корреляция по заголовку, ограниченные клиенты |
| [stand-test-db](stand-test-db/README.md) | DB-шаги проверки/ассертов с fail-closed SQL write-guard |
| [stand-test-grpc](stand-test-grpc/README.md) | gRPC unary-шаги через server reflection + `DynamicMessage`, обязательный deadline, полный набор матчеров |
| [stand-test-ui](stand-test-ui/README.md) | UI-шаги (`ui.open`/`click`/`fill`/`expect`/`expectEventually`/`login`) на Playwright: приложение только по алиасу, свой `BrowserContext` на прогон, пул техучёток по ролям |
| [stand-test-allure](stand-test-allure/README.md) | Маппит reporting-события SDK в Allure (шаги, labels, параметры, вложения) |
| [stand-test-config](stand-test-config/README.md) | Файловый `EnvironmentRegistry` (`stand-test-environments.yml`) — SPI-провайдер для plain JUnit |
| [stand-test-spring-boot-starter](stand-test-spring-boot-starter/README.md) | Auto-configuration для Boot 3: `@Autowired StandClient`, окружения из `application.yml` |
| [stand-test-scenario-yaml](stand-test-scenario-yaml/README.md) | YAML DSL (поверхности given/then и AI steps/type) над той же моделью |
| [stand-test-ai-schema](stand-test-ai-schema/README.md) | JSON Schema + правила генерации для безопасных AI-сценариев |
| [stand-test-bom](stand-test-bom/README.md) | BOM (`java-platform`) — выравнивание версий для потребителей |
| [stand-test-example](stand-test-example/README.md) | Test-only витрина на offline-двойниках (не публикуется) — живой quick start |

## Быстрый старт (plain JUnit, без Spring)

**1. Добавьте зависимости** (все модули получают одну версию через BOM):

```kotlin
testImplementation(platform("ru.alfa.stand.test:stand-test-bom:<version>"))
testImplementation("ru.alfa.stand.test:stand-test-junit")
testImplementation("ru.alfa.stand.test:stand-test-rest")    // + -kafka / -db / -grpc по необходимости
testImplementation("ru.alfa.stand.test:stand-test-config")  // файловый реестр окружений
testImplementation("ru.alfa.stand.test:stand-test-allure")  // опционально: отчётность в Allure
// если берёте stand-test-allure — добавьте и интеграцию с JUnit 5: SDK тянет только allure-java-commons,
// и без неё жизненному циклу некуда складывать шаги (отчёт выйдет пустым, без единого сообщения):
testImplementation("io.qameta.allure:allure-junit5:2.29.1")
```

**2. Опишите стенд** в `src/test/resources/stand-test-environments.yml`. Каждый `*-ref` — это **имя
переменной окружения, никогда не значение**: endpoints и секреты не попадают в исходники:

```yaml
version: 2                                       # версия ФОРМАТА файла (не версия SDK); можно опустить — тогда 1
environments:
  ift:
    services:
      client-service:
        base-url-ref: CLIENT_SERVICE_URL
        correlation: { source: HEADER, name: X-Correlation-Id }
        # опциональная авторизация сервиса — только ссылки; заголовок Authorization SDK проставит сам:
        # auth: { scheme: BASIC, username-ref: CLIENT_USER, password-ref: CLIENT_PASSWORD }
    datasources:
      main-db:
        url-ref: MAIN_DB_URL
        user-ref: MAIN_DB_USER
        password-ref: MAIN_DB_PASSWORD
        allowed-schemas: [test_data]
        write-allowed: true
    ui-applications:                             # требует version: 2 (секция auth.login — version: 3)
      client-portal:
        base-url-ref: CLIENT_PORTAL_IFT_URL      # имя переменной окружения, не URL
        default-viewport: desktop                # профиль прогона — конфигурацией, не полем сценария
        viewport-profiles:
          desktop: { width: 1440, height: 900 }
          mobile:  { width: 390, height: 844 }
        trace: off                               # off (умолчание) | on-failure
        auth:
          scheme: FORM                           # NONE | FORM | STORAGE_STATE | SSO (SSO — говорящий отказ)
          credentials-pool-ref: CLIENT_PORTAL_TEST_USERS   # переменная с РЕЕСТРОМ учёток, не с учёткой
          roles: [client, operator]              # объявлены роли ⇒ ui.login обязан назвать одну из них
          discovery-account-ref: CLIENT_PORTAL_DISCOVERY   # учётка разведки, вне пула (SEC-10)
          challenge: none                        # none | mfa | otp | captcha — объявляется, не обходится
          login:                                 # локаторы формы входа: <стратегия>=<значение>
            path: /login
            username-locator: testId=login-username
            password-locator: testId=login-password
            submit-locator: "role=button:Sign in"
            signed-in-locator: testId=user-menu   # элемент, который есть только после входа
```

Переменная `CLIENT_PORTAL_TEST_USERS` содержит **реестр учёток**, а не учётку:
`portal-client-1:client;portal-client-2:client;portal-manager-1:manager`. Каждая запись — это
`<id>:<роль>` (имена переменных с логином и паролем выводятся из id: `PORTAL_CLIENT_1_USERNAME` /
`PORTAL_CLIENT_1_PASSWORD`) либо `<id>:<роль>:<переменная-логина>:<переменная-пароля>`. Ни логина, ни
пароля в конфигурации нет ни на одном уровне.

### Версия формата реестра

Корневой ключ `version` — это версия **формата файла**, а не версия SDK. Правила одинаковы для файла и
для Spring-поверхности (`stand.test.version`), потому что обе читают одну константу в
`stand-test-core` (`EnvironmentConfigFormat`):

| Что в конфигурации | Поведение SDK |
|---|---|
| ключ отсутствует | читается как `1` — файлы, написанные до версионирования, работают без изменений; выводится `WARN` (см. ниже) |
| `version` < поддерживаемой | читается, плюс один `WARN` при загрузке |
| `version` = поддерживаемой | читается молча |
| `version` > поддерживаемой | отказ с сообщением о **версии формата**: указывает версию файла, поддерживаемую версию и что сделать (обновить `stand-test-*`) — вместо `Unknown field` |
| не целое число или ≤ 0 | ошибка конфигурации (fail-closed) |

**Окна совместимости нет** (ADR-UI-004): SDK читает **каждую** версию формата от `1` до
поддерживаемой и не получает права перестать это делать. Отставший файл продолжает работать
неограниченно долго — `WARN` сообщает о факте отставания и прямо говорит, что файл остаётся читаемым,
а не предупреждает о будущем отказе. Отказ бывает ровно один и в другую сторону: файл **новее**, чем
понимает SDK. Одно предупреждение на загрузку документа, а не на обращение к алиасу.

Секции, появившиеся после версии 1, требуют явного объявления версии: `ui-applications` — это
`version: 2`, а `auth.login`/`auth.challenge` внутри неё — `version: 3` (поле, добавленное в секцию,
считается секцией для этого правила). Смысл в том, что более старый SDK, встретив такой файл, скажет
«файл версии 3, поддерживается 2 — обновите SDK», а не «неизвестный ключ `login`».

**UI-приложения** адресуются логическим алиасом ровно так же, как сервисы и топики: сценарий называет
`client-portal`, реестр — единственное место, где алиас превращается в адрес, произвольный URL в шаге
указать негде. Неизвестный алиас отвергается валидатором **до** запуска шага
(`NON_WHITELISTED_UI_APPLICATION`). Сами `ui.*`-шаги поставляет модуль
[stand-test-ui](stand-test-ui/README.md): `open`/`click`/`fill`/`expect`/`expectEventually` плюс
`login` — вход техучёткой, выданной **по роли** из пула, с ограниченным ожиданием свободной учётки и
переиспользованием сессии браузера. Упавший шаг оставляет артефакты: скриншот с заклеенными
чувствительными зонами, консоль и сетевую историю текстом, а там, где приложение объявило в реестре
`trace: on-failure`, — ещё и Playwright-трейс; всё это подчиняется сроку жизни
`stand.test.ui.artifacts.retention.days` (по умолчанию 7 дней). Подробности и границы —
[stand-test-ui/README.md](stand-test-ui/README.md).

**3. Напишите первый тест.** `@StandTest` инжектит `StandClient`, собранный из адаптеров, найденных на
classpath — никакого кода проводки:

```java
@StandTest
@StandEnv("ift")
@StandScenarioId("payment-flow")
class PaymentFlowTest {

    @Test
    void createsRequest(StandClient stand, @StandScenarioId String id, @StandEnv String env) {
        Scenario scenario = Scenario.builder(id)
                .environment(env)
                .step(RestStep.post("client-service", "/api/requests")
                        .body("{\"amount\":1}")
                        .injectCorrelationId()
                        .expectStatus(200)
                        .capture("requestId", "$.requestId")
                        .build())
                .step(DbStep.expectEventually("main-db")
                        .sql("SELECT status FROM test_data.orders WHERE id = :id")
                        .param("id", "${requestId}")
                        .expectValue("NEW")
                        .withinSeconds(30)
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);   // Validator -> Runner -> StepExecutor SPI

        assertThat(result.isSuccessful()).isTrue();
    }
}
```

Несошедшиеся ассерты приходят как `StandTestAssertionError` (это `AssertionError` — нативное падение для
JUnit); проблемы инфраструктуры/конфигурации — как `StandTestException`. Запускайте тест с выставленными
переменными окружения, на которые ссылается конфиг (`CLIENT_SERVICE_URL`, `MAIN_DB_URL`, …).

## Быстрый старт (Spring Boot)

Добавьте `stand-test-spring-boot-starter` плюс нужные адаптеры, объявите окружения под
`stand.test.environments.*` в `application.yml` и получите `@Autowired StandClient` — полный пример
`application.yml`, правила переопределения бинов и troubleshooting смотрите в
[README стартера](stand-test-spring-boot-starter/README.md). `stand.test.enabled: false` выключает всю
auto-configuration целиком.

Value-поля также принимают настоящие Spring-плейсхолдеры через value-twins
(`base-url: ${CLIENT_SERVICE_URL:}` вместо `base-url-ref: CLIENT_SERVICE_URL`), включая секретные
креденшелы (`password: ${TKS_PASSWORD:pwddev3}`). Но у секретного twin'а есть цена: раскрытое значение
материализуется в Spring Environment, а default остаётся в файле — поэтому для секретов предпочтительно
голое написание `password-ref: TKS_PASSWORD`. **Никогда не кладите плейсхолдер `${...}` внутрь `*-ref`-поля**:
Spring схлопнет его, и ref будет прочитан как имя переменной (ловушка двойного резолва). См. раздел
«Endpoint values via Spring placeholders» в README стартера.

## AI-генерируемые сценарии

`stand-test-ai-schema` поставляет JSON Schema и
[правила генерации](stand-test-ai-schema/src/main/resources/ai/stand-test-ai-generation-rules.md), которые
нужны LLM, чтобы производить безопасные декларативные сценарии; `stand-test-scenario-yaml` парсит этот
формат в ту же валидированную модель. Guardrails (только whitelisted-окружения, никаких сырых URL, никаких
инлайн-секретов, никакого деструктивного SQL, ограниченные таймауты) выводятся из единственного источника
истины `ForbiddenOperation` и переenforce'атся в рантайме валидатором.

### Кит для AI-агента

Чтобы превратить бизнес-кейс в виде обычного текста в полноценный безопасный Java-автотест силами
AI-агента (Claude Code), см. разворачиваемый бандл в [`docs/ai-agent/`](docs/ai-agent/README.md): копируемый
набор `.claude/` со скиллами и слэш-командами (анализ кейса → поиск в базе знаний → маппинг окружения →
дизайн сценария → авторинг Java/YAML → фикстуры → safety review → review теста → отладка),
**контракт базы знаний** ([`docs/ai-agent/knowledge-base/`](docs/ai-agent/knowledge-base/README.md) —
строгие JSON-схемы + разобранные примеры для сервисов/эндпоинтов/топиков/датасорсов/gRPC-таргетов/окружений)
и пайплайн загрузки спецификаций (неструктурированная спека → schema-valid KB-кандидаты → review → apply).
Начните с [`docs/ai-agent/README.md`](docs/ai-agent/README.md) (установка бандла) и разобранного примера
на русском в [`docs/ai-agent/usage-guide.md`](docs/ai-agent/usage-guide.md).

## Параллельное выполнение

SDK построен так, чтобы гонять сценарии **параллельно в одной JVM** без флаки: каждый прогон получает
уникальные `testRunId` и `correlationId`, собственный `VariableStore` и уникальную Kafka consumer group;
единственный `DefaultScenarioRunner` держит всё per-run состояние thread-confined, поэтому один
закэшированный `StandClient` безопасно разделяется тестовыми потоками (план §15).

**Включается** размещением `junit-platform.properties` в корне `src/test/resources`:

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.classes.default=concurrent   # классы гоняются параллельно
junit.jupiter.execution.parallel.mode.default=same_thread          # методы внутри класса — последовательно
junit.jupiter.execution.parallel.config.strategy=dynamic
junit.jupiter.execution.parallel.config.dynamic.factor=0.5
```

Это рекомендуемая модель: **классы параллельно, методы последовательно** — она соответствует инварианту SDK
*один прогон сценария = один поток* (параллелить сценарии/классы, но никогда шаги одного сценария). Модель
работает **только внутри одной JVM**: держите Gradle `maxParallelForks=1`, поскольку отдельные JVM гоняли бы
наперегонки фиксированные порты и общее внешнее состояние. `stand-test-example` поставляется ровно с такой
конфигурацией как референс.

**Что параллельно-безопасно** — сценарий безопасен by construction, когда опирается на per-run изоляцию:
- **DB:** INSERT в `db.seed` обязан помечать свои строки зарезервированным биндом `:testRunId` **и объявлять
  колонку-тег** через `taggedByTestRunId("test_run_id")` — ту же колонку, по которой фильтрует его
  `db.cleanup` через `whereTestRunId("test_run_id")`. Write-guard **проверяет, что объявленная колонка
  действительно присутствует в списке колонок INSERT** (а не просто что `:testRunId` где-то упомянут), поэтому
  seed, помечающий неубираемую колонку, отвергается до любого IO; выводите любой фиксированный первичный ключ
  из per-run значения (`id = "order-${testRunId}"`). `db.cleanup` обязан скоупить свой DELETE через
  `whereTestRunId(...)`.
- **Kafka:** `kafka.expect` обязан выбирать по per-run **уникальному** дискриминатору —
  `correlationIdFromContext()` (уникальный id, принадлежащий SDK) или `key(...)`, выведенный из per-run
  значения (**содержит плейсхолдер `${...}`**, например `${testRunId}`). Enforce'ится fail-closed и на этапе
  сборки, и в рантайме (перед вооружением consumer'а): expect без дискриминатора либо **константный** ключ,
  на который в общем топике матчились бы оба параллельных прогона, отвергается. Константный ключ допустим
  только вместе с `correlationIdFromContext()`, где он лишь сужает выборку среди собственных
  скоррелированных сообщений прогона.
- **REST/gRPC:** SDK инжектит per-run `correlationId` в каждый вызов; скоупьте любую создаваемую на стороне
  сервера сущность через `${testRunId}`/`${correlationId}` в теле запроса или фикстуре.

**Как пометить тест последовательным** — для редкого теста, который нельзя изолировать через `testRunId`
(фиксированный порт, общий файл, process-wide синглтон), откажитесь от параллельности мета-аннотациями из
`stand-test-junit` — тонкими фасадами над JUnit'овыми:

| Аннотация | Во что разворачивается | Для чего |
|---|---|---|
| `@StandParallelSafe` | `@Execution(CONCURRENT)` | явная пометка «безопасно гонять параллельно» |
| `@StandSerial` | `@Execution(SAME_THREAD)` | сериализовать методы одного класса |
| `@StandIsolated` | `@Isolated` | гонять класс в одиночку (ничего параллельно) |

Для взаимного исключения только между тестами, делящими один именованный ресурс (например, два класса
занимают один порт), используйте нативный JUnit'овый `@ResourceLock("<alias>")` напрямую.

**UI-набор — та же модель и два дополнительных правила.** Браузерный прогон изолирован
`BrowserContext`'ом (свои куки, storage, кэш), а сессия и аренда учётки живут в `ResourceScope` прогона,
поэтому `ui.*` параллелится как всё остальное. Но:

- **потолок параллельности UI-набора равен размеру пула техучёток.** Выше потолка прогоны ждут
  свободную учётку — но ждут **ограниченно**: при параллельности `P`, пуле `N` и длительности сценария
  `T` последний в очереди простаивает ≈ `T × (P/N − 1)`, и если это больше `accountTimeout`
  (умолчание 60 с), прогон падает как `BROKEN`. Посчитайте до запуска; диагностика
  `ui.account.waitMillis` показывает фактический простой;
- **только потоки, не форки:** `maxParallelForks = 1`. Пул живёт в процессе, и вторая JVM заведёт
  свой — то есть выдаст ту же учётку второму прогону, ровно вопреки тому, ради чего пул существует;
- поток браузерного набора стоит Chromium плюс процесс драйвера, поэтому число для него задаётся
  отдельно и меньше, чем для обычного. Подробности — в
  [stand-test-ui/README.md](stand-test-ui/README.md#параллельность-набора).

**Риски против реальных стендов DEV/IFT** — общий стенд по определению находится под конкуренцией. Изоляция
данных держится на том, что каждая запись/чтение скоуплены по `testRunId`, а каждый Kafka expect
отфильтрован по корреляции (guardrails выше enforce'ят сторону записи/expect'а; скоупьте и свои чтения).
Рекомендуемый пилот: начните с unit/example и in-memory тестов, затем включите тесты адаптеров, затем
небольшой фактор параллельности против стенда, следя за диагностикой таймаутов, прежде чем поднимать
`dynamic.factor`.

## Логирование

SDK логирует через **SLF4J** и поставляет **только фасад** (`slf4j-api`) — биндинг предоставляете вы
(Logback через Spring Boot, `slf4j-simple` и т. п.) и сами управляете уровнями/форматом, поэтому конфликта
multiple-bindings не возникает.

Каждый прогон штампует correlation-идентификаторы в **MDC**, поэтому их несёт каждая строка лога — и SDK, и
адаптеров, и вашего собственного кода в тестовом потоке во время прогона:

| Ключ MDC | Область | Пример |
|---|---|---|
| `scenarioId` / `testRunId` / `correlationId` / `environment` | весь прогон | `full-framework-example` / uuid / uuid / `ift` |
| `stepId` / `stepType` / `stepIndex` | текущий шаг | `create-request` / `rest.post` / `1` |

Уровни: **INFO** — старт/финиш сценария; **DEBUG** — старт/успех каждого шага (+ длительность) и трейсы
запросов/ответов/ожиданий по адаптерам; **WARN** — несошедшийся ассерт (`FAILED`/`TIMEOUT`) и best-effort
заминки (закрытие ресурса, бросающий reporting-публишер); **ERROR** — инфраструктурный/неожиданный сбой шага
(`BROKEN`). Сбой называет шаг одинаково в логе и в брошенном исключении:
`Step [2/6] 'seed-order' (db.seed) FAILED: <reason>`. DEBUG-трейсы адаптеров содержат **только метаданные** —
никогда тела запросов/ответов, заголовки/`Authorization`, ключи/значения сообщений, текст SQL или связанные
значения, ссылки на секреты.

Готовая к копированию конфигурация для потребителя лежит в
[`stand-test-example/src/test/resources/logback-test.xml`](stand-test-example/src/test/resources/logback-test.xml)
(паттерн с `%X{scenarioId}`/`%X{stepId}`, `ru.alfa.stand.test` на уровне DEBUG). Запустите
`./gradlew :stand-test-example:test`, чтобы увидеть скоррелированный вывод полного сценария REST→DB→gRPC.

## Сборка

```bash
./gradlew build                # компиляция + checkstyle (zero-tolerance) + тесты + гейт JaCoCo 80%
./gradlew publishToMavenLocal  # локальная публикация всех модулей + BOM
```

Toolchain — Java 21, байткод таргетит **Java 17** (`--release 17`) — артефакты грузятся на потребительских
JDK 17/21/24. Публикация во внутренний репозиторий параметризована через свойства `standTestPublish*` /
переменные окружения `STAND_TEST_PUBLISH_*` — см. [docs/publishing.md](docs/publishing.md).

## CI

Пайплайн GitLab [`.gitlab-ci.yml`](.gitlab-ci.yml) прогоняет ту же самую команду, что и разработчик:

| Джоба | Когда | Команда |
|---|---|---|
| `verify` | merge request и push в ветку | `./gradlew build --console=plain` |
| `nightly-verify` | расписание (Settings → CI/CD → Schedules) | `./gradlew build --console=plain --rerun-tasks --no-build-cache` |

Между прогонами переносится **только кеш загруженных зависимостей** (`.gradle-home/caches/modules-2`,
`.gradle-home/wrapper`): build-cache, configuration-cache и каталоги `build/` не кешируются, поэтому
джоба не может отчитаться `UP-TO-DATE` за то, что изменено этим коммитом. Ночной прогон вдобавок
переисполняет всё от холодного состояния. Длительность пишется в артефакт
`ci-metrics/gradle-build-seconds.txt` — вход в KPI-8.

Браузерный набор (`:stand-test-ui:browserTest`) здесь **намеренно не запускается**: ему нужен образ с
Chromium — это отдельная задача (`UITG-S026`). Тест `CiPipelineConfigTest` роняет сборку, если
`browserTest` появится в пайплайне.

**Что нужно настроить один раз** — координаты раннера и доступ к Artifactory не зашиты в файл, это
факты организации, а не репозитория. Переменные проекта (Settings → CI/CD → Variables):

| Переменная | Назначение |
|---|---|
| `STAND_TEST_CI_IMAGE` | образ с JDK 21 (toolchain; авто-провижининга нет — сборка ничего не тянет из интернета) |
| `STAND_TEST_CI_RUNNER_TAG` | тег раннера, которому разрешены эти джобы |
| `ORG_GRADLE_PROJECT_binaryPublicRepoUrl` | публичный прокси-репозиторий (Maven Central + Plugin Portal) |
| `ORG_GRADLE_PROJECT_artifactoryUrl` | базовый URL Artifactory |
| `ORG_GRADLE_PROJECT_artifactorySnapshotRepo` | ключ snapshot-репозитория |
| `ORG_GRADLE_PROJECT_artifactoryUser` | read-пользователь (masked) |
| `ORG_GRADLE_PROJECT_artifactoryPassword` | read-токен (masked + protected) |

`before_script` проверяет их наличие и падает с перечнем недостающих **имён** (значения не печатаются
никогда). Блокировка merge — настройка проекта, а не файла: включите Settings → Merge requests →
«Pipelines must succeed»; со своей стороны пайплайн не помечает ни одну джобу `allow_failure` и даёт
merge request собственный прогон.

## Известные ограничения

- gRPC поддерживает **только unary**-вызовы (server reflection + `DynamicMessage`).
- Ассерты REST и gRPC исполняют все пять матчеров (`equals`/`contains`/`exists`/`notNull`/`matches`);
  `rest.expectEventually` поллит GET'ом, пока ожидания не сойдутся. **Ассерты Kafka — только `equals`**:
  `kafka.expect` сравнивает на равенство и отвергает любой другой матчер.
- Kafka-пример в `stand-test-example` требует реального брокера и помечен тегом `requires-broker`
  (исключён из прогона по умолчанию); все unit-тесты модулей гоняются офлайн.
- Первой публикации во внутренний Nexus/Artifactory ещё не было (URL репозитория в ожидании).
- Сценарии выполняются только против окружений, объявленных в реестре — escape-hatch'а нет by design.

## Куда смотреть дальше

`stand-test-example` — живая, компилирующаяся витрина: композиция REST → DB → gRPC, проброс корреляции,
per-run изоляция переменных, ожидание на фейковом источнике времени, вывод в Allure и семантика сбоев — всё
на offline-двойниках.
