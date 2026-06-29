# stand-test-core — План исправлений (remediation plan)

> Статус: **Proposed** (2026-06-26). План устранения замечаний по итогам жёсткого ревью
> модуля `stand-test-core` (Итерация 1).
> Источники истины: ревью `stand-test-core` (состязательная проверка: подтверждено 19 находок из 33),
> архитектурный план `docs/arch/stand-test-sdk-implementation-plan.md`, правила `.claude/rules/`,
> `CLAUDE.md`.
>
> **Этот документ — только план.** Код, тесты и Gradle на этом шаге не меняются; здесь зафиксированы
> *что*, *где*, *как проверить* и *в каком порядке*.

---

## 0. Контекст и итог ревью

`stand-test-core` принят со статусом **APPROVED WITH COMMENTS**. Блокирующих (critical/major)
проблем нет: граница ядра чиста (нет адаптеров/IO/Spring/Allure/YAML/Testcontainers, нет
`Thread.sleep`, hardcoded URL и секретов), сборка/checkstyle/тесты зелёные, все обязательные сущности
из плана присутствуют, иммутабельность и failure-семантика корректны. Переход к `stand-test-await`
**разрешён и не блокируется** этим планом.

Назначение плана — закрыть подтверждённые MINOR/NITPICK-замечания и зафиксировать архитектурные
условия **до того, как число модулей вырастет** и мелкие отклонения расползутся по сигнатурам.

### Принципы, которые план НЕ нарушает

- **Граница ядра неизменна.** Никаких adapter/IO/Spring/Allure/YAML/Testcontainers-зависимостей и кода.
  Core остаётся JDK-only стоком графа.
- **Contracts-only для Итерации 1.** Не «дотягиваем» сюда логику раннера/валидатора окружений/AI-схемы —
  только то, что относится к контрактам и моделям ядра.
- **Java 24 / `--release` не трогаем в рамках этого плана.** Это отдельный проектный open decision
  (план §14, `CLAUDE.md`). Здесь он только зафиксирован как риск (см. [§4](#4-архитектурные-риски-условия-для-будущих-итераций)).
- **Стиль кода/тестов сохраняется.** AssertJ (не JUnit-assertions), JUnit 5 `@Test`/`@DisplayName`,
  `Objects.requireNonNull`/blank-проверки вместо nullability-аннотаций, 4 пробела (Java) / 2 (kts/toml),
  checkstyle zero-tolerance (`maxWarnings = 0`).
- **TDD по правилам проекта.** Для задач с тестами: сначала RED, потом GREEN (для исправлений кода —
  тест, фиксирующий новое поведение, до правки).

---

## 1. Сводка задач

| ID | Severity | Область | Задача | Этап |
|----|----------|---------|--------|------|
| C-1 | MINOR | Gradle/CI | ✔ **Сделано:** JaCoCo coverage-gate ≥ 80 % подключён (core: 89% INSTRUCTION) | 1 |
| C-2 | MINOR | Модель | ✔ **Решено (Вариант B):** `String` задокументирован как принятое отклонение (README + план §4/§21) | 1 |
| C-3 | MINOR | Тесты | Дотестировать `EnvironmentDefinition.topic()/datasource()/grpcTarget()` + иммутабельность их карт | 2 |
| C-4 | NITPICK | Тесты | Покрыть `StepExecutionContext.resolver()` | 2 |
| C-5 | NITPICK | Тесты | Покрыть `ScenarioResult.duration()` и false-ветку `isSuccessful()` | 2 |
| C-6 | NITPICK | Тесты | Зафиксировать полный набор кодов `ForbiddenOperation` (anti-drift) | 2 |
| C-7 | NITPICK | Доки | Переформулировать present-tense в `validation/package-info.java` | 2 |
| C-8 | NITPICK | Resolver | Унифицировать поведение `${}` vs `${ }` | 3 |
| C-9 | NITPICK | VariableStore | Задокументировать shallow-snapshot `asMap()` и single-thread-per-run | 3 |
| C-10 | NITPICK | GenericStep | Явное сообщение при null-значении параметра | 3 |
| C-11 | NITPICK | StepResult | Non-blank `errorMessage` для FAILED/TIMEOUT (или явно задокументировать nullable) | 3 |
| C-12 | NITPICK | Validator | Убрать/пометить недостижимую ветку `SCENARIO_ID_REQUIRED` | 3 |

Дополнительно — архитектурные риски (R-1…R-6, [§4](#4-архитектурные-риски-условия-для-будущих-итераций))
и список **намеренно не исправляемого** (NON_ISSUE, [§5](#5-намеренно-не-исправляем-non_issue)).

---

## 2. Этап 1 — до накопления модулей (приоритет)

### C-1 · Подключить JaCoCo coverage-gate ≥ 80 % — MINOR

> **✔ Сделано (2026-06-26).** JaCoCo подключён в корневом `subprojects { }` (`apply(plugin = "jacoco")` +
> блок `plugins.withId("jacoco")`); версия `jacoco = "0.8.15"` в каталоге (нужна ≥ 0.8.13 для байткода
> Java 24). Правило: `INSTRUCTION` ≥ `0.80` на уровне BUNDLE, привязано к `check`. Модули без
> execution-data (скелеты, `test` = NO-SOURCE) пропускаются через `onlyIf { executionData.files.any { it.exists() } }`,
> поэтому `./gradlew build` зелёный. Факт: `stand-test-core` — **89% INSTRUCTION** (89.07%; LINE 90.9%,
> METHOD 87.5%, CLASS 100%), гейт проходит. Проверено и негативно: при `minimum = 0.99` сборка падает с
> `instructions covered ratio is 0.89, but expected minimum is 0.99`.
>
> **Известное ограничение (принято):** модуль с реальным main-кодом, но **без** тестов (нет `.exec`)
> будет пропущен `onlyIf` и обойдёт гейт. Сейчас безвредно (реальный код только в `core`, и он покрыт);
> закрыть позже конвенцией «есть main-код → есть хотя бы один тест» или агрегированным root-отчётом —
> при появлении первого адаптера с реальным кодом.

- **Проблема.** DoD (§18) для code-итераций 1–8 требует `coverage ≥ 80 %`, но JaCoCo нигде не
  сконфигурирован (`grep -rni jacoco` пуст; корневой `subprojects { }` в `build.gradle.kts:28–30`
  применяет только `java-library`/`checkstyle`/`maven-publish`). Гейт нельзя ни измерить, ни форсить.
- **Где.** Корневой `build.gradle.kts` (блок `subprojects { }`), `gradle/libs.versions.toml`.
- **Что сделать (предложение).**
  1. Добавить версию в каталог: `jacoco = "0.8.12"` (валидировать совместимость с Gradle 9.3.0 / JDK 24).
  2. В корневом `subprojects { }` — по аналогии с checkstyle и с тем же исключением `stand-test-bom`
     (`java-platform` несовместим с `jacoco`):
     ```kotlin
     apply(plugin = "jacoco")

     extensions.configure<JacocoPluginExtension> { toolVersion = ver("jacoco") }

     tasks.withType<JacocoCoverageVerification>().configureEach {
       violationRules {
         rule { limit { minimum = "0.80".toBigDecimal() } }
       }
     }
     tasks.named("check") { dependsOn(tasks.withType<JacocoCoverageVerification>()) }
     ```
  3. Проверить, что toolchain Java 24 поддерживается выбранной версией JaCoCo; при несовместимости —
     зафиксировать как известный risk и поднять минимальную версию.
- **Acceptance.** `./gradlew :stand-test-core:check` прогоняет `jacocoTestCoverageVerification`;
  при покрытии < 80 % сборка падает; текущее покрытие core ≥ 80 % (иначе — добить тестами из Этапа 2).
- **Замечание по объёму.** Гейт настраивается на уровне корня и влияет на **все** модули — согласовать,
  чтобы скелеты (`*/package-info.java`-only) не валили `check` из-за пустого покрытия (для модулей без
  тестов правило либо не активируется при NO-SOURCE, либо настраивается порог по classpath).

### C-2 · Решение по value-объекту `Environment` — MINOR

> **✔ Решено (2026-06-26): Вариант B.** `String` принят как осознанное MVP-упрощение и явно
> задокументирован в `stand-test-core/README.md` (раздел «What is in core») и в плане §4/§21. Код,
> сигнатуры и тесты не менялись. Введение типа `Environment`/`EnvironmentName` (Вариант A, имя
> `EnvironmentName`, объём Full — выбор зафиксирован на случай возврата к задаче) отложено как будущее
> ломающее изменение; при необходимости его уместно сделать **до** итераций адаптеров.

- **Проблема.** План §4 (стр. 140) и §21 (стр. 972) перечисляют `Environment` среди базовых
  value-объектов рядом с `ScenarioId`/`TestRunId`/`CorrelationId`. Три из четырёх реализованы как
  record'ы, четвёртый — нет: окружение моделируется голым `String`
  (`ScenarioContext.java:28`, `Scenario.java:23,33`, `EnvironmentDefinition.java:17`).
  Корректностной дыры нет (blank-проверки на месте), но нарушена типовая консистентность, а будущая
  миграция `String → Environment` на фундаментальном типе будет ломающей.
- **Решение — выбрать ОДИН вариант (требует подтверждения владельца архитектуры):**
  - **Вариант A (рекомендуется) — ввести тип.** Новый record `Environment` (или `EnvironmentName`) в
    пакете `identifier` по образцу id-типов:
    ```java
    public record Environment(String value) {
        public Environment {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("environment value must not be blank");
            }
        }
        public static Environment of(String value) { return new Environment(value); }
        @Override public String toString() { return value; }
    }
    ```
    Использовать в `ScenarioContext.environment`, `Scenario.environment`, `EnvironmentRegistry.environment(...)`,
    `EnvironmentDefinition.name`. **Важно про permissive-модель `Scenario`:** сохранить разделение
    Model→Validator — в `Scenario` допускается «пустое»/отсутствующее окружение до валидации, поэтому
    либо хранить `Environment` как nullable до build с проверкой в `DefaultScenarioValidator`
    (`ENVIRONMENT_REQUIRED`), либо оставить в `Scenario` строковый ввод билдера и материализовать
    `Environment` на границе. Решение зафиксировать в этой задаче, чтобы не сломать тест
    `ScenarioTest.missingEnvironment_defaultsToEmpty`.
  - **Вариант B — задокументировать отклонение.** Если `String` выбран сознательно как MVP-упрощение —
    явно отметить это в `stand-test-core/README.md` (раздел «What is in core») и в плане §21, по образцу
    того, как уже задокументирован отложенный forbidden-op validation. Тогда C-2 закрывается без кода.
- **Acceptance.**
  - A: `Environment` покрыт unit-тестом (blank/null reject, value-based equals/hashCode/toString) по
    образцу `IdentifierTest`; все существующие тесты зелёные; checkstyle чист.
  - B: README/план обновлены, отклонение названо явно; код не меняется.
- **Зависимость.** Вариант A затрагивает сигнатуры, которые поедут в `await`/адаптеры — выполнять
  **до** старта зависимых модулей либо осознанно выбрать B сейчас и A позже (с пониманием ломающего
  изменения).

---

## 3. Этап 2 — полнота тестов и честность документации

> Все задачи этапа — после C-1 (чтобы новое покрытие сразу мерилось гейтом). Тесты — JUnit 5 + AssertJ,
> hermetic, без IO/Testcontainers/sleep.

### C-3 · Lookup'ы и иммутабельность карт `EnvironmentDefinition` — MINOR

- **Проблема.** `EnvironmentTest.environmentDefinition_resolvesAliases` проверяет только `service()` и
  иммутабельность `services()`. `topic()`/`datasource()`/`grpcTarget()` (`EnvironmentDefinition.java:49–71`)
  и defensive-copy для `topics()/datasources()/grpcTargets()` (`:27–30`) не покрыты. Это
  whitelist-поверхность: опечатка `*.get(alias)` против неверной карты или пропуск `Map.copyOf` пройдут
  незамеченными.
- **Где.** `stand-test-core/src/test/java/.../environment/EnvironmentTest.java`.
- **Что сделать.** Расширить (или параметризовать) тест: наполнить по одному topic/datasource/grpcTarget,
  проверить present + empty lookup для каждого, и бросок `UnsupportedOperationException` при мутации
  каждой из четырёх карт-аксессоров.
- **Acceptance.** Покрыты все четыре `*()`-lookup'а и иммутабельность всех четырёх карт; тест зелёный.

### C-4 · Покрыть `StepExecutionContext.resolver()` — NITPICK

- **Проблема.** `StepExecutionContext.java:41–43` — единственный поведенческий метод holder'а — не
  вызывается ни в одном тесте (`StepExecutionContextTest` покрывает только `exposesCollaborators`/
  `rejectsNullCollaborators`).
- **Что сделать.** Тест: положить переменную в `VariableStore`, вызвать
  `context.resolver().resolve("${var} ${scenarioId}")`, проверить, что резолвятся и пользовательская
  переменная, и built-in из того же `ScenarioContext`/store, с которыми построен holder.
- **Acceptance.** `resolver()` имеет прямой тест; регрессия привязки к чужому store ловится.

### C-5 · Покрыть `ScenarioResult.duration()` и false-ветку `isSuccessful()` — NITPICK

- **Проблема.** `ScenarioResult.duration()` (`:47–49`) не покрыт (в отличие от `StepResult.duration()`,
  `StepResultTest:25`); false-ветка `isSuccessful()` не зафиксирована (нет `isFalse()` на FAILED/TIMEOUT/
  SKIPPED).
- **Что сделать.** В `ScenarioResultTest`: добавить `assertThat(result.duration()).isEqualTo(Duration.between(START, END))`
  в `from_allSuccess_isSuccess`; добавить `assertThat(failed.isSuccessful()).isFalse()` в
  `from_anyFailure_isFailed`.
- **Acceptance.** Обе ветки/метода покрыты.

### C-6 · Anti-drift тест для `ForbiddenOperation` — NITPICK

- **Проблема.** `ForbiddenOperationTest` пинит только 5 из 11 кодов (`ForbiddenOperation.java:10–43`).
  Аддитивный дрейф enum'а — ровно то, ради предотвращения чего он объявлен «единым источником истины» —
  пройдёт молча.
- **Что сделать.** Зафиксировать полный ожидаемый набор кодов (`containsExactlyInAnyOrder(...)`) **или**
  снапшот `values().length`, чтобы любое изменение каталога требовало осознанного обновления теста.
- **Acceptance.** Добавление/удаление константы ломает тест.

### C-7 · Переформулировать present-tense в `validation/package-info.java` — NITPICK

- **Проблема.** `validation/package-info.java:7–9` утверждает в настоящем времени, что
  `ForbiddenOperation` *«consumed by the runtime validator and (later) by the AI schema»*, тогда как
  `DefaultScenarioValidator` его **не** потребляет (forbidden-op validation явно отложена).
- **Что сделать.** Переформулировать на честный отложенный контракт, например:
  `«…to be consumed by the runtime validator (not yet wired in this iteration) and later by the AI schema.»`
  — в тон уже честной последней фразе того же package-info и Javadoc `DefaultScenarioValidator`.
- **Acceptance.** Текст не вводит в заблуждение о статусе; checkstyle (Javadoc-правила) чист.

---

## 4. Этап 3 — косметика / NITPICK (можно отложить)

### C-8 · Унифицировать `${}` vs `${ }` — NITPICK

- **Проблема.** `VariableResolver.java:21` (`Pattern "\$\{([^}]+)}"`, квантор `+` требует ≥1 символа):
  `${}` не матчится и проходит как литерал, а `${ }` матчится, триммится в пустую строку и кидает
  `StandTestException`. Две записи пустого имени ведут себя противоположно.
- **Что сделать.** Выбрать ОДНО поведение и применить единообразно:
  - либо расширить паттерн до `([^}]*)`, чтобы `${}` тоже доходил до blank-guard и кидал
    `StandTestException` (тогда обновить ожидание `VariableResolverTest:88`);
  - либо оставить оба «не-плейсхолдерами» (тогда не кидать на `${ }`).
  Рекомендация для guardrailed SDK — fail-fast (первый вариант). Решение задокументировать в Javadoc
  `VariableResolver`.
- **Acceptance.** Оба написания пустого имени ведут себя одинаково; поведение покрыто тестом и описано.

### C-9 · Документация `VariableStore` — NITPICK

- **Проблема.** `asMap()` (`VariableStore.java:71–72`) — shallow snapshot: структура карты иммутабельна,
  но мутабельные значения остаются общими. Плюс класс потокобезопасен только при one-store-per-run
  (`:18`, голый `LinkedHashMap`).
- **Что сделать.** В Javadoc класса/метода явно указать: (1) `asMap()` — поверхностный снимок (значения
  делятся ссылкой); (2) контракт «один store на один прогон, один поток» — внутри прогона store не
  потокобезопасен. Кода не менять.
- **Acceptance.** Оба ограничения задокументированы; будущий раннер опирается на явный контракт.

### C-10 · Явное сообщение при null-значении параметра `GenericStep` — NITPICK

- **Проблема.** `GenericStep.java:28` (`Map.copyOf(parameters)`) на null-значении кидает «голый» `NPE`,
  тогда как id/type (`:21–26`) валидируются с понятным `IllegalArgumentException`.
- **Что сделать.** Опционально пред-валидировать карту параметров на null-значения и бросать
  `IllegalArgumentException` с именем нарушившего ключа до `Map.copyOf` (либо явно задокументировать
  NPE-контракт). Сохранить fail-fast в конструкторе.
- **Acceptance.** Сообщение об ошибке actionable; тест на null-значение проверяет тип/сообщение.

### C-11 · `errorMessage` для FAILED/TIMEOUT — NITPICK

- **Проблема.** `StepResult` (`:32–43`, фабрики `failed` `:77–79` / `timeout` `:91–93`) допускает
  null/blank `errorMessage` для провальных статусов — отчёт без причины.
- **Что сделать.** Выбрать: (a) требовать non-blank `errorMessage` при `status.isFailure()` в каноническом
  конструкторе; **или** (b) осознанно оставить nullable и явно это задокументировать. Не должно нарушать
  правило «`FAILED` не подменяет брошенный provал».
- **Acceptance.** Поведение зафиксировано тестом и/или Javadoc.

### C-12 · Недостижимая ветка `SCENARIO_ID_REQUIRED` — NITPICK

- **Проблема.** `DefaultScenarioValidator.java:24` (`if (scenario.id() == null)`) недостижима: `Scenario`
  гарантирует non-null id (`Scenario.java:30,123`, `ScenarioId` reject blank/null). Мёртвая защитная
  ветка не покрывается тестом и слегка искажает контракт.
- **Что сделать.** Либо убрать проверку (модель уже гарантирует id), либо оставить как defense-in-depth с
  явным комментарием о намеренной недостижимости (учесть влияние на coverage-гейт C-1).
- **Acceptance.** Нет недостижимого непокрытого кода без пометки; решение отражено в комментарии/тесте.

---

## 5. Архитектурные риски — условия для будущих итераций

> Не «исправления здесь», а зафиксированные условия/триггеры, которые должны быть выполнены на
> соответствующих итерациях. Внести в трекинг.

| ID | Риск | Условие/триггер |
|----|------|-----------------|
| R-1 | **Java 24 без `--release`** (план §14) — байткод Java 24 во всех модулях, потребители на JDK 17/21 не загрузят. | Отдельная проектная задача: задать `--release` на LTS-baseline (17/21) **или** обосновать требование JDK 24. **Не в рамках этого плана.** |
| R-2 | **`ForbiddenOperation` без потребителя** — «единый источник истины» с нулём дериваций. | На итерации валидации окружений/AI-схемы `DefaultScenarioValidator` (и `ai-schema`) обязаны деривировать ограничения из enum'а. До тех пор — честный текст package-info (C-7) и anti-drift тест (C-6). |
| R-3 | **Валидацию можно обойти** — ничто в core не форсит validate-before-run. | На итерации раннера: `ScenarioRunner`-реализация обязана сама вызывать `ScenarioValidator` до исполнения. |
| R-4 | **`VariableStore` не потокобезопасен** при параллельных шагах одного сценария. | Зафиксировать контракт «один поток на прогон» (C-9). Если раннер начнёт параллелить шаги внутри сценария — пересмотреть на concurrent-структуру. |
| R-5 | **All-SKIPPED (непустой) прогон → SUCCESS** (`ScenarioResult.deriveStatus`, `StepStatus.isFailure`). | На итерации раннера: гарантировать, что «ничего не выполнилось» не маскируется под зелёный; pass/fail управляется исключениями, статус — reporting-артефакт. |
| R-6 | **Result-модель без `correlationId`** — прямой потребитель `ScenarioResult` не восстановит SDK-owned id (есть `testRunId`, 1:1). | Соответствует плану (диагностика в Allure идёт через reporting-event SPI, события несут `correlationId`). Добавить в result-модель, **только** если появятся прямые потребители результата. |

---

## 6. Намеренно НЕ исправляем (NON_ISSUE)

Зафиксировано, чтобы не возникало повторного «дрейфа ревью». Эти пункты проверены состязательно и
признаны корректным дизайном/осознанным MVP-упрощением:

- **`slf4j-api` отсутствует.** План называет его *допустимой* (не обязательной сейчас) зависимостью;
  логирующего кода нет, `libs.slf4j` нет в каталоге. Подключить **вместе с первым кодом логирования/MDC**,
  не раньше (YAGNI).
- **Смешанные типы исключений** (`IllegalArgumentException` для невалидного имени переменной vs
  `StandTestException` для отсутствующей/неразрешённой) — идиоматично: precondition-нарушение vs
  runtime-условие сценария.
- **`toString()` value-объектов возвращает голое значение** — намеренно для outbound-инъекции
  (REST-заголовок / Kafka-ключ / gRPC-metadata); `equals` между разными record-типами корректно false.
- **`readonly`-по-умолчанию у `DatasourceDefinition`** через явный positional `boolean` — дефолтирование
  отсутствующего ключа принадлежит будущему YAML/config-парсеру, а не value-объекту; явный `boolean`
  форсит решение сильнее, чем молчаливый дефолт.
- **`ScenarioEvent` без `status`** — исход сценария по плану идёт через `ScenarioResult`, а не через
  событие.
- **Фабрики `failed()/timeout()` с пустыми diagnostics** — канонический 7-арг конструктор полностью
  позволяет приложить структурную диагностику; минимализм фабрик — осознанный.
- **`DefaultScenarioValidator`/`InMemoryEnvironmentRegistry`/`NoOpReportingEventPublisher` в core** —
  zero-IO реализации контрактов, соответствуют плану §4/§8.5 и README.

---

## 7. Definition of Done (для всего плана)

- [ ] `./gradlew :stand-test-core:build` зелёный; checkstyle (main + test) без нарушений (`maxWarnings = 0`).
- [ ] JaCoCo подключён; `:stand-test-core` проходит порог ≥ 80 % (C-1) и не ломает скелетные модули.
- [ ] Решение по C-2 принято и реализовано (тип `Environment` **или** задокументированное отклонение).
- [ ] Все добавленные тесты hermetic (нет IO/Testcontainers/sleep), AssertJ + JUnit 5, не flaky.
- [ ] Доки честны: `validation/package-info.java` (C-7) и Javadoc `VariableResolver`/`VariableStore`
      (C-8/C-9) отражают фактическое поведение.
- [ ] Граница ядра не нарушена: повторный импорт-скан `src/main/java` не даёт ничего вне
      `java.*`/`ru.alfa.stand.test.core.*`; нет новых внешних зависимостей.
- [ ] Риски R-1…R-6 внесены в трекинг с привязкой к будущим итерациям.

---

## 8. Порядок и зависимости

1. **Этап 1 (сначала):** **C-1** (JaCoCo) → затем **C-2** (`Environment`). C-1 раньше, чтобы покрытие из
   Этапа 2 сразу мерилось; C-2 — пока сигнатуры не разрослись по `await`/адаптерам (если выбран
   ломающий Вариант A — выполнить до старта зависимых модулей).
2. **Этап 2:** **C-3 → C-4 → C-5 → C-6 → C-7** (тесты независимы, можно параллельно; идут после C-1).
3. **Этап 3 (можно отложить, не блокирует переход к `await`):** **C-8 … C-12**.
4. **Риски R-1…R-6:** не исправляются здесь — фиксируются как условия будущих итераций (R-1 — отдельная
   проектная задача).

> Переход к `stand-test-await` не блокируется этим планом. Рекомендуется закрыть как минимум **C-1** и
> принять решение по **C-2** до того, как модулей станет много.
