# stand-test-example — implementation plan (Итерация 8)

Самодостаточный план реализации **Итерации 8 (Example tests)** — написан так, чтобы новая сессия без
контекста могла начать. Источник истины контрактов: `stand-test-sdk-implementation-plan.md` — **§7**
(порядок, Итерация 8), **§6** (MVP), **§10** (Public API sketch — черновик), **§16** (testing strategy),
**§18** (DoD), **§20** (анти-правила). Связанный артефакт: `stand-test-allure-implementation-plan.md`
(его **Фаза 3** «проводка + smoke-пример» сливается с этой итерацией).

> **Design-only документ.** Кода нет; сниппеты — draft. Цель — зафиксировать решения, которых сейчас в
> плане нет, чтобы реализация была механической.

> **Статус: Phase 0–1 РЕАЛИЗОВАНЫ** (модуль заведён; 5 REST+DB примеров зелёные офлайн; §10 сверён;
> `./gradlew build` зелёный). Детальные **дальнейшие шаги (Phase 2: `@StandTest`-автопроводка + Kafka, и
> бэклог)** — в `docs/arch/stand-test-example-next-steps.md`.

## Что уже задано планом (verbatim)

- §7: «**Итерация 8 — Example tests.** Только технические примеры использования SDK; **без** бизнес-логики
  реального проекта.»
- §10: черновик `ExampleFlowTest` (REST→Kafka→DB) + `kafka.send` + `db.seed/cleanup` — помечен «draft, НЕ
  реализуется». **Расходится с фактическим API** (см. §«Сверка §10» ниже).
- §16: для тестов **самого SDK** допустимы test doubles (in-memory adapters, embedded broker, controlled
  fake); реальные стенды для unit-тестов не обязательны; coverage-gate ≥ 80 %.
- §18 (code-итерация): build зелёный; coverage ≥ 80 %; README; «технические примеры usage без бизнес-
  логики»; «есть Allure-диагностика (шаги/attachments/метаданные)»; нет `Thread.sleep`/hardcoded URLs/
  secrets/raw-bypass; timeout/negative покрыты; проброс SDK-падений в JUnit покрыт; reporting events покрыты.
- §20: «Не смешивать SDK и тесты конкретного проекта»; «Не хардкодить stand/datasource URLs — только
  whitelisted-алиасы из env-конфига».

## Чего НЕ хватает (и что этот план фиксирует)

1. `stand-test-example` **не определён** в §4 (ответственность), §5 (граф) и в `settings.gradle.kts`.
2. **Execution model** не задана: как примеры проходят `./gradlew build` зелёными **без реального стенда**.
3. **Проводка** (allure-Фаза 3) не специфицирована: как `@StandTest` подключает
   `AllureReportingEventPublisher` и как заполняется `EnvironmentRegistry` (сейчас extension строит раннер
   с **пустым** registry и **NoOp**-publisher → REST/Kafka/DB через `@StandTest` не разрешат алиасы).
4. §10-черновик расходится с отгруженным API (6 точек).
5. Не заданы: конвенция fixtures, трактовка coverage-gate для модуля-примеров, размещение модуля.

---

## Решение 1 — Execution model (ключевое)

Примеры исполняются **через публичный API SDK как чёрный ящик**, против **in-process test doubles**
(они НЕ Testcontainers и допустимы по §16), поэтому `./gradlew build` зелёный офлайн:

| Адаптер | Double | Чёрный ящик через публичный API? |
|---|---|---|
| REST | JDK `com.sun.net.httpserver.HttpServer` (как в `RestStepExecutorHttpTest`) | **Да** — адаптер реально вызывает `localhost:<port>` через WebClient. |
| DB | H2 in-memory (как в db-тестах) | **Да** — адаптер реально подключается по JDBC к H2. |
| Kafka | — | **Нет**: `kafka.expect` поднимает реальный `KafkaConsumer` против кластера; `MockConsumer` живёт **внутри** адаптерных тестов через seam и снаружи не инжектится. Нужен брокер. |

**Как doubles цепляются к адаптерам (env-ref модель, важно).** Адаптеры по дизайну (§9/§20) резолвят
endpoint **из ссылки на env-переменную**, а не из литерального URL в registry:
- REST: `ServiceEndpointDefinition.baseUrlRef` → `BaseUrlResolver` (дефолт `EnvironmentBaseUrlResolver`
  читает `System.getenv`). Есть публичный seam: `new RestStepExecutor(HttpCaller, BaseUrlResolver)`.
- DB: `DatasourceDefinition` (jdbcUrlRef + cred-refs) → `EnvironmentReferenceResolver` (env). **У
  `DbStepExecutor` только public no-arg-конструктор; seam-ctor `(ReferenceResolver, ConnectionFactory,
  Awaiter)` package-private** (`DbStepExecutor.java:88`) — DataSource-инжектирующего ctor нет вовсе,
  публичной точки подмены подключения нет (offline H2 гоняется во внутренних тестах адаптера, не через
  публичный API).
- `InMemoryEnvironmentRegistry` ключуется **по имени окружения → `EnvironmentDefinition`**
  (`{services, topics, datasources, kafkaCluster}`), алиасы — внутри него; в нём хранятся **refs, не URL**.

**Рекомендуемый способ для офлайна — env-ref + Gradle `test { environment(...) }`** (только публичный API,
без seam-инъекций, совместимо с `@StandTest`):
- поднять doubles на **фиксированных** адресах: HttpServer на фикс-порту (напр. `18080`), H2 на
  фикс-URL (`jdbc:h2:mem:exampledb;DB_CLOSE_DELAY=-1` — порт не нужен);
- в `build.gradle.kts` модуля: `tasks.test { environment("CLIENT_SERVICE_URL","http://localhost:18080");
  environment("MAIN_DB_URL","jdbc:h2:mem:exampledb;DB_CLOSE_DELAY=-1") }`;
- registry хранит ссылки → дефолтные executor'ы (через ServiceLoader) резолвят их в localhost-doubles.
  Риск: фикс-порт может конфликтовать в CI — задокументировать; REST-альтернатива без фикс-порта — инжект
  `BaseUrlResolver` в `RestStepExecutor(HttpCaller, baseUrlResolver→server.baseUrl())` (эфемерный порт),
  но тогда executor'ы собираются вручную, не из ServiceLoader (у `DbStepExecutor` 3-арг seam-конструктор
  **package-private**, кросс-модульно недоступен → DB всё равно остаётся на env-ref).

**DB-специфика офлайна (обязательно учесть — иначе DB-пример не заработает):**
- `DatasourceDefinition(alias, urlRef, userRef, passwordRef, allowedSchemas, writeAllowed)` требует **все
  три ref'а non-blank**, а `EnvironmentReferenceResolver` падает на blank/unset. H2-креды должны быть
  непустыми: задать `MAIN_DB_URL`/`MAIN_DB_USER`/`MAIN_DB_PASSWORD` (напр. `sa`/`sa` — H2 заводит
  пользователя при первом коннекте). `allowedSchemas` — физическое (lower-case) имя схемы; `writeAllowed=true`
  только для seed/cleanup-примеров.
- **Схему/таблицу H2 нельзя создать через SDK:** `db.seed/cleanup` проходят write-guard, но **DDL
  (`CREATE TABLE`/`CREATE SCHEMA`) запрещён** (DESTRUCTIVE_SQL, §8.8). Бутстрап схемы — **вне SDK**, прямым
  JDBC в `@BeforeAll`/extension (как `DbTestSupport`: `Statement.execute("CREATE SCHEMA ...; CREATE TABLE
  test_data.orders(...)")`). Затем сценарии делают только seed/query/expect/cleanup. Это нужно явно
  показать в DB-примере и в README (иначе «почему мой CREATE TABLE отвергается»).

**Следствия (зафиксировать):**
- **Исполняемые в CI по умолчанию:** REST (HttpServer) и DB (H2) — они покрывают DSL, capture/resolve,
  assertions, await/timeout (`db.expectEventually`), outbound correlationId (REST header) и Allure-репорт
  end-to-end.
- **Kafka:** офлайн-чёрный-ящик требует брокера. Варианты (выбрать на старте Фазы 2):
  - (A) embedded broker как `testImplementation` (допустимо §16) → исполняемый пример;
  - (B, рекомендуется для MVP) пример с тегом `@Tag("requires-broker")`, **исключён** из дефолтного
    `test` (через `useJUnitPlatform { excludeTags("requires-broker") }` в модуле), плюс
    документированный snippet — чтобы не тащить тяжёлую зависимость в основную сборку.
- **Реальный стенд (опц.):** любой пример запускается против DEV/IFT сменой env-значений (тех же refs),
  гейтится system-property/тегом `@Tag("stand")`, **выключен по умолчанию**. URLs — только из env (§9, §20),
  никогда не хардкодятся в исходниках.

---

## Решение 2 — Определение модуля `stand-test-example`

Отдельный модуль (не подмешивать примеры в адаптеры, §20). Примеры — это **тестовый код**: сценарии в
`src/test/java`, `src/main` пустой (только `package-info.java`).

**Правки скаффолда (Фаза 0):**
- `settings.gradle.kts`: добавить `"stand-test-example"` в `include(...)`.
- §4 SDK-плана: добавить раздел `### stand-test-example` (назначение: технические usage-примеры; что
  можно — DSL-демонстрации, doubles в test-scope; чего нельзя — бизнес-логика, реальные конфиги,
  публикация артефакта).
- §5 SDK-плана: добавить рёбра графа `example → core, junit, rest, kafka, db, allure` (всё как
  `testImplementation`; `await` — транзитивно через адаптеры, прямого ребра не нужно; модуль — сток-
  потребитель, от него никто не зависит).

**`build.gradle.kts` (draft):**
```kotlin
dependencies {
    testImplementation(project(":stand-test-core"))
    testImplementation(project(":stand-test-junit"))
    testImplementation(project(":stand-test-rest"))
    testImplementation(project(":stand-test-kafka"))
    testImplementation(project(":stand-test-db"))
    testImplementation(project(":stand-test-allure"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.h2)            // DB double
    // (A-вариант Kafka) testImplementation(<embedded-broker>) — только если выбран исполняемый Kafka
    testRuntimeOnly(libs.junit.platform.launcher)
}

// env-ref проводка doubles (Решение 1): дефолтные адаптеры резолвят refs из env. Все три DB-ref'а
// должны быть non-blank (иначе EnvironmentReferenceResolver падает).
tasks.test {
    environment("CLIENT_SERVICE_URL", "http://localhost:18080")
    environment("MAIN_DB_URL", "jdbc:h2:mem:exampledb;DB_CLOSE_DELAY=-1")
    environment("MAIN_DB_USER", "sa")
    environment("MAIN_DB_PASSWORD", "sa")
    // excludeTags("requires-broker", "stand")  // см. Решение 1 (Kafka/реальный стенд — вне дефолта)
}
```
HttpServer — из JDK (зависимость не нужна). Адаптерные `StepExecutor`'ы подхватываются с classpath через
их `META-INF/services` (ServiceLoader), как делает junit-extension.

**Публикация и coverage-gate (build-gotcha):** модуль-примеры не публикуется и не имеет production-кода.
Чтобы не трогать общий `subprojects {}`, отключить унаследованные задачи **локально** в
`stand-test-example/build.gradle.kts`:
```kotlin
tasks.withType<JacocoCoverageVerification>().configureEach { enabled = false }  // src/main пуст → 80% н/п
tasks.withType<AbstractPublishToMaven>().configureEach { enabled = false }       // артефакт не нужен
```
Checkstyle (main+test, zero-tolerance) **остаётся** — примеры обязаны быть стилево чистыми (AssertJ и т.д.).

---

## Решение 3 — Проводка (сливается с allure-Фазой 3)

Цель: показать канонический путь `@StandTest` + инъекция `StandClient`, с авто-подключением Allure. Два
подхода; **Фаза 1 использует ручной раннер (не требует правок junit/core), Фаза 2 добавляет авто-проводку.**

### Подход B (Фаза 1, гарантированный) — ручной раннер
Пример сам собирает раннер с явным registry (env→`EnvironmentDefinition`, хранит **refs**) и явным
publisher; refs резолвятся из env, проставленных Gradle (см. Решение 1). Нулевые изменения SDK:
```java
// draft — канонические record-конструкторы (сверено с core):
var correlation = new CorrelationConfig(CorrelationSource.HEADER, "X-Correlation-Id");
// EnvironmentDefinition = (name, services, topics, datasources, grpcTargets[, kafkaCluster]);
// 5-арг конструктор по умолчанию даёт kafkaCluster=null.
var ift = new EnvironmentDefinition("ift",
        Map.of("client-service", new ServiceEndpointDefinition("client-service", "CLIENT_SERVICE_URL", correlation)),
        Map.of(),                                   // topics
        Map.of("mainDb", new DatasourceDefinition(
                "mainDb", "MAIN_DB_URL", "MAIN_DB_USER", "MAIN_DB_PASSWORD",
                Set.of("test_data"), true)),        // allowedSchemas (lower-case), writeAllowed для seed/cleanup
        Map.of());                                  // grpcTargets (kafkaCluster=null по 5-арг конструктору)
var registry = new InMemoryEnvironmentRegistry(Map.of("ift", ift));
// схема/таблица H2 создаются ВНЕ SDK (DDL запрещён write-guard'ом) — прямым JDBC в @BeforeAll
var executors = new ArrayList<StepExecutor>();
ServiceLoader.load(StepExecutor.class).forEach(executors::add);   // дефолтные REST/DB executor'ы (env-resolving)
var runner = new DefaultScenarioRunner(executors, new DefaultScenarioValidator(), registry,
        new AllureReportingEventPublisher());
var stand = new DefaultStandClient(runner);
stand.run(scenario);   // scenario.environment("ift") выбирает окружение; алиасы резолвятся внутри него
```

### Подход A (Фаза 2, опц.) — авто-проводка через `@StandTest`
Минимальная правка `StandTestExtension.buildStandClient()` — discovery через ServiceLoader (сохраняет
развязку графа: junit и allure не получают compile-ребро, §8.5/§17):
- `ReportingEventPublisher` ← `ServiceLoader.load(ReportingEventPublisher.class)`, первый найденный, иначе
  `NoOpReportingEventPublisher.INSTANCE`. Allure-модуль публикует
  `META-INF/services/ru.alfa.stand.test.core.event.ReportingEventPublisher` →
  `ru.alfa.stand.test.allure.AllureReportingEventPublisher` (у него есть public no-arg ctor → совместим).
- `EnvironmentRegistry` ← `ServiceLoader.load(EnvironmentRegistry.class)`, первый, иначе пустой
  `InMemoryEnvironmentRegistry` (закрывает известный gap «пустого registry» из junit-итерации). Пример
  регистрирует свой registry-провайдер (на doubles) через SPI.
- Документировать: «первый-побеждает» + single-provider-предположение (композит — позже).

> Подход A — это junit-улучшение сверх строгого скоупа «примеров»; держать его отдельной фазой, чтобы
> риск правки extension не блокировал исполняемые примеры из Фазы 1.

---

## Сверка §10-черновика с фактическим API (исправить в примерах и в §10)

| §10 (draft) | Реальный API |
|---|---|
| `Scenario.builder(...).env("ift")` | `.environment("ift")` |
| `RestStep.post(...).body("fixtures/request.json")` | `.body(inline)`; ресурс — `.bodyFromResource("fixtures/request.json")` |
| `KafkaStep.send(...).body("fixtures/event.json")` | `.bodyFromResource("fixtures/event.json")` |
| `DbStep.expectEventually(...).query("select ...")` | `.sql("select ...")` (метода `.query` у инстанса нет — это фабрика) |
| `.paramFromContext("requestId")` | `.param("requestId", "${requestId}")` (резолв `${...}` из `VariableStore`) |
| `.expectSingleValue("SUCCESS")` | `.expectValue("SUCCESS")` |

Совпадают: REST `.injectCorrelationId/.expectStatus/.assertPath/.capture/.query(name,value)/.header`;
Kafka `.correlationIdFromContext/.withinSeconds/.within/.assertPath/.capture/.key/.injectCorrelationId`;
DB `.whereTestRunId/.withinSeconds/.within/.capture/.seed/.cleanup`.

Под-задача итерации: привести §10 SDK-плана к фактическому API (устранение противоречия плана, §18).

---

## Состав примеров (smoke-набор, draft)

Минимальный, технический, без бизнес-логики. Каждый — отдельный `@Test`, fixtures в
`src/test/resources/fixtures/*.json` и `.../sql/*.sql` (или inline для краткости).

1. **REST happy-path** (HttpServer): `RestStep.post(...).bodyFromResource(...).injectCorrelationId()
   .expectStatus(200).assertPath(...).capture("requestId","$.requestId")` → проверить успех + что double
   получил `correlationId`-header.
2. **DB query/expectEventually** (H2): `db.seed` (writeAllowed) → `db.query`/`db.expectEventually
   .sql(...).param(...).withinSeconds(...).expectValue(...)` + `db.cleanup .whereTestRunId(...)`.
3. **Связка REST→DB**: `rest.post` capture `requestId` → `db.seed` вставляет строку по `${requestId}`
   (writeAllowed) → `db.expectEventually` читает её → демонстрирует capture/resolve между шагами и общий
   `VariableStore` (без связывания double с H2). Альтернатива: handler HttpServer-double сам пишет в H2.
4. **Negative/timeout** (H2): `db.expectEventually` по несуществующей строке с коротким timeout →
   падает `StandTestAssertionError`, в отчёте — timeout-diagnostics (attempts/elapsed) и FAILED-шаг.
5. **Reporting end-to-end** *(реализовано в Phase 1)*: ассерт через **публичный facade-seam** —
   `new AllureReportingEventPublisher(capturingFacade)`, где `capturingFacade` — **свой класс модуля**,
   реализующий public-интерфейс `AllureLifecycleFacade` (см. фактический `CapturingAllureLifecycleFacade`),
   и записывающий имена шагов, статусы, `tag`-labels, параметры (scenarioId/testRunId/correlationId/
   environment) и attachments → ассерт по ним. **Нельзя** использовать
   `new DefaultAllureLifecycleFacade(new AllureLifecycle(writer))`: ctor
   `DefaultAllureLifecycleFacade(AllureLifecycle)` **package-private** (`DefaultAllureLifecycleFacade.java:38`)
   → из пакета примера не скомпилируется. `FakeAllureLifecycleFacade` тоже недоступен (test-scope
   allure-модуля) — поэтому в модуле заведён собственный capturing-facade.
6. **Kafka** (по Решению 1): `@Tag("requires-broker")` send+expect с `correlationIdFromContext` — либо
   исполняемый (вариант A), либо excluded-by-default + snippet (вариант B).
7. **(Фаза 2)** `@StandTest`-пример: `@StandTest(env="ift")` + `StandClient stand` параметр, авто-Allure.

---

## Project gotchas (проверено в предыдущих итерациях)

- **Toolchain Java 24**; checkstyle zero-tolerance (main+test): AssertJ обязателен (JUnit-`Assertions`/
  JUnit4-`@Test` — banned imports), non-JetBrains `@NotNull/@Nullable` — banned, `EmptyLineSeparator`,
  `OneStatementPerLine`, `InnerTypeLast`, `MethodName` (2-й символ строчный/цифра), `LineLength` 1000.
- **Нет `Thread.sleep`** (DoD): любое ожидание — только через SDK-await (`db.expectEventually`/await-API).
- **Нет hardcoded URLs/secrets**: адреса doubles задаются через Gradle `test { environment(...) }`
  (фикс-порт HttpServer / фикс H2-URL), а не литералами в исходниках; алиасы — через `EnvironmentRegistry`
  (хранит refs). Эфемерный порт допустим только в REST-альтернативе с инжектом `BaseUrlResolver`.
- **HttpServer/H2** — те же doubles, что в `RestStepExecutorHttpTest`/db-тестах; переиспользовать паттерн.
- **Git:** формат `type: description` (feat/test/docs/chore); **без** `Co-Authored-By` (атрибуция
  отключена). Прецедент: prerequisite/проводка (правки junit/allure) — отдельным коммитом от модуля-примеров.

## Verification

```bash
./gradlew :stand-test-example:test --console=plain        # исполняемые примеры (REST+DB), зелёные офлайн
./gradlew :stand-test-example:build --console=plain
./gradlew build --console=plain                           # всё вместе перед коммитом
# опц.: -PwithKafka / --tests с тегами для requires-broker/stand
```

## Definition of Done (§18, адаптировано к модулю-примерам)

- [ ] `stand-test-example` в `settings.gradle.kts`, §4 и §5; граф ацикличен.
- [ ] `./gradlew build` зелёный; примеры REST+DB исполняются офлайн.
- [ ] checkstyle (main+test) чист; coverage-gate для модуля исключён (нет production-кода) — задокументировано.
- [ ] есть README модуля (что показывает, как запускать, execution model, как переключить на реальный стенд).
- [ ] нет `Thread.sleep`/hardcoded URLs/secrets/raw Kafka/JDBC bypass.
- [ ] покрыты timeout/negative и проброс SDK-падения в JUnit; reporting (шаги/attachments/labels) проверены.
- [ ] §10 SDK-плана приведён к фактическому API.
- [ ] (Фаза 2, если делается) `@StandTest` + авто-Allure через ServiceLoader; Kafka-пример по выбранному варианту.

## Порядок и оценка

1. **Фаза 0** — скаффолд: `settings`/§4/§5, `build.gradle.kts`, исключения publish/coverage. Небольшая.
2. **Фаза 1** — исполняемые примеры (REST+DB) через ручной раннер + doubles + fixtures + reporting-ассерт;
   README; сверка §10. **Это закрывает MVP-DoD Итерации 8** (build зелёный, Allure-диагностика, usage-примеры).
3. **Фаза 2 (опц.)** — авто-проводка `@StandTest` (ServiceLoader publisher + registry) и Kafka-пример;
   правки junit/allure — отдельным коммитом.

После Фазы 1 Итерация 8 формально закрыта; Фаза 2 даёт канонический `@StandTest`-путь и Kafka. Далее по
§7 — Итерация 9 (YAML DSL design) и Итерация 10 (AI guardrails).
