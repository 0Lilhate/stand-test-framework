# stand-test-example — дальнейшие шаги (Phase 2 и далее)

Продолжение `docs/arch/stand-test-example-implementation-plan.md`. Фиксирует **детальные** следующие шаги
после уже сделанных **Phase 0–1**. Источник истины: `stand-test-sdk-implementation-plan.md` (§7
Итерация 8, §9 env-model, §17 observability, §18 DoD). Все code-сниппеты — draft.

## Текущий статус (сделано в Phase 0–1)

- Модуль `stand-test-example` заведён (`settings.gradle.kts`, §4/§5 SDK-плана), `./gradlew build` зелёный.
- 5 исполняемых примеров офлайн через **ручной раннер** (`ExampleStand.stand(...)`) против in-process
  doubles: REST (JDK `HttpServer`, публичный passthrough-сид `RestStepExecutor(WebClientHttpCaller, ref->ref)`,
  эфемерный порт), DB (H2, no-arg `DbStepExecutor` + env-ref, схема bootstrap'ится вне SDK).
- Reporting проверяется через свой `CapturingAllureLifecycleFacade` (паблишер принимает фасад публичным
  конструктором).
- §10 SDK-плана приведён к фактическому API.

**Не сделано (это и есть «дальнейшие шаги»):** канонический путь `@StandTest` с авто-Allure и реальным
`EnvironmentRegistry`, а также Kafka-пример.

---

## Phase 2 — канонический `@StandTest`-путь + Kafka

Три независимо поставляемых шага. Делать в порядке 2.1 → 2.2 → 2.3 (каждый — отдельный коммит; правки
`junit`/`allure` — отдельно от модуля-примеров, как core-prereq в прошлых итерациях).

### Шаг 2.1 — авто-подключение Allure через ServiceLoader (`junit` + `allure`)

**Зачем.** Сейчас `StandTestExtension.buildStandClient()` строит раннер с `NoOpReportingEventPublisher`;
Allure не включается в `@StandTest`-прогонах. Цель — авто-включить, **не** создавая compile-ребра
`junit → allure` (граф §5: оба развязаны, связь только в рантайме через core-SPI, §8.5/§17).

**Что менять.**
- `stand-test-allure`: добавить ресурс
  `src/main/resources/META-INF/services/ru.alfa.stand.test.core.event.ReportingEventPublisher` с одной
  строкой `ru.alfa.stand.test.allure.AllureReportingEventPublisher` (у класса есть public no-arg ctor →
  ServiceLoader-совместим).
- `stand-test-junit` (`StandTestExtension.buildStandClient`): метод **уже** грузит `StepExecutor` через
  `ServiceLoader` (`StandTestExtension.java:145`) — этот блок и импорт `java.util.ServiceLoader`
  **сохраняются**; Шаг 2.1 лишь добавляет ещё два lookup'а и переходит на 4-арг ctor. Сейчас (строки 144–147):
  ```java
  List<StepExecutor> executors = new ArrayList<>();
  ServiceLoader.load(StepExecutor.class).forEach(executors::add);          // ← остаётся как есть
  ScenarioRunner runner = new DefaultScenarioRunner(executors);           // 1-арг ctor → пустой registry + NoOp
  ```
  заменить **только последнюю строку** на discovery (draft; `executors`-блок выше не трогаем):
  ```java
  ReportingEventPublisher publisher = ServiceLoader.load(ReportingEventPublisher.class)
          .findFirst().orElse(NoOpReportingEventPublisher.INSTANCE);
  EnvironmentRegistry registry = ServiceLoader.load(EnvironmentRegistry.class)
          .findFirst().orElseGet(() -> new InMemoryEnvironmentRegistry(Map.of()));   // см. Шаг 2.2
  ScenarioRunner runner = new DefaultScenarioRunner(
          executors, new DefaultScenarioValidator(), registry, publisher);
  ```
  (4-арг `DefaultScenarioRunner` уже есть — `DefaultScenarioRunner.java:87`; 1-арг ctor на `:70` сам
  подставляет `InMemoryEnvironmentRegistry(Map.of())` + `NoOpReportingEventPublisher.INSTANCE`.)
  Дописать недостающие импорты (checkstyle zero-tolerance: ни лишних, ни недостающих): `java.util.Map`,
  `ru.alfa.stand.test.core.event.ReportingEventPublisher`,
  `ru.alfa.stand.test.core.event.NoOpReportingEventPublisher`,
  `ru.alfa.stand.test.core.environment.EnvironmentRegistry`,
  `ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry`,
  `ru.alfa.stand.test.core.validation.DefaultScenarioValidator`.

**Решения зафиксировать.** «Первый-побеждает» + single-provider-предположение (несколько publisher'ов —
не поддерживаем; композит/приоритеты — позже). `findFirst()` берёт первого по **порядку итерации
classpath** (не по приоритету) — недетерминированно при двух провайдерах; это **осознанное
ограничение MVP**, пометить код-комментарием у обоих lookup'ов. Дефолты: NoOp publisher, пустой registry —
поведение без провайдеров не меняется (обратная совместимость существующих junit-тестов).

**Тесты (junit).** Через `EngineTestKit` (как `StandTestExtensionTest`): фикстура с тестовым
`ReportingEventPublisher`-провайдером (в `src/test/resources/META-INF/services`) — проверить, что раннер
его подхватил (счётчик опубликованных событий > 0). Существующие тесты остаются зелёными (дефолт NoOp).

**DoD 2.1.** `./gradlew :stand-test-junit:build :stand-test-allure:build` зелёные; на classpath с allure
шаги `@StandTest`-теста дают Allure-вывод; граф не получил новых compile-рёбер.

### Шаг 2.2 — реальный `EnvironmentRegistry` в `@StandTest` + `@StandTest`-пример

**Зачем.** Чтобы `@StandTest`-пример выполнял REST/DB, раннеру нужен непустой registry (сейчас пустой —
known-gap из junit-итерации). Алиасы должны резолвиться в doubles.

**Ключевое ограничение (timing).** `StandClient` в extension строится **лениво** и кэшируется на прогон
движка; `EnvironmentRegistry` через `ServiceLoader` инстанцируется no-arg-конструктором и **не знает
рантайм-адресов** (эфемерный порт HttpServer). Поэтому для `@StandTest`-пути переходим на
**env-ref + фиксированные адреса** (а не passthrough-сид из Phase 1):
- REST: дефолтный `RestStepExecutor` (no-arg, ServiceLoader) использует `EnvironmentBaseUrlResolver`,
  который резолвит **любой** `endpoint.baseUrlRef()` через `System.getenv` и **fail-closed** на
  unset/blank-значении (`EnvironmentBaseUrlResolver.java:37-46` → `StandTestException`, т.е. REST-шаг
  падает как infra-ошибка, не как assertion). Имя `CLIENT_SERVICE_URL` и порт `18080` — **соглашение
  примера, а не SDK-константы** (в `stand-test-rest` их нет): HttpServer поднимается на **фикс-порту**
  `18080`, env `CLIENT_SERVICE_URL=http://localhost:18080` (через `tasks.withType<Test>{ environment(...) }`),
  в registry — `baseUrlRef="CLIENT_SERVICE_URL"`. Риск фикс-порта в CI — **осознанное ограничение**,
  задокументировать (см. ниже рекомендацию параметризовать порт env-var'ом).
- DB: как в Phase 1 (фикс H2-URL + env-refs).
- Registry: пример предоставляет **свой** `EnvironmentRegistry`-провайдер (no-arg impl, оборачивает
  `InMemoryEnvironmentRegistry` с env-ref-определениями) через
  `META-INF/services/ru.alfa.stand.test.core.environment.EnvironmentRegistry`. (`InMemoryEnvironmentRegistry`
  имеет только Map-конструктор → нужен тонкий no-arg wrapper.)

**Lifecycle doubles.** HttpServer (фикс-порт) и H2-схема поднимаются один раз на прогон движка. Варианты:
(а) отдельный JUnit-extension/`@BeforeAll` в примере, стартующий doubles до первого `@StandTest`-теста;
(б) ленивая инициализация в no-arg registry-провайдере (при первом resolve поднять doubles идемпотентно).
**Рекомендация:** (а) — явный extension `ExampleDoublesExtension` (start в `beforeAll`, stop в `afterAll`/
`close`), порядок относительно `StandTestExtension` детерминирован (registry читает env, а не сам сервер).

**Альтернатива (если не вводить registry-SPI):** оставить исполняемые примеры на ручном раннере (Phase 1),
а `@StandTest` показать только для инъекции `@ScenarioId`/`@StandEnv`/`Awaiter` (без REST/DB-шага). Менее
показательно, но нулевые изменения junit/core. Выбрать на старте шага.

**Зависимости.** `@StandTest` требует `testImplementation(project(":stand-test-junit"))` — **сейчас junit
в зависимостях `stand-test-example` нет** (`build.gradle.kts` тянет только core/rest/db/allure), его надо
добавить. Соответственно дорисовать ребро `example → junit` в §5-диаграмме (см. ниже про §5-mermaid).

**Тесты/пример.** `@StandTest(env="ift")` + `StandClient stand` параметр: тот же REST→DB-сценарий, что в
Phase 1, но через инъекцию. Проверить успех; Allure-вывод — через capturing-провайдер из Шага 2.1.

**DoD 2.2.** `@StandTest`-пример исполняется офлайн зелёным; registry резолвит алиасы в doubles; известный
gap «пустого registry» закрыт (или явно оставлен с обоснованием по выбранной альтернативе).

### Шаг 2.3 — Kafka-пример

**Зачем.** Phase 1 без Kafka: офлайн-чёрный-ящик через публичный API требует брокера (`kafka.expect`
поднимает реальный `KafkaConsumer`; `MockConsumer` живёт во внутренних адаптерных тестах через seam и
снаружи не инжектится).

**Решение (выбрать).**
- (A) **Embedded broker** как `testImplementation` (допустимо §16) → исполняемый Kafka-пример (send+expect
  с `correlationIdFromContext`, JSONPath-ассерт). Минус — тяжёлая тест-зависимость.
- (B, рекомендуется для MVP) Kafka-пример с `@Tag("requires-broker")`, **исключён** из дефолтного `test`
  (`tasks.withType<Test>{ useJUnitPlatform { excludeTags("requires-broker") } }`), плюс документированный
  snippet и инструкция запуска против локального/стенд-брокера. Держит основную сборку лёгкой.
  **Внимание:** в текущем `stand-test-example/build.gradle.kts` блока `useJUnitPlatform { … }` **нет**
  (есть только `environment(...)` и `enabled = false`) — его надо **добавить**, не модифицировать
  существующий; иначе тег не исключается и тест потребует брокер в дефолтном CI.

**Зависимости.** Добавить `testImplementation(project(":stand-test-kafka"))` (+ §5-ребро `example → kafka`;
embedded-broker — только при варианте A). Координаты embedded-broker в version-catalog сейчас нет
(`libs.versions.toml` содержит только `kafka-clients 3.9.2`, `MockConsumer/Producer` — из самого
kafka-clients) → вариант A требует **сначала завести** координату брокера в каталог.

**§5-mermaid (DoD §18 — устранить противоречие).** §5-диаграмма сейчас рисует только
`example → core, rest, db, allure` (4 ребра); рёбра `example → junit` (Шаг 2.2) и `example → kafka`
(Шаг 2.3) в prose упомянуты как «Phase 2 добавит», но в диаграмме **не нарисованы**. Дорисовывать их надо
**в том же PR**, что добавляет соответствующий `testImplementation(...)`, иначе диаграмма разойдётся с
prose и нарушит §18 («противоречия в плане устранены»). Ацикличность сохраняется — `example` остаётся
sink'ом, от него никто не зависит.

**DoD 2.3.** Вариант A — Kafka-пример зелёный в CI; вариант B — компилируется, исключён из дефолта,
snippet+README обновлены.

---

## Cross-cutting (для всех шагов Phase 2)

- **Нет `Thread.sleep`** — ожидания только через SDK-await; doubles стартуют синхронно (HttpServer.start /
  H2 connect), без сна.
- **Изоляция:** общий H2 на JVM (фикс-URL) — уникальные id на тест + `testRunId`-cleanup; фикс REST-порт —
  один сервер на прогон движка.
- **Нет hardcoded URLs/secrets** — адреса doubles через `tasks...environment(...)`, не литералами в коде.
- **Checkstyle zero-tolerance** на main+test (AssertJ, `EmptyLineSeparator`, `InnerTypeLast`, `MethodName`
  2-й символ строчный/цифра и т.д.).
- **Git:** `type: description`, без `Co-Authored-By`; правки `junit`/`allure` — отдельным коммитом от модуля.

## Beyond Phase 2 (бэклог, вне Итерации 8)

- **Реальный стенд (профиль).** Тег `@Tag("stand")` + env-значения на реальные DEV/IFT (выключен по
  умолчанию). Сценарии не меняются — только env-refs (§9/§20).
- **gRPC-пример.** После реализации `stand-test-grpc` (вне MVP, §6) — по аналогии с REST/Kafka.
- **spring-boot-starter usage.** Когда появится `stand-test-spring-boot-starter`: `@Autowired StandClient`
  как post-MVP-удобство (§4) — отдельный пример/модульная секция.
- **Документация для потребителей.** Вынести canonical-usage из README модуля в общий getting-started,
  если потребуется внешним командам.

## Verification (Phase 2)

```bash
./gradlew :stand-test-junit:build :stand-test-allure:build --console=plain   # Шаг 2.1
./gradlew :stand-test-example:test --console=plain                          # Шаги 2.2–2.3 (дефолт, без брокера)
./gradlew :stand-test-example:test --console=plain -DincludeTags=requires-broker  # Kafka (вариант B, по требованию)
./gradlew build --console=plain                                             # всё вместе
```

## Открытые решения к подтверждению (перед стартом)

1. Registry в `@StandTest`: вводить ServiceLoader-провайдер `EnvironmentRegistry` (Шаг 2.2 основной) или
   оставить примеры на ручном раннере (альтернатива)?
2. Kafka: embedded broker (A) или tagged-disabled + snippet (B, рекомендуется)?
3. REST в `@StandTest`: фикс-порт + env-ref (для ServiceLoader-пути) — приемлем ли риск фикс-порта в CI?
   (Рекомендация: параметризовать порт через Gradle-property с дефолтом `18080`, чтобы CI мог переопределить
   и избежать port-collision; эфемерный порт на ServiceLoader-пути **недоступен** — registry строится no-arg,
   без рантайм-параметров.)
4. Размещение теста регистрации Шага 2.1 (Allure ServiceLoader): из-за package-private
   `DefaultAllureLifecycleFacade(AllureLifecycle)` capturing-через-`AllureLifecycle` компилируется **только**
   в пакете `…allure.lifecycle`. Тест-регистрации (по образцу `RestStepExecutorRegistrationTest`) делать в
   пакете `…allure` через public-seam (`FakeAllureLifecycleFacade` или свой facade), а capturing-через-writer
   — лишь если тест лежит в пакете `lifecycle`? (Рекомендация: public-seam, без `DefaultAllureLifecycleFacade(AllureLifecycle)`.)
5. §5-mermaid: обновлять диаграмму (рёбра `example → junit/kafka`) синхронно с добавлением зависимостей в
   том же PR (DoD §18) — подтвердить как часть scope, а не отдельной задачей.
