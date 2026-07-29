# Current-state analysis: stand-test-framework как основа автономного тест-агента

**Дата анализа:** 2026-07-27 · **Ветка:** `chore/track-claude-harness` · **HEAD:** `e962c94`
**Метод:** чтение исходников, схем, промтов и git-истории. Production-код не изменялся.

Обозначения доказательности по всему документу:
**[F]** — подтверждённый факт (есть ссылка на файл/строку/коммит);
**[A]** — предположение (вывод из косвенных признаков, помечено явно);
**[U]** — неизвестно (данных в репозитории нет).

---

## 1. Executive summary

### 1.1 Главное расхождение с постановкой задачи

Формулировка задачи гласит: «в библиотеке уже реализован workflow, который генерирует тест-кейс
по текстовому описанию и информации из базы знаний». Это верно по **функции**, но неверно по
**носителю**, и это расхождение определяет весь дальнейший анализ:

> **[F] Workflow генерации существует исключительно в виде markdown-промтов, исполняемых внешним
> LLM-агентом (Claude Code / opencode). В репозитории нет ни одной строки кода, которая
> оркестрирует этот workflow, вызывает LLM, читает базу знаний, запускает тест или анализирует
> результат.**

Доказательство (исчерпывающий поиск по всем отслеживаемым файлам):

```
$ git grep -riE 'anthropic|openai|gigachat|yandexgpt|langchain|chat-?model|max_tokens|temperature' \
      -- '*.java' '*.kts' '*.toml' '*.json' '*.yml' '*.properties'
docs/ai-agent/.opencode/opencode.json:      "npm": "@ai-sdk/openai-compatible",
```

Единственное вхождение — строка конфигурации **loader'а opencode**, не кода библиотеки.
`git grep -l 'class .*Agent' -- '*.java'` возвращает пустой результат.

Java-модуль с префиксом `ai` — ровно один, `stand-test-ai-schema`, и его вся «main»-часть это
**один класс-загрузчик ресурсов на 60 строк**
(`stand-test-ai-schema/src/main/java/ru/alfa/stand/test/ai/AiSchemaResources.java:16-60`), который
умеет читать с classpath два файла и больше ничего:

```java
public static String scenarioSchemaJson() { return read(SCHEMA_RESOURCE); }
public static String generationRules()    { return read(GENERATION_RULES_RESOURCE); }
```

Его javadoc это прямо декларирует: *«This is a JDK-only convenience loader — it does not execute
scenarios, parse them, or perform any IO beyond reading its own classpath resources»*
(`AiSchemaResources.java:11-14`).

### 1.2 Что на самом деле есть

Репозиторий состоит из **двух несвязанных по коду артефактов**:

| Артефакт | Носитель | Объём | Зрелость |
|---|---|---|---|
| **`stand-test-sdk`** — тестовый SDK (REST/Kafka/DB/gRPC/await/Allure/Spring) | Java, 14 Gradle-модулей | **~35 800 строк Java** в 333 файлах | Высокая: полный набор тестов, checkstyle zero-tolerance, JaCoCo 80%, ArchUnit-пин графа модулей |
| **AI-authoring kit** — «workflow генерации теста» | Markdown-промты + YAML/JSON схемы | **2 753 строки** промтов (16 skills + 12 commands + 2 rules + 2 workflows), продублированы ×2 | Низкая-средняя: детерминизм только в прозе, ни одного исполняемого гейта |

[F] Замеры: `find <module>/src -name '*.java' | wc -l` по модулям (§2.1) и
`wc -l docs/ai-agent/.claude/{rules,commands,skills,workflows}/**`.

### 1.3 Вердикт

Для цели «автономный агент в стиле Hermes» текущее состояние — это **очень хорошая нижняя половина
стека и полностью отсутствующая верхняя**:

- **Слой инструментов (tools) фактически готов** — но не как tools, а как *SDK для человека*.
  `Scenario`/`StepExecutor`/`ScenarioResult`/`ForbiddenOperation` — это ровно те примитивы,
  из которых собираются агентские tools, с уже реализованными guardrails, correlation, изоляцией
  параллельных прогонов и репортингом.
- **Слой агента отсутствует целиком** — нет планировщика, нет state machine, нет tool-интерфейсов,
  нет контрактов LLM-ответов на уровне кода, нет памяти, нет audit trail, нет бюджетов,
  нет evaluation. Всё это заменено инструкциями в markdown, соблюдение которых обеспечивается
  добросовестностью модели, а не механизмом.
- **Главный архитектурный риск не технический, а эпистемологический:** пайплайн из 11 стадий
  (`docs/ai-agent/.claude/rules/stand-test-pipeline.md:33-48`) объявлен обязательным
  («No stage may be skipped or reordered», «Gates are not advisory»), но **ни одна стадия
  не оставляет машинно-проверяемого следа**. Утверждение «safety review PASS» и утверждение
  «safety review не запускался» физически неразличимы после завершения прогона.

---

## 2. Фактическая карта проекта

### 2.1 Карта модулей

Источник структуры: `settings.gradle.kts:150-165` (список `include`), README модулей,
`stand-test-example/src/test/java/.../ModuleDependencyArchTest.java` (граф пинится ArchUnit-тестом).

| Модуль / пакет | Назначение | Основные классы | Входы | Выходы | Зависимости | Связанность |
|---|---|---|---|---|---|---|
| **`stand-test-core`** (92 файла, 8 644 LOC) | Модель сценария, SPI, валидация, failure-семантика, событийная модель | `Scenario`, `ScenarioStep`, `ScenarioRunner`, `StepExecutor`, `StepExecutionContext`, `VariableStore`, `ScenarioContext`, `DefaultScenarioValidator`, `ForbiddenOperation`, `AssertionMatchers`, `ScenarioResult`/`StepResult`/`StepStatus`, `ReportingEvent`/`ScenarioEvent`/`StepEvent` | `Scenario` (immutable), `EnvironmentRegistry` | `ScenarioResult`, `ReportingEvent`-поток, исключения | **только** `slf4j-api` | Sink графа. Нулевая связанность с адаптерами — диспетчеризация по `ScenarioStep.type()` через SPI |
| **`stand-test-await`** (14 / 1 406) | Единственный санкционированный движок ожидания | `Awaiter`, `AwaitPolicy`, `TimeoutDiagnostics`, `TimeSource` | политика + проба | результат/таймаут с диагностикой | `core` | Низкая |
| **`stand-test-junit`** (15 / 1 138) | Обвязка JUnit 5, инъекция `StandClient` | `@StandTest`, `@StandEnv`, `@StandScenarioId`, `@StandIsolated`/`@StandSerial`/`@StandParallelSafe` | JUnit extension context | `StandClient` в параметре теста | `core`, `await` | Низкая |
| **`stand-test-rest`** (29 / 3 222) | REST-адаптер поверх Spring WebClient | `RestStep`, `RestStepExecutor`, `WebClientHttpCaller`, `PollProbe`, `EnvironmentAuthHeaderResolver`, `ResponseAssertions` | `RestStep` + registry | `StepResult`, captures | `core`, `await`, `spring-webflux`, `json-path` | Средняя (изолирована за `HttpCaller`-seam) |
| **`stand-test-kafka`** (24 / 2 813) | Kafka-адаптер на `kafka-clients` 3.9.2 | `KafkaStep`, `KafkaStepExecutor`, `MessageAssertions` | `KafkaStep` + registry | `StepResult`, диагностика `messagesSeen`/`lastMessages` | `core`, `await`, `kafka-clients` | Средняя |
| **`stand-test-db`** (37 / 4 560) | JDBC-адаптер, seed/cleanup/write + undo-log | `DbStep`, `DbStepExecutor`, `DbValues`, write-guard, undo-log | `DbStep` + registry | `StepResult`, компенсации | `core`, `await`, JDK `java.sql` | Средняя |
| **`stand-test-grpc`** (33 / 2 667) | Unary gRPC через server reflection + `DynamicMessage` | `GrpcStep`, `GrpcStepExecutor`, `DefaultGrpcCallInvoker`, `ManagedChannelResource` | `GrpcStep` + registry | `StepResult` | `core`, `await`, grpc 1.68.1 | Средняя |
| **`stand-test-allure`** (18 / 1 920) | Репортинг: события core → Allure, маскирование секретов | `AllureReportingEventPublisher`, `AllureStepMapper` | `ReportingEvent` | Allure-результаты + attachments | `core`, `allure-java-commons` | Низкая (SPI) |
| **`stand-test-scenario-yaml`** (16 / 2 185) | Два декларативных формата → `Scenario` | `AiScenarioParser`, `YamlScenarioParser`, `AiStepNormalizer`, `{Rest,Kafka,Db,Grpc}StepTranslator`, `YamlStepKeys` | JSON/YAML документ | `Scenario` | **только** `core` + SnakeYAML | ⚠ Высокая *логическая* связанность с адаптерами при нулевой compile-time (см. проблему A-04) |
| **`stand-test-ai-schema`** (10 / 1 502, из них main = 1 файл/60 строк) | JSON Schema + generation rules как ресурсы + **кросс-проверочные тесты** | `AiSchemaResources` (main); `BundleParityTest`, `ForbiddenOperationCoverageTest`, `AssertionMatcherCoverageTest`, `StepMatcherCapabilityCoverageTest`, `GuardrailHoleTest`, `KnowledgeBaseSchemaValidationTest`, `KbCandidateSchemaValidationTest` (test) | схема, KB-файлы, bundle-файлы | pass/fail сборки | `core` (+ networknt/jackson/snakeyaml **test-only**) | Низкая |
| **`stand-test-config`** (8 / 1 124) | Файловый `EnvironmentRegistry` (SPI-провайдер) | `FileEnvironmentRegistry` | `stand-test-environments.yml` | `EnvironmentRegistry` | `core` + SnakeYAML | Низкая |
| **`stand-test-spring-boot-starter`** (7 / 2 000) | Boot-3 автоконфигурация, адаптеры как `compileOnly optional` | автоконфигурация, `@ConfigurationProperties` | `application.yml` `stand.test.*` | бины `StandClient`/registry | все runtime-модули как `compileOnly` | Средняя |
| **`stand-test-example`** (30 / 2 612) | Витрина на офлайн-дублях, **не публикуется** | `FullStandTestFrameworkExampleTest`, `ExampleStand`, `ExampleHttpServer`, `ExampleGrpcServer`, `ExampleH2`, `ModuleDependencyArchTest` | — | зелёные тесты | все модули (test) | — |
| **`stand-test-bom`** | `java-platform`, вне compile-графа | — | — | constraints | — | — |
| **`docs/ai-agent/.claude`** и **`.opencode`** | **AI-authoring kit** — 16 skills, 12 commands, 2 rules, 2 workflows, ×2 копии | markdown | текстовый кейс | markdown-артефакты + Java/YAML файлы | — (не код) | ⚠ Дублирование ×2, зеркалирование wire-ключей SDK |
| **`docs/ai-agent/knowledge-base`** | База знаний: 20 JSON-схем + YAML-записи + staging кандидатов | — | OpenAPI/DOCX/… | YAML-записи | — | ⚠ Зеркало модели `EnvironmentRegistry` без compile-связи |

### 2.2 Публичный API библиотеки

[F] Точки входа для потребителя (не для агента):

- `ru.alfa.stand.test.core.StandClient#run(Scenario)` — единственная точка исполнения;
- `Scenario.builder(id)` + типизированные builder'ы `RestStep`/`KafkaStep`/`DbStep`/`GrpcStep`;
- `@StandTest` / `@Autowired StandClient` — два способа получить `StandClient`;
- `AiScenarioParser#parse(String)` / `YamlScenarioParser` — декларативные форматы;
- `AiSchemaResources.scenarioSchemaJson()` / `.generationRules()` — ресурсы для внешней валидации;
- SPI: `EnvironmentRegistry`, `StepExecutor`, `ReportingEventPublisher`.

### 2.3 Где что физически лежит (для агентской перспективы)

| Категория | Расположение | [F] Комментарий |
|---|---|---|
| **Prompts / system instructions** | `docs/ai-agent/.claude/{skills,commands,rules,workflows}/**` (+ идентичная копия в `.opencode/`) | Ни один не имеет версии; frontmatter содержит только `name` + `description` (проверено: `grep '^version:'` по всему bundle — 0 совпадений) |
| **Templates** | Рядом с каждым SKILL.md: `*-template.md`, `*-template.yml`, `java-test-template.java`, `fixture-template.json` | 24 файла-шаблона |
| **LLM-клиент** | ❌ отсутствует | Хостом является Claude Code / opencode |
| **LLM-конфигурация** | `docs/ai-agent/.opencode/opencode.json:3` — `"model": "alfagen/VIP-EVC-DeepSeek-V4-Flash"`, провайдер `baseURL: https://alfagen.moscow.alfaintra.net/continue-dev`, `apiKey: ""` | Для `.claude`-копии конфигурации модели нет вообще |
| **База знаний** | `docs/ai-agent/knowledge-base/` (контракт + примеры), у потребителя — `knowledge-base/` в корне репо | Формат: YAML, одна коллекция на файл |
| **Механизм поиска контекста** | ❌ кода нет; процедура описана прозой в `stand-test-kb-lookup/SKILL.md:20-80` | Retrieval = агент читает файлы своими Read/Grep |
| **Генерация тест-кейса** | `stand-test-case-analysis` → `stand-test-scenario-design` (markdown-артефакты) | — |
| **Генерация кода** | `stand-test-java-dsl-authoring/SKILL.md` (213 строк) + `java-test-template.java` | — |
| **Компиляция / запуск** | ❌ кода нет; агент вызывает `./gradlew` через Bash-tool по инструкции `stand-test-validate.md:26-45` | — |
| **Allure** | `stand-test-allure` (SPI-подписчик на `ReportingEvent`); потребитель добавляет `allure-junit5:2.29.1` | Реальная интеграция, работает |
| **Обработка ошибок агента** | `stand-test-debugging/SKILL.md` — таблица из 24 сигнатур | Только диагностика в прозе |
| **Повторное выполнение** | `stand-test-pipeline.md:52-53`: «On a failed run: `/stand-test-debug`, then re-enter at 6, or at 5» | ⚠ **Лимита итераций нет нигде** (проверено grep по `max iterations\|attempts\|попыт\|итерац` — 0 совпадений) |
| **Сохранение состояния** | `knowledge-base/mappings/*.yml` (трассировка кейс→тест), `knowledge-base/candidates/promotion-log.yml` (леджер промоутов) | Артефакты процесса, не состояние агента |

### 2.4 Конфигурация сборки и окружения

[F] Из `gradle/libs.versions.toml` и `settings.gradle.kts`:

- **Java:** toolchain 21, компиляция с `--release 17` (`javaRelease = "17"`).
- **Gradle:** wrapper 9.3.0, Kotlin DSL, configuration cache + parallel + build cache включены.
- **Резолюция зависимостей:** только внутренний Artifactory, **публичного fallback нет намеренно**
  (`settings.gradle.kts:1-10`). Требует `binaryPublicRepoUrl`, `artifactoryUrl`,
  `artifactorySnapshotRepo`, `artifactoryUser`, `artifactoryPassword` в Gradle User Home.
- **Spring Boot:** 3.5.14 — только в `stand-test-spring-boot-starter`, как библиотека
  (плагин `org.springframework.boot` не применяется).
- **CI/CD:** ❌ **отсутствует полностью** — в репозитории нет `.gitlab-ci.yml`, `.github/`,
  `Jenkinsfile`, `.teamcity/`. [F] Проверено листингом корня.
- **Публикация:** параметризована (`standTestPublish*` / `STAND_TEST_PUBLISH_*`), реального
  endpoint нет (`docs/publishing.md`).

---

## 3. End-to-end workflow: как он выглядит фактически

### 3.1 Фактическая точка входа

[F] `docs/ai-agent/.claude/commands/stand-test-generate-java-test.md` — слэш-команда
`/stand-test-generate-java-test`. Это **markdown-документ**, который загружается в контекст LLM,
когда пользователь набирает команду в Claude Code. Никакого кода за ней нет.

Порядок стадий продублирован в двух местах и объявлен обязательным:
`rules/stand-test-pipeline.md:33-48` (авто-загружаемое правило) и
`commands/stand-test-generate-java-test.md:21-34`.

### 3.2 Постадийная реконструкция

| # | Этап | Реализован | Компонент (файл) | Вход | Выход | Ошибки | Наблюдаемость |
|---|---|---|---|---|---|---|---|
| 1 | Получение описания | ✅ через UI хоста | — | текст в чате | — | — | история чата хоста |
| 2 | Валидация входа | ⚠ частично, в прозе | `skills/stand-test-case-analysis/SKILL.md` | текст | `TestCaseAnalysis.md` по шаблону | «blocking questions» человеку | markdown-файл, если агент его записал |
| 3 | Определение цели теста | ⚠ прозой | тот же | текст | goal/preconditions/trigger/effects/cleanup | — | тот же файл |
| 4 | Обращение к KB | ⚠ прозой | `skills/stand-test-kb-lookup/SKILL.md:20-80` | analysis + `knowledge-base/**` | `KnowledgeBaseLookupResult` (YAML по шаблону) | `missing` / `conflicts` → вопрос человеку | YAML-файл + проекция в `mappings/` |
| 5 | Формирование контекста | ❌ как механизм | — | — | — | — | ❌ контекст = всё, что агент прочитал; нигде не фиксируется |
| 6 | Выбор prompt/template | ✅ хостом (Skill tool) | frontmatter `description` каждого SKILL.md | триггер-фраза | загруженный промт | ❌ нет | ❌ выбор скилла нигде не логируется |
| 7 | Вызов LLM | ✅ хостом | `opencode.json:3` (для opencode) | контекст | текст | ❌ retry/timeout не настроены | ❌ |
| 8 | Разбор ответа модели | ❌ | — | — | — | — | — |
| 9 | Формирование тест-кейса | ⚠ прозой | `skills/stand-test-scenario-design/SKILL.md` (173 стр.) | analysis + lookup + mapping | `ScenarioDesign.md` | — | markdown |
| 10 | Валидация результата | ⚠ частично **машинно** | JSON Schema (`stand-test-scenario.schema.json`, 324 стр.) + `AiScenarioParser` + `DefaultScenarioValidator` | AI-документ | пусто/исключение | fail-closed | ✅ реальные исключения — **единственный машинный гейт пайплайна** |
| 11 | Сохранение результата | ⚠ прозой | `mappings/*.yml` через `test-case-mapping.schema.json` | — | traceability-запись | — | ✅ файл в репо |
| 12 | Генерация исходного кода | ⚠ прозой | `skills/stand-test-java-dsl-authoring/SKILL.md` (213 стр.) + шаблон | `ScenarioDesign.md` | `.java`-класс + фикстуры | — | файл на диске |
| 13 | Запуск теста | ⚠ прозой | `commands/stand-test-validate.md:26-45` | — | вывод Gradle | «skip ≠ pass» описано прозой | ✅ `build/test-results/test/TEST-*.xml` |
| 14 | Allure-отчёт | ✅ **кодом** | `stand-test-allure` (SPI) | `ReportingEvent` | `allure-results/` | — | ✅ реальный отчёт с маскированием |
| 15 | Обработка неуспеха | ⚠ прозой | `skills/stand-test-debugging/SKILL.md` (24 сигнатуры) | stacktrace + Allure | `DebuggingReport.md` | — | markdown |
| 16 | Повтор/исправление | ⚠ прозой, **без лимита** | `rules/stand-test-pipeline.md:52-53` | — | новая итерация | ❌ **нет счётчика попыток** | ❌ |

**Легенда:** ✅ — реализовано механизмом; ⚠ — существует только как инструкция модели;
❌ — отсутствует.

Итог: **из 16 этапов механизмом реализованы 3** (валидация AI-документа, Allure-репортинг,
JUnit-запуск через Gradle). Остальные 13 — инструкции.

### 3.3 Mermaid sequence diagram (фактическое, не целевое состояние)

```mermaid
sequenceDiagram
    autonumber
    actor QA as QA-инженер
    participant Host as LLM-хост<br/>(Claude Code / opencode)
    participant FS as Файловая система<br/>(skills, KB, репо)
    participant LLM as LLM<br/>(DeepSeek-V4-Flash / Claude)
    participant Gradle as ./gradlew (Bash-tool)
    participant SDK as stand-test-sdk (JVM)
    participant Stand as DEV/IFT стенд

    QA->>Host: /stand-test-generate-java-test + текст кейса
    Note over Host,FS: rules/*.md загружаются автоматически —<br/>единственная гарантированная часть пайплайна

    Host->>FS: read .claude/rules/stand-test-{pipeline,guardrails}.md
    FS-->>Host: 209 строк правил
    Host->>LLM: контекст = правила + кейс

    rect rgb(255, 246, 230)
    Note over LLM,FS: Стадии 1-5 — только чтение/запись markdown.<br/>НЕТ машинной проверки, что стадия выполнена
    LLM->>FS: Skill(stand-test-case-analysis)
    LLM->>FS: write TestCaseAnalysis.md
    LLM->>FS: Skill(stand-test-kb-lookup); read knowledge-base/**
    Note right of FS: retrieval = Read/Grep по YAML.<br/>Индекса нет, ранжирования нет
    LLM->>FS: write KnowledgeBaseLookupResult.yml
    alt есть missing / low confidence
        LLM-->>QA: блокирующие вопросы (соблюдение — на совести модели)
        QA-->>LLM: ответы
    end
    LLM->>FS: Skill(environment-mapping) → Skill(scenario-design)
    LLM->>FS: write ScenarioDesign.md
    end

    rect rgb(230, 245, 255)
    Note over LLM,FS: Стадии 6-8 — генерация артефактов
    LLM->>FS: Skill(java-dsl-authoring) + java-test-template.java
    LLM->>FS: write <Test>.java (+ fixtures)
    LLM->>FS: Skill(safety-review) — 17 grep-паттернов, выполняет сама модель
    Note right of LLM: BLOCK → регенерация.<br/>Гейт = самоконтроль, следа не остаётся
    end

    rect rgb(232, 245, 233)
    Note over Gradle,Stand: Стадии 9-10 — ЕДИНСТВЕННЫЙ машинный контур
    LLM->>Gradle: ./gradlew compileTestJava checkstyleTest
    Gradle-->>LLM: exit code + вывод
    LLM->>Gradle: ./gradlew test --tests '<class>'
    Gradle->>SDK: JUnit 5 → StandClient.run(scenario)
    SDK->>SDK: DefaultScenarioValidator (ForbiddenOperation) — fail-closed
    SDK->>Stand: REST / Kafka / JDBC / gRPC (correlationId инжектится)
    Stand-->>SDK: ответы
    SDK->>FS: ReportingEvent → allure-results/ (с маскированием)
    Gradle-->>LLM: TEST-<class>.xml
    Note right of LLM: skipped="1" ≠ PASS —<br/>различает только сама модель по инструкции
    end

    alt тест упал
        LLM->>FS: Skill(debugging) → DebuggingReport.md
        LLM->>LLM: re-enter на стадию 6 или 5
        Note right of LLM: ⚠ ЛИМИТА ИТЕРАЦИЙ НЕТ
    end

    LLM->>FS: Skill(test-review) → readiness report
    LLM-->>QA: READY / READY-WITH-NOTES / NOT-READY
    QA->>QA: решение о мерже (человек, всегда)
```

---

## 4. Инвентаризация LLM-компонентов

### 4.1 Полный список промтов

[F] 32 промт-файла в каждой из двух копий bundle. Ниже — по назначению; колонки «контракт ответа»
и «self-check» показывают ключевой дефицит.

| Промт | Назначение | Входные переменные | Ожидаемый формат ответа | Машинный контракт | Retry / repair | Тесты контракта |
|---|---|---|---|---|---|---|
| `rules/stand-test-pipeline.md` (78 стр.) | Порядок стадий, авто-загружается | — | — | ❌ | ❌ | ❌ |
| `rules/stand-test-guardrails.md` (131 стр.) | Запреты, авто-загружается | — | — | ⚠ частично: `ForbiddenOperationCoverageTest` пинит перечень к enum | ❌ | ✅ частично |
| `skills/stand-test-case-analysis` (125) | Текст → структура кейса | текст кейса | markdown по шаблону | ❌ | ❌ | ❌ |
| `skills/stand-test-kb-lookup` (103) | Кейс → id записей KB | analysis + KB | YAML по шаблону | ❌ | ❌ | ❌ |
| `skills/stand-test-environment-mapping` (109) | Системы → алиасы registry | analysis + registry | markdown | ❌ | ❌ | ❌ |
| `skills/stand-test-scenario-design` (173) | Analysis → технический дизайн | analysis + lookup | markdown по шаблону | ❌ | ❌ | ❌ |
| `skills/stand-test-java-dsl-authoring` (213) | Дизайн → JUnit-класс | design | `.java` | ⚠ компилятор + checkstyle постфактум | ❌ | ✅ `AuthoringCribApiCoverageTest` пинит crib к API SDK |
| `skills/stand-test-yaml-authoring` (164) | Дизайн → AI-документ | design | JSON/YAML | ✅ **JSON Schema 2020-12** | ❌ | ✅ `ScenarioSchemaValidationTest`, `StepMatcherCapabilityCoverageTest` |
| `skills/stand-test-fixture-authoring` (76) | Фикстуры | design | JSON | ❌ | ❌ | ❌ |
| `skills/stand-test-safety-review` (69) | Adversarial-гейт, 17 находок | все артефакты | markdown-отчёт | ❌ | ❌ | ⚠ `GuardrailHoleTest` |
| `skills/stand-test-test-review` (110) | Quality-гейт | артефакты + кейс | markdown-отчёт | ❌ | ❌ | ❌ |
| `skills/stand-test-debugging` (90) | Падение → диагноз | stacktrace/Allure | markdown-отчёт | ❌ | ❌ | ❌ |
| `skills/stand-test-kb-update` (107) | Спека → записи KB | OpenAPI/proto/SQL | YAML | ✅ `stand-test-knowledge-base.schema.json` | ❌ | ✅ `KnowledgeBaseSchemaValidationTest` |
| `skills/stand-test-spec-ingestion` (92) | Неструктурированный док → кандидаты | PDF/DOCX | YAML-кандидаты | ✅ candidate-схемы | ❌ | ✅ `KbCandidateSchemaValidationTest` |
| `skills/stand-test-spec-extraction` (119) | Правила извлечения + confidence | нормализованный текст | YAML-кандидаты | ✅ те же схемы | ❌ | ✅ |
| `skills/stand-test-kb-candidate-review` (62) | Ревью кандидатов | кандидаты | отчёт | ❌ | ❌ | ❌ |
| `skills/stand-test-kb-candidate-apply` (69) | Промоут в KB | approved кандидаты | KB-записи + леджер | ✅ strict-схема | ❌ | ✅ |
| `skills/stand-test-env-generation` (90) | KB → registry-конфиг | KB | yml/application.yml | ❌ | ❌ | ❌ |
| 12 × `commands/*.md` (41-77 стр.) | Оркестрация цепочек скиллов | — | — | ❌ | ❌ | ❌ |

### 4.2 Оценка по критериям запроса

- **JSON Schema / контракт ответа:** ✅ есть **только** для AI-формата сценария
  (`stand-test-ai-schema/src/main/resources/schema/stand-test-scenario.schema.json`, 324 строки,
  draft 2020-12) и для KB (20 схем). ❌ Отсутствует для *всех* промежуточных артефактов пайплайна:
  `TestCaseAnalysis`, `ScenarioDesign`, safety-отчёт, quality-отчёт, debug-отчёт — свободный
  markdown по шаблону.
- **Защита от невалидного ответа:** ✅ для AI-документа fail-closed на трёх уровнях
  (Schema → `AiScenarioParser` → `DefaultScenarioValidator(scenario, registry)`).
  ❌ Для Java-трека — только компилятор + checkstyle постфактум, семантика не проверяется.
  ❌ Для markdown-артефактов — никакой.
- **Обработка галлюцинаций:** ⚠ единственный механизм — правило «no invented contracts»
  (`guardrails.md`) + требование, чтобы каждая деталь контракта резолвилась в KB
  (`kb-lookup/SKILL.md:82-85`). Это **дисциплинарный**, а не технический механизм: ничто не
  проверяет постфактум, что путь `/api/orders` в сгенерированном тесте присутствует в `matched`.
  Команда прямо признаёт это находкой ревью, а не гарантией
  (`stand-test-generate-java-test.md:54-57`).
- **Контроль размера контекста:** ⚠ единственная мера — «addressed read strategy» для больших KB
  прозой (`kb-lookup/SKILL.md:27-35`): сначала `services/`, затем только файлы совпавших сервисов
  по конвенции `<collection>/<service-id>.yml`. Работоспособно, но не измеряется и не
  форсируется. Token-бюджета нет.
- **Примеры:** ✅ обильно — `example-text-case.md`, `example-kb-lookup-result.yml`,
  `example-scenario-design.md`, `example-generated.java`, `example-generated.yaml`,
  `example-provisioned-prelude.java`, `example-review.md`.
- **Negative examples:** ✅ **сильная сторона** — 12 невалидных примеров в
  `stand-test-ai-schema/src/test/resources/examples/invalid/` (`arbitrary-url`, `destructive-sql`,
  `inline-secret`, `jdbc-url`, `unbounded-timeout`, `script-assertion`, …) плюс 9 невалидных
  KB-файлов и 4 невалидных кандидата — все прогоняются тестами.
- **Self-check:** ✅ есть в виде чеклистов внутри промтов
  (`java-dsl-authoring/SKILL.md:187-202` — 8 пунктов). Выполняет их сама модель, следа нет.
- **Retry / repair prompt:** ❌ отдельного repair-промта нет. Повтор описан как «вернись на
  стадию 6 или 5» без параметризации ошибкой и **без лимита попыток**.
- **Версионирование:** ❌ **полностью отсутствует.** Frontmatter всех 16 skills содержит ровно
  два ключа (`name`, `description`), всех 12 commands — один (`description`).
  `grep '^version:'` по всему bundle → 0 совпадений. Изменение промта неотличимо от его
  отсутствия в любом артефакте.
- **Тесты prompt-контрактов:** ⚠ частично и косвенно — 7 тестов в `stand-test-ai-schema`
  пинят промты к коду: `BundleParityTest` (две копии bundle идентичны),
  `ForbiddenOperationCoverageTest` (таблица правил ↔ enum), `AssertionMatcherCoverageTest`,
  `StepMatcherCapabilityCoverageTest`, `GuardrailHoleTest`, + `AuthoringCribApiCoverageTest`
  в `stand-test-example`. Это **проверка согласованности документации с кодом**, а не проверка
  поведения промта.

### 4.3 Смешение обязанностей в промтах

Проверка на «одна ответственность — один промт». Результат в целом **положительный**:
декомпозиция аккуратная и это сильная сторона кита. Но три файла перегружены:

| Промт | Обязанности в одном файле | Последствие |
|---|---|---|
| `stand-test-java-dsl-authoring/SKILL.md` (213 стр.) | (1) crib по API SDK на 15 строк таблицы, (2) выбор `db.seed` vs `db.write`, (3) 11 hard rules, (4) правила метаданных, (5) code style, (6) self-check из 8 пунктов, (7) выбор обвязки Spring/plain-JUnit | Самый длинный промт кита; изменение API SDK требует правки в 4 местах одного файла. Смешаны «как генерировать» и «как проверять сгенерированное» |
| `rules/stand-test-guardrails.md` (131 стр.) | (1) процесс, (2) 12 hard-constraints, (3) детальная методика DB-write логики на ~40 строк, (4) definition of done | Авто-загружается **в каждый** контекст, включая задачи, не связанные с DB-записью → постоянный расход контекста |
| `stand-test-safety-review/SKILL.md` (69 стр.) | (1) детекция 17 типов находок, (2) severity-модель, (3) процедура, (4) правила эскалации | Приемлемо, но детекция и классификация в одной таблице затрудняют вынесение детекции в код |

**Недетерминированные промты без машинно-проверяемого результата** (главный список):
`case-analysis`, `scenario-design`, `environment-mapping`, `safety-review`, `test-review`,
`debugging`, `kb-lookup`. У всех семи выход — свободный markdown/YAML по шаблону; корректность
проверить нечем.

---

## 5. Анализ базы знаний и retrieval

### 5.1 Физическое устройство

[F] Расположение: `docs/ai-agent/knowledge-base/` (контракт + примеры в SDK-репо);
у потребителя — `knowledge-base/` в **корне репозитория**, не в `src/test/resources`
(`usage-guide.md`, таблица §0), потому что KB читает только агент, SDK её не видит.

Формат: YAML, ровно одна коллекция на файл, 9 типов коллекций
(`services`, `endpoints`, `kafkaTopics`, `datasources`, `dbProbes`, `dbTables`, `grpcTargets`,
`environments`, `testCaseMappings`) + staging-слой `candidates/`.

**Схемы:** 20 JSON Schema draft-2020-12 в `knowledge-base/schema/`, «зонтичная»
`stand-test-knowledge-base.schema.json` + тонкие `$ref`-указатели. Валидируются тестами
`KnowledgeBaseSchemaValidationTest` / `KbCandidateSchemaValidationTest`.

**Фактическое наполнение [F]:** 561 строка YAML во всей курируемой KB.

| Коллекция | Файлов | Реальных записей |
|---|---|---|
| `services/` | 3 | 1 пример + 3 записи `pakt-lgoty` (без endpoints/topics!) + 1 `showcase-mock` |
| `endpoints/` | 2 | 1 пример + **6 реальных** endpoint'ов `showcase-mock` (148 строк — единственный полноценный контракт) |
| `kafka/`, `db/`, `grpc/` | по 1-3 | **только примеры**, ни одной реальной записи |
| `environments/` | 2 | пример + `ift` (одна привязка `showcase-mock` → `SHOWCASE_MOCK_BASE_URL`) |
| `mappings/` | 1 | 1 запись, статус `generated` |

[F] Вывод: **KB — это контракт и демонстрация, а не рабочий корпус.** Единственный сервис
с полным набором endpoint'ов — mock (`showcase-mock.yml`, засеян из живого OpenAPI, коммит
`906fec3`). Реальный бизнес-документ (`ФС ПК ПАКТ.Льготы`) промоутнут **намеренно частично**:
только три идентичности сервисов, без контрактов — комментарий в
`services/pakt-lgoty.yml:4-12` честно фиксирует, что endpoints/topics/datasources остались
`UNRESOLVED` и «never guessed». Это честное поведение системы, но означает, что на реальном
кейсе `kb-lookup` вернёт почти сплошные `missing`.

### 5.2 Retrieval

| Свойство | Состояние | Доказательство |
|---|---|---|
| **Semantic search** | ❌ отсутствует | Нет vector store, нет embeddings, нет соответствующих зависимостей в `libs.versions.toml` |
| **Keyword search** | ⚠ есть, но как **инструкция модели**, не как компонент | `kb-lookup/SKILL.md:39-62`: «case-insensitive match of system names against `services/*.yml` (`id`, `name`, `tags`, `domain`)» — выполняется агентскими Read/Grep |
| **Выбор релевантных документов** | ⚠ прозой, 7-шаговая процедура | `kb-lookup/SKILL.md:38-69`: services → endpoints → kafka → dbProbes → grpc → environment → emit |
| **Версии документов** | ⚠ частично | Есть у *источников*: `source-document.yml` хранит `hash` и версию; у *курируемых записей* версий нет |
| **Актуальность** | ❌ | Нет `validFrom`/`deprecated`/TTL ни в одной схеме |
| **Источники в ответе модели** | ✅ **есть и это сильная сторона** | `KnowledgeBaseLookupResult` содержит `matched` с id записей; проецируется в `mappings/` (`test-case-mapping.schema.json`) — по сгенерированному тесту можно установить, на каких записях он построен |
| **Проверяемость происхождения теста** | ⚠ *в принципе* да, *механически* нет | `mappings/example-test-case-mapping.yml:16-36` показывает готовую форму. Но заполняет её модель; ничто не сверяет содержимое теста с `matched` |
| **Поведение при недостатке контекста** | ✅ **спроектировано корректно** | «No KB entry ⇒ `missing` ⇒ blocking question, never a guess» (`kb-lookup/SKILL.md:8-11, 82-85`); «An empty KB means many questions; that is the correct outcome» (`pipeline.md:60-62`). Отсутствие каталога KB — сама по себе блокирующая находка (`SKILL.md:36-38`) |
| **Отличие найденного факта от предположения** | ✅ **на уровне модели данных — да** | Три раздельные категории в результате: `matched` / `missing` / `assumptions` + `conflicts`; в candidate-слое дополнительно обязательные `provenance` + `confidence` (high/medium/low) — `spec-extraction/SKILL.md` |
| **Защита от prompt injection в документах KB** | ⚠ **частичная и косвенная** | Схемы `additionalProperties: false` + анти-URL-паттерны + `select`-only SQL + ref-shape для секретов делают «командные» поля непредставимыми в *контрактных* полях. Но `description`/`title` — свободные строки, а `candidates/` наполняется из **произвольного PDF/DOCX**. Прямой защиты от инъекции в прозе документа нет; ставка на human review кандидатов |

### 5.3 Передача избыточного контекста

[F] Риск **реален и осознан авторами**. `kb-lookup/SKILL.md:27-35` вводит «addressed read
strategy», обязательную начиная с ~10 сервисов, и предупреждает: на большой KB читать всё нельзя.
Однако:

1. Стратегия зависит от **конвенции именования файлов** (`<collection>/<service-id>.yml`),
   которая не проверяется никаким тестом — файл с другим именем нужно «сканировать целиком»
   (сам промт это допускает: «do not skip them silently»).
2. Помимо KB в контекст **безусловно** грузятся 209 строк правил
   (`pipeline.md` + `guardrails.md`) при **любом** обращении, включая не связанные с генерацией.
3. Промты крупные: `java-dsl-authoring` 213 строк, `scenario-design` 173, `yaml-authoring` 164.
   Полный проход пайплайна загружает ≥ 800-1000 строк только промтов, плюс шаблоны, плюс KB,
   плюс исходники потребителя.

[A] Предположение (проверке в репозитории не поддаётся): на KB реального масштаба (десятки
сервисов) сквозной прогон упрётся в контекст раньше, чем в качество генерации.

---

## 6. Анализ модели тест-кейса

### 6.1 Три разных «тест-кейса» в системе

Это ключевое наблюдение раздела: единой модели тест-кейса **нет**, есть три несвязанных
представления с разной степенью формальности.

| Представление | Носитель | Формальность | Кто потребляет |
|---|---|---|---|
| **A. `TestCaseAnalysis.md`** | markdown по шаблону `test-case-analysis-template.md` | ❌ свободный текст | только LLM |
| **B. `ScenarioDesign.md`** | markdown по шаблону `scenario-design-template.md` | ❌ свободный текст | только LLM |
| **C. Исполняемый сценарий** | Java DSL (`Scenario`) **или** AI-документ (JSON/YAML) | ✅ типизированная модель / JSON Schema | SDK-рантайм |
| **D. `testCaseMapping`** | YAML по `test-case-mapping.schema.json` | ✅ схема | трассировка |

### 6.2 Наличие атрибутов

| Атрибут | A (analysis) | C (Java DSL / AI-док) | D (mapping) |
|---|---|---|---|
| Идентификатор | прозой `caseId` | ✅ `Scenario.builder("<scenarioId>")` / `ScenarioId` | ✅ `caseId` |
| Название | прозой | ⚠ `.title(...)` **существует, но ни один репортер его не читает** — см. ниже | ✅ `title` |
| Цель | прозой | ❌ | ❌ |
| Предусловия | прозой | ⚠ как шаги (`db.seed`/provision-prelude) | ❌ |
| Тестовые данные | прозой | ✅ параметры шагов, `${testRunId}`-скоуп | ❌ |
| Шаги | прозой | ✅ `List<ScenarioStep>`, упорядоченный, типизированный | ❌ |
| Ожидаемые результаты | прозой | ✅ assertion-матчеры (5 типов на REST/gRPC, equals-only на Kafka) | ❌ |
| Postconditions / cleanup | прозой | ✅ `db.cleanup` + `cleanupPolicy` (`ON_FAILURE`/`ALWAYS`/`NEVER`) + undo-log | ❌ |
| Негативные сценарии | прозой | ✅ через `assertThatThrownBy` | ❌ |
| Приоритет | ❌ | ❌ | ❌ |
| Tags | прозой | ✅ `ScenarioContext.tags` | ❌ |
| Links | прозой | ❌ (только javadoc-комментарий) | ✅ `source.ref` |
| Requirement IDs | ❌ | ❌ | ⚠ через `source` |
| Traceability | ❌ | ❌ | ✅ **`matched.{services,endpoints,kafkaTopics,datasources,dbProbes,grpcTargets,grpcMethods}`** |
| Источник требований | ❌ | ❌ | ✅ `source.{type,ref}` |
| Критерии применимости | прозой | ⚠ `@EnabledIfEnvironmentVariable` (гейт по env) | ✅ `environment` |
| Ожидаемый oracle | прозой | ✅ матчеры + `expectStatus` + `expectValue` | ❌ |

[F] **Важная деталь про метаданные:** `Scenario.builder(...)` принимает `.title(...)` и
`.description(...)`, шаги несут `description` — но **ни один репортер их не читает**.
`ScenarioEvent`/`StepEvent` этих полей не несут, поэтому Allure-sink их не видит; в отчёт попадает
только JUnit-`@DisplayName`. Это задокументировано и промт прямо запрещает их использовать
(`java-dsl-authoring/SKILL.md:165-177`). То есть **в исполняемой модели поле «название кейса»
де-факто мёртвое** — коммит `2f25465` («stop the review demanding scenario metadata nothing reads»)
это зафиксировал.

### 6.3 Ответы на проверочные вопросы

| Вопрос | Ответ | Обоснование |
|---|---|---|
| Типизированная модель или свободный текст? | **Раздвоено**: промежуточные артефакты (A, B) — свободный текст; исполняемый (C) — строго типизированная immutable-модель | §6.1 |
| Можно ли выполнить автоматически? | ✅ да, представление C | `StandClient.run(scenario)` |
| Можно ли проверить полноту? | ❌ **нет** | Никакого сопоставления «шаги из кейса ⊆ шаги в тесте». `test-review/SKILL.md` требует «coverage vs the original case», но это ручная сверка модели с собой |
| Можно ли определить дубликат? | ❌ **нет** | Нет ни хэша сценария, ни нормальной формы, ни индекса по `matched` |
| Можно ли связать тест с требованиями? | ⚠ **частично** | Только через `mappings/*.yml` (`source.ref`), заполняемый моделью; из самого теста ссылки нет |
| Можно ли преобразовать в другой test framework? | ⚠ **теоретически да** | Модель `Scenario` генерик-типизирована по `ScenarioStep.type()` и не зависит от JUnit; но кодогенератор один — JUnit 5 |
| Можно ли переиспользовать отдельные шаги? | ❌ **нет** | Нет библиотеки шагов, нет именованных фрагментов. `dbProbes` в KB — ближайший аналог (переиспользуемая SQL-проба), но только для DB |

---

## 7. Анализ выполнения тестов

### 7.1 Что умеет текущая система

| Способность | Кто это делает | Реализовано |
|---|---|---|
| Только генерировать описание | LLM | ✅ |
| Генерировать исходный код | LLM по промту | ✅ |
| **Компилировать тест** | LLM вызывает `./gradlew compileTestJava` через Bash-tool | ⚠ **есть, но как инструкция**: `stand-test-java.md:38-39` |
| **Запускать тест** | LLM вызывает `./gradlew test --tests '<class>'` | ⚠ то же: `stand-test-validate.md:28` |
| Получать stdout/stderr | Bash-tool хоста | ✅ (возможность хоста, не библиотеки) |
| Получать stack trace | из вывода Gradle | ✅ |
| **Читать результаты test runner** | ⚠ **прозой** — «Read the count out of `build/test-results/test/TEST-<class>.xml` and require `tests="1" skipped="0"`» (`stand-test-validate.md:36-38`) | Парсера нет |
| Читать Allure results | ⚠ прозой (`debugging/SKILL.md:72`) | Парсера нет |
| **Определять причину падения** | `debugging/SKILL.md` — таблица из **24 сигнатур сообщений** | ⚠ качественная, но текстовая |
| **Отличать ошибку теста от дефекта системы** | ✅ **архитектурно решено на уровне SDK** | `StandTestAssertionError` (extends `AssertionError`) vs `StandTestException` (extends `RuntimeException`) — `debugging/SKILL.md:28-33`. Это сильная сторона |
| Исправлять тест | LLM | ⚠ прозой |
| Перезапускать только изменённый тест | `--tests '<class>'` | ✅ |
| **Ограничивать количество попыток** | ❌ **НЕТ** | grep по `max iterations/attempts/retries/попыт/итерац` во всём bundle → **0 совпадений** |
| **Завершать цикл при отсутствии прогресса** | ❌ **НЕТ** | Никакого критерия «нет прогресса» не определено |

### 7.2 Фактические зависимости тестовых технологий

[F] Из `gradle/libs.versions.toml` (проверено построчно, без предположений):

| Технология | Присутствует | Версия / роль |
|---|---|---|
| **JUnit 5** | ✅ | 5.11.4 — платформа SDK; `junit-platform-testkit` для тестов расширений |
| **AssertJ** | ✅ | 3.26.3 — единственный разрешённый assertion-API (JUnit `Assertions` — **banned import** в checkstyle) |
| **Allure** | ✅ | `allure-java-commons` 2.29.1 (mapping-модуль); `allure-junit5` — на стороне потребителя, намеренно не зависимость |
| **ArchUnit** | ✅ | 1.3.0, **test-only** — пинит граф модулей |
| **H2** | ✅ | 2.3.232, **test-only** — офлайн-дубль БД |
| **Kafka clients** | ✅ | 3.9.2 (пин на 3.x ради `MockConsumer`) |
| **gRPC** | ✅ | 1.68.1 + protobuf 3.25.5, `grpc-inprocess` test-only |
| **Spring WebFlux** | ✅ | 6.2.8 (WebClient на JDK-коннекторе, **без reactor-netty**) |
| **TestNG** | ❌ | отсутствует |
| **Cucumber** | ❌ | отсутствует |
| **REST Assured** | ❌ | отсутствует (заменён `stand-test-rest`) |
| **Selenide / Selenium / Playwright** | ❌ | отсутствуют — UI-тестирование вне скоупа |
| **WireMock** | ❌ | отсутствует; вместо него собственные офлайн-дубли (`ExampleHttpServer` на JDK `HttpServer`) |
| **Testcontainers** | ❌ | **отсутствует намеренно** — CLAUDE.md: «Testcontainers is explicitly *not* the basis»; тесты идут против реальных DEV/IFT-стендов |

---

## 8. Архитектурные проблемы

Severity: **Critical** — блокирует цель (агент) или создаёт риск безопасности;
**High** — существенно ограничивает; **Medium** — сопровождаемость; **Low** — стиль.

| ID | Проблема | Доказательство | Последствие | Severity | Направление |
|---|---|---|---|---|---|
| **A-01** | **Bundle агента содержит MCP-подключения к продуктивным БД и абсолютные пути одной машины разработчика** | `docs/ai-agent/.opencode/opencode.json:92-142`: серверы `postgres_prodcat`, `postgres_prodprofile_u`, `postgres_designer` с `--env-file /Users/alfa/IdeaProjects/ALFA/stand-test-framework/docs/ai-agent/.opencode/.env_*`; `jetbrains`-сервер с путями к локальной IDEA. Файл входит в «deployable bundle», который README предписывает копировать потребителю (`README.md:73-78`) | (1) Прямое противоречие собственному guardrail «No production environments in any test registry» (`guardrails.md`); (2) агенту доступен `execute_sql` на прод-БД; (3) у любого потребителя, кроме автора, bundle не работает (пути чужие) | **Critical** | Вынести MCP/провайдер/пути в `env.template`+локальный оверрайд; `opencode.json` в bundle — только модель-агностичный скелет; добавить тест на отсутствие абсолютных путей и prod-имён |
| **A-02** | **Секреты уже утекли в git-историю, из bundle-каталога** | Коммит `58282f2` (сообщение цитирует: «`.env_designer` blob carried a live DEV Postgres password (designer@tksdev3psg1)… That account must be treated as compromised… the blobs remain reachable in history on master and feat/stand-test-rest until it is rewritten»). Добавлены коммитом `ac2baea` | Учётная запись скомпрометирована; история не переписана — доступна всем, у кого есть клон | **Critical** | Ротация учётки (если не сделана — [U]); решение по rewrite истории; запрет на хранение путей к env-файлам внутри shippable-артефакта |
| **A-03** | **Ни одна стадия пайплайна не оставляет машинно-проверяемого следа** | `pipeline.md:30-31` объявляет стадии обязательными, `:70-72` — гейты не совещательными, `:74-78` — требует честного отчёта. Но выход всех стадий, кроме AI-схемы, — свободный markdown; ни `mappings/`, ни какой-либо иной артефакт не фиксирует «safety-review запускался и вернул PASS» | «READY» невозможно верифицировать. Раздел «Reporting honestly» — признание, что режим отказа известен и не закрыт | **Critical** | Ввести машинный `RunManifest` (JSON) со списком стадий, их входами/выходами и хэшами; гейт валидации = проверка манифеста |
| **A-04** | **Wire-ключи адаптеров зеркалируются в `scenario-yaml` без compile-time связи** | `stand-test-scenario-yaml` зависит **только** от `core` (CLAUDE.md, §модульный граф); `RestStepTranslator`/`KafkaStepTranslator`/`DbStepTranslator`/`GrpcStepTranslator` формируют строковые параметры, которые адаптеры читают по своим ключам. Частично закрыто хойстом ключей в `StepParameterKeys` | Переименование ключа в адаптере ломает YAML-трек молча, до рантайма | **High** | Тест-контракт «каждый ключ, который пишет транслятор, читается адаптером» (аналогично `AuthoringCribApiCoverageTest`) |
| **A-05** | **Цикл repair не ограничен ни числом итераций, ни бюджетом, ни критерием прогресса** | grep по bundle: 0 совпадений `max iterations/attempts/retries`. `pipeline.md:52-53` описывает повтор без границ | Возможен бесконечный цикл «сгенерировал → упало → переписал»; неограниченный расход токенов и времени; риск «зелёного любой ценой» | **High** | Явный `maxRepairIterations` (2-3) + критерий прогресса (номер падающего шага/тип ошибки должен меняться) + hard stop с отчётом |
| **A-06** | **Отсутствует token/cost/execution budget** | Ни в `opencode.json`, ни в промтах нет `max_tokens`, лимитов стоимости, таймаутов агента | Неконтролируемая стоимость прогона; невозможно планировать масштабирование | **High** | Бюджет на прогон, деградация (сузить KB-чтение) при приближении к лимиту |
| **A-07** | **Промты не версионированы** | frontmatter: 16 skills → `{name, description}`, 12 commands → `{description}`; `grep '^version:'` → 0 | Невозможно связать сгенерированный тест с версией промта; регрессия качества промта необнаружима; A/B-сравнение невозможно | **High** | `version` в frontmatter + запись версий каждого использованного промта в `RunManifest`/`mappings` |
| **A-08** | **Отсутствует offline evaluation промтов и пайплайна** | В `stand-test-ai-schema` есть тесты **согласованности** (схема↔enum, копия↔копия), но нет ни одного теста «на кейсе X пайплайн даёт результат Y». Каталог golden-кейсов отсутствует | Невозможно измерить, стало лучше или хуже после правки промта; всё качество — анекдотическое | **High** | Корпус из 5-10 golden-кейсов + офлайн-прогон с проверяемыми инвариантами (какие KB-записи должны быть в `matched`, какие шаги в сценарии) |
| **A-09** | **Полное дублирование bundle ×2 (`.claude` / `.opencode`)** | 217 файлов в `docs/ai-agent`, из них ~65 промтов ×2. `BundleParityTest` (`stand-test-ai-schema/src/test/java/.../BundleParityTest.java:30-129`) существует **именно потому**, что копии уже разъезжались: javadoc фиксирует «the gRPC matcher set came to be current in one copy and stale in the other for a whole release» | Двойная стоимость каждого изменения; тест ловит расхождение, но не устраняет причину | **Medium** | Один источник + генерация второй копии Gradle-таском (тест тогда проверяет свежесть генерации) |
| **A-10** | **`opencode.json` исключён из проверки паритета** | `BundleParityTest.java:33` — `OPENCODE_ONLY = {AGENTS.md, opencode.json, env.template}`; для них не проверяется ничего | Именно самый опасный файл bundle (A-01) не покрыт ни одной проверкой | **Medium** | Отдельный тест на `opencode.json`: запрет абсолютных путей, prod-имён, непустых `apiKey` |
| **A-11** | **`guardrails.md` (131 стр.) авто-загружается в каждый контекст** | `pipeline.md:18` — «These rules · `.claude/rules/*.md` · auto-loaded as project instructions»; в файле ~40 строк посвящены исключительно методике DB-write | Постоянный расход контекста на инструкции, нерелевантные большинству задач | **Medium** | Разделить: «hard constraints» (короткий, авто) vs «DB-write методика» (skill, по требованию) |
| **A-12** | **Между стадиями передаётся неструктурированный текст** | Выходы стадий 1-5 — markdown по шаблону; `ScenarioDesign.md` — вход стадии 6 (`stand-test-java.md:11`) | Ошибка на ранней стадии не детектируется, а транслируется дальше; невозможна автоматическая проверка «дизайн ⊆ анализ» | **Medium** | Схематизировать `TestCaseAnalysis`/`ScenarioDesign` (JSON Schema, как уже сделано для AI-сценария) |
| **A-13** | **Нет CI/CD** | В репозитории отсутствуют `.gitlab-ci.yml`, `.github/`, `Jenkinsfile`, `.teamcity/` | Все тесты-инварианты (paritет bundle, KB-схемы, guardrail-покрытие) выполняются только вручную; для агентской системы, где эти тесты — единственная защита от дрейфа, это критично по последствиям | **Medium** | Пайплайн `./gradlew build` на MR + прогон KB-валидации |
| **A-14** | **Нет idempotency и correlation ID у самого агента** | SDK имеет образцовые `scenarioId`/`testRunId`/`correlationId` (`ScenarioResult.java:28-35`), но у **прогона агента** идентификатора нет | Два прогона на одном кейсе неразличимы; невозможно связать артефакты одного прогона | **Medium** | `agentRunId`, проставляемый во все артефакты прогона |
| **A-15** | **Локальные разрешения харнесса допускают `execute_sql` на прод-БД без подтверждения** | `.claude/settings.local.json` (untracked): в `permissions.allow` — `mcp__postgres_prodprofile_u__execute_sql`, `mcp__postgres_prodprofile_u__list_tables`, а также `Bash(docker run *)`, `Bash(PGPASSWORD=postgres psql *)` | Approval gate обойдён на уровне конфигурации у конкретного разработчика; при переносе практики в bundle станет системным | **High** (для практики) / **Low** (для репозитория — файл не отслеживается) | Политика: read-only MCP к прод-источникам, `execute_sql` — всегда `ask` |
| **A-16** | **`god prompt` в authoring-скилле** | `java-dsl-authoring/SKILL.md` — 213 строк, 7 разнородных обязанностей (§4.3) | Изменение API SDK требует синхронной правки в нескольких местах одного файла; риск частичного обновления | **Low** | Вынести crib API в отдельный генерируемый из кода файл (`AuthoringCribApiCoverageTest` уже намекает на такую возможность) |
| **A-17** | **Нет cancellation** | Ни в промтах, ни в конфигурации нет механизма прерывания прогона | Долгий/зациклившийся прогон останавливается только человеком через UI хоста | **Low** | Следствие A-05/A-06; закрывается вместе с ними |

### 8.1 Проблемы, которых **нет** (проверено, не подтвердилось)

Честности ради — по нескольким пунктам чеклиста запроса претензий предъявить нельзя:

- **God service в коде** — ❌ не найдено. Крупнейший модуль `core` — 92 файла/8 644 строки,
  средний файл ~94 строки; декомпозиция по пакетам (`scenario`, `step`, `result`, `event`,
  `validation`, `assertion`, `variable`, `environment`, `identifier`, `context`) чистая.
- **Смешение orchestration и domain logic в коде** — ❌ разделено: `ScenarioRunner` (оркестрация)
  ↔ `StepExecutor` SPI (домен адаптера) ↔ `Scenario` (модель).
- **Жёсткая связанность с конкретным LLM-провайдером** — ❌ **в коде связанности нет вообще**
  (кода нет). В `opencode.json` провайдер захардкожен, но это конфиг loader'а, заменяемый одной
  строкой.
- **Жёсткая связанность с конкретной БЗ** — ⚠ спорно: KB — файлы+схемы, замена хранилища потребует
  переписать процедуру в `kb-lookup`, но не код.
- **Неконтролируемое изменение файлов** — ⚠ частично закрыто: `opencode.json:37-53` запрещает
  правку `checkstyle.xml`, `.gitignore`, `.env*`; `rm -rf` — `deny`.
- **Отсутствие timeout у шагов теста** — ❌ **наоборот, образцово**: `UNBOUNDED_TIMEOUT` —
  элемент `ForbiddenOperation`, схема ограничивает грамматику (`≤99999ms/≤999s/≤60m`),
  gRPC deadline обязателен.

---

## 9. Оценка зрелости

Шкала 0-5: **0** — отсутствует; **1** — есть намерение/документ; **2** — работает вручную;
**3** — работает, но не проверяется механизмом; **4** — механизм + тесты; **5** — production-grade
с метриками.

| Аспект | Оценка | Доказательство | Что нужно для следующего уровня |
|---|---|---|---|
| **Структурированность входных данных** | **2** | Вход — свободный текст. Есть спецификация «как писать кейс» (`example-test-case-specification.md`) и шаблон анализа, но контракта нет; `case-analysis` призван «допрашивать» сырой кейс (`example-text-case.md` — намеренно неоднозначный) | JSON Schema на `TestCaseAnalysis`; валидация до входа в пайплайн |
| **Retrieval** | **2** | Файловый YAML + процедура прозой (`kb-lookup/SKILL.md:38-69`); ни индекса, ни ранжирования, ни семантики. KB содержит 561 строку — корпуса нет | Индекс по `id`/`tags`/`domain` + детерминированный резолвер как код (а не как инструкция) |
| **Prompt engineering** | **4** | Сильнейшая часть кита: 32 промта, чистая декомпозиция по стадиям, шаблоны, worked examples, negative examples, чеклисты, явные Forbidden-разделы, честное «Reporting honestly» | Версионирование (A-07), разгрузка `guardrails.md` (A-11), декомпозиция `java-dsl-authoring` (A-16) |
| **Контракты LLM-ответов** | **2** | ✅ строгий контракт **только** для AI-формата (JSON Schema 324 стр. + parser + validator, fail-closed) и KB (20 схем). ❌ Для 7 из 9 промежуточных артефактов контракта нет | Схематизировать analysis/design/отчёты гейтов (A-12) |
| **Генерация тест-кейсов** | **3** | Работает и даёт содержательный результат; ограничена качеством KB. Модель кейса раздвоена (§6.1); полноту и дубликаты проверить нечем | Единая типизированная модель кейса + проверка покрытия «шаги кейса ⊆ шаги теста» |
| **Генерация кода** | **3** | Промт на 213 строк + шаблон + два worked example + crib, запинённый к реальному API (`AuthoringCribApiCoverageTest`). Проверка — компилятор постфактум | Семантическая проверка «каждая деталь контракта ∈ `matched`» как код |
| **Выполнение тестов** | **3** | Реальный, работающий контур: JUnit 5 + Gradle + Allure + skip-gate. Но вызывается инструкцией, а результат парсится глазами модели | Tool `run_test(class) → {status, skipped, failures[], stacktrace}` вместо прозы |
| **Анализ ошибок** | **3** | 24 сигнатуры сообщений в таблице; архитектурное разделение assertion/infra на уровне типов исключений; богатая диагностика (`TimeoutDiagnostics`, `kafka.messagesSeen`/`lastMessages`, REST `http.*`) | Классификатор как код поверх структурированного результата |
| **Self-correction** | **1** | Есть только указание «вернись на стадию 6 или 5» без лимита, без критерия прогресса, без repair-промта, без счётчика | Ограниченный цикл (A-05) + repair-промт, параметризованный классифицированной ошибкой |
| **Память** | **1** | Персистентны только `mappings/*.yml` (кейс→тест) и `candidates/promotion-log.yml`. Памяти агента (что уже пробовали, что не сработало) нет | Run memory (артефакты прогона) + project memory (накопленные решения по проекту) |
| **Skills** | **2** | 16 «skills» — это **статические промты**, а не накопленные процедуры: не создаются из успешных прогонов, не версионируются, не имеют метрик применимости | Механизм фиксации успешной процедуры как переиспользуемого артефакта |
| **Tool abstraction** | **1** | Инструментов **нет**. Всё, что делает агент, — Read/Write/Grep/Bash хоста. При этом SDK предоставляет идеальные кандидаты в tools (`StandClient.run`, `DefaultScenarioValidator.validate`, `AiScenarioParser.parse`, `EnvironmentRegistry`) — они просто не обёрнуты | Тонкий слой типизированных tools поверх существующих API SDK |
| **Безопасность** | **2** | ✅ Модель guardrails **очень сильная**: `ForbiddenOperation` как единый источник истины, схема + рантайм-валидатор + review, fail-closed на seed-tagging и kafka-дискриминаторе, маскирование в Allure, ref-only секреты. ❌ Но у самого **агента**: прод-БД в bundle (A-01), утечка в истории (A-02), `execute_sql` в allow-листе (A-15) | Применить к агенту собственные guardrails: «no production environments» должно распространяться на MCP-конфигурацию |
| **Observability** | **2** | ✅ У **выполнения теста** — хорошая: SLF4J+MDC, `Step [i/total] 'id' (type)` в логах и в исключении, Allure с attachments, диагностические payload'ы. ❌ У **агента** — нулевая: ни run id, ни trace решений, ни лога выбора скиллов, ни записи «какие файлы KB прочитаны» | Execution trace прогона агента как обязательный артефакт |
| **Evaluation** | **1** | Есть 7 тестов-инвариантов (схема↔enum, паритет копий, покрытие матчеров) — это проверка **согласованности артефактов**, не качества генерации. Golden-кейсов нет | Корпус golden-кейсов + офлайн-прогон + метрики (доля `missing`, доля прошедших safety, доля скомпилировавшихся) |
| **Production readiness** | **1** | Нет CI (A-13), нет публикации (endpoint отсутствует), bundle не переносим (A-01), лимитов нет (A-05/A-06), следа нет (A-03). При этом **сам SDK** production-ready на 4 | Отдельно оценивать SDK (4) и агента (1) — это разные продукты в одном репозитории |

**Средняя оценка агентской части: 2,0 / 5.** Средняя оценка SDK как библиотеки: **4,0 / 5** [A]
(на основе покрытия тестами, ArchUnit-пинов, zero-tolerance checkstyle, JaCoCo-гейта; не оценивалось
формально, поскольку это вне запроса).

---

## 10. Gap analysis относительно целевого агента

| Возможность | Уже существует | Частично | Отсутствует | Что переиспользовать | Что мешает |
|---|---|---|---|---|---|
| **Test Intent Interpreter** | — | ✅ `stand-test-case-analysis` (125 стр. промта + шаблон + worked example) | — | Промт и шаблон — готовая спецификация поведения | Выход — свободный markdown; нет схемы, нет валидации, нет теста |
| **Requirement & Knowledge Retriever** | — | ✅ `stand-test-kb-lookup` + 20 JSON-схем + модель `matched/missing/assumptions/conflicts` | — | **Модель результата — лучшее, что есть**: разделение факта и предположения уже спроектировано | Нет реализации: процедура прозой, KB пуста (561 строка), нет индекса |
| **Context Builder** | — | — | ❌ **отсутствует** | «Addressed read strategy» из `kb-lookup/SKILL.md:27-35` как алгоритм | Контекст нигде не материализуется; невозможно узнать, что видела модель |
| **Test Planner** | — | ✅ `stand-test-scenario-design` (173 стр.) — выбор трека, порядок шагов, captures, awaits, cleanup | — | Правила планирования сформулированы детально | Нет типизированного `TestPlan`; план = markdown |
| **Test Case Generator** | — | ✅ два трека (Java DSL / AI-формат) | — | Промты + шаблоны + примеры | См. Test Planner |
| **Test Code Generator** | — | ✅ `java-dsl-authoring` + `java-test-template.java` + `AuthoringCribApiCoverageTest` | — | Crib, запинённый к реальному API SDK — редкая по качеству вещь | Промт-only; семантической проверки нет |
| **Static Validator** | ✅ **ДА, для AI-формата** | — | — | **`DefaultScenarioValidator` + `ForbiddenOperation` + JSON Schema + `AiScenarioParser`** — полностью готовый компонент, fail-closed, покрыт тестами | Для Java-трека — только компилятор+checkstyle; guardrail-проверка Java-исходника существует лишь как 17 grep-паттернов в промте |
| **Test Runner Tools** | — | ⚠ `./gradlew` через Bash + skip-gate | — | Реально работающий контур JUnit/Gradle | Не обёрнут в tool; вызов и разбор — прозой |
| **Result Collector** | ✅ **ДА, внутри SDK** | — | — | **`ScenarioResult`/`StepResult`/`StepStatus` + `ReportingEvent`-поток + Allure-sink + `TimeoutDiagnostics`** | Не доходит до агента структурно: агент видит текстовый вывод Gradle, а не `ScenarioResult` |
| **Failure Classifier** | — | ✅ `debugging/SKILL.md` — 24 сигнатуры + 7 корневых причин + разделение assertion/infra на уровне типов | — | **Таблица сигнатур — готовая спецификация классификатора**; типы исключений уже разделяют «дефект системы» и «проблема теста» | Классификация выполняется моделью по тексту, а не кодом по структуре |
| **Test Repair Loop** | — | ⚠ только «re-enter at 6 or 5» | — | — | ❌ Нет лимита, нет критерия прогресса, нет repair-промта, нет счётчика (A-05) |
| **Human Approval Gate** | — | ✅ **спроектирован тщательно** — блокирующие точки в 4 командах; «The human merges» (`pipeline.md:72`); KB-изменения человеком; `dry-run` по умолчанию у kb-update/ingest/apply | — | Модель approval-точек полностью описана | Соблюдение не форсируется; `settings.local.json` показывает, как allow-лист обходит гейты (A-15) |
| **Run Memory** | — | ⚠ `mappings/*.yml` (кейс→тест→статус) | — | Схема `test-case-mapping.schema.json` — каркас | Нет ни `agentRunId`, ни истории попыток, ни списка прочитанного |
| **Project Memory** | — | ⚠ KB + `promotion-log.yml` (леджер промоутов с обратной ссылкой на документ) | — | KB-схемы, provenance/confidence-модель кандидатов | KB — память *о системе*, не *о работе агента*; накопления «что сработало» нет |
| **Procedural Skills** | — | ⚠ 16 статических skills | — | Формат skills (frontmatter+тело+шаблоны) годится как контейнер | Skills пишутся людьми, не выводятся из успешных прогонов; нет версий, нет метрик |
| **Evaluation Engine** | — | ⚠ 7 тестов-инвариантов согласованности | — | `stand-test-ai-schema` как площадка (уже держит невалидные примеры и валидацию) | Нет golden-кейсов, нет метрик качества генерации (A-08) |
| **Audit & Observability** | — | ⚠ SLF4J+MDC и Allure — **у теста**; `promotion-log.yml` — у KB | ❌ у агента | MDC-паттерн `Step [i/total]` и Allure-attachments — образец для агентского trace | Ни одного артефакта, фиксирующего решения агента (A-03, A-14) |

**Сводка:** из 17 целевых возможностей **2 существуют полноценно** (Static Validator для
AI-формата, Result Collector внутри SDK), **13 существуют как промты/частично**,
**2 отсутствуют полностью** (Context Builder, Test Repair Loop как механизм).

---

## 11. Keep / Refactor / Replace / Remove / Investigate

| Компонент | Решение | Обоснование | Риск | Зависимости |
|---|---|---|---|---|
| `stand-test-core` (модель, SPI, `ForbiddenOperation`, `ScenarioResult`, события) | **Keep** | Полноценный фундамент tool-слоя: immutable-модель, fail-closed валидация, разделение assertion/infra, изоляция параллельных прогонов. Менять нечего | Изменение ради агента дестабилизирует SDK для существующих потребителей | Все модули |
| Адаптеры `rest`/`kafka`/`db`/`grpc`/`await` | **Keep** | Работают, покрыты тестами, ArchUnit пинит граф | — | `core`, `await` |
| `stand-test-allure` | **Keep** | Готовый Result Collector с маскированием | — | `core` |
| `stand-test-ai-schema` (схема + generation rules + 7 тестов-инвариантов) | **Keep + расширить** | Единственное место, где промты пинятся к коду. Естественная площадка для evaluation-корпуса | Расширение не должно превратить его в «модуль всего» | `core` |
| `DefaultScenarioValidator` + `ForbiddenOperation` | **Keep** — и **вынести как tool** | Готовый Static Validator; уже вызывается из `run()` | При обёртке в tool не потерять двухаргументную форму `validate(scenario, registry)` — одноаргументная не проверяет guardrails | `core` |
| `stand-test-scenario-yaml` (трансляторы) | **Refactor** | Зеркалирование wire-ключей без compile-связи (A-04) | Правка транслятора без правки адаптера ломает YAML-трек молча | `core` + (логически) адаптеры |
| Промты стадий 1-5 (`case-analysis`, `kb-lookup`, `environment-mapping`, `scenario-design`) | **Refactor** (не переписывать!) | Содержание качественное и продуманное; проблема — в формате выхода. Схематизировать выход, оставив текст промта | Схематизация «на глазок» потеряет нюансы, накопленные в прозе | Шаблоны, `stand-test-ai-schema` |
| `stand-test-java-dsl-authoring/SKILL.md` | **Refactor** | 213 строк, 7 обязанностей (A-16). Crib API вынести в генерируемый из кода артефакт | Расхождение crib и API — уже был прецедент, поэтому и появился `AuthoringCribApiCoverageTest` | API SDK |
| `rules/stand-test-guardrails.md` | **Refactor** | Разделить «hard constraints» (авто-загрузка) и «DB-write методика» (skill по требованию) — A-11 | Дробление правил повышает шанс, что часть не загрузится | `ForbiddenOperationCoverageTest` |
| Дублирование bundle `.claude` / `.opencode` | **Refactor** | Один источник + генерация; `BundleParityTest` превращается в проверку свежести (A-09) | Генерация усложняет правку для тех, кто редактирует bundle вручную | `BundleParityTest` |
| `docs/ai-agent/.opencode/opencode.json` | **Replace** | Не подлежит починке в текущем виде: абсолютные пути одной машины + прод-БД MCP (A-01). Нужен чистый шаблон + локальный оверрайд | Локальная конфигурация автора перестанет работать «из коробки» — потребуется миграция | `env.template`, `BundleParityTest` |
| Механизм «повтори с шага 6» | **Replace** | Не цикл, а фраза. Нужен ограниченный контур с состоянием (A-05) | Слишком жёсткий лимит снизит долю успешных генераций | Failure Classifier |
| Три представления тест-кейса (§6.1) | **Refactor → одно** | Analysis/Design/исполняемый должны стать проекциями одной типизированной модели | Большой рефакторинг; делать после MVP, не до | Все промты стадий 1-6 |
| `.env_*` файлы в `docs/ai-agent/.opencode/` | **Remove** (с диска bundle) | Секреты внутри shippable-каталога — источник A-02. `env.template` уже документирует их форму | Локальные MCP-подключения автора перестанут работать до переноса файлов | `opencode.json` |
| KB-записи `pakt-lgoty` (сервисы без контрактов) | **Keep** | Честная фиксация частичного промоута; удалять нельзя — это доказательство работы anti-invention-правила | Может создать ложное впечатление наполненности KB | `promotion-log.yml` |
| Отсутствующий CI | **Investigate** | Нужно понять: [U] запрещён ли внешний CI политикой банка, есть ли внутренний GitLab/TeamCity, куда его подключать | Без CI все инварианты держатся на дисциплине | — |
| Модель `alfagen/VIP-EVC-DeepSeek-V4-Flash` | **Investigate** | [U] Неизвестны: контекстное окно, качество на длинных промтах (kit требует ≥1000 строк контекста), стоимость, лимиты. Это определяет, реализуем ли пайплайн из 11 стадий в один прогон | Пайплайн может не помещаться в контекст выбранной модели | `opencode.json` |
| Статус ротации учётки `designer@tksdev3psg1` | **Investigate** | [U] Коммит `58282f2` требует ротации; выполнена ли — из репозитория не видно | Действующий скомпрометированный доступ | — |

---

## 12. Рекомендуемый MVP

### 12.1 Оценка предложенного в задаче сценария

Предложенный вертикальный срез (описание → KB → TestPlan → тест → компиляция → запуск →
результат → ≤2 repair → Allure → trace):

**Вердикт: сценарий по составу правильный, но по объёму на текущем состоянии нереализуем за
один заход.** Причины (все [F]):

1. **KB пуста.** Полноценные контракты есть у **одного** сервиса — mock (`showcase-mock`,
   6 endpoint'ов). Единственный реальный бизнес-документ дал 3 сервиса **без единого контракта**.
   Шаг «система находит требования в базе знаний» на реальном кейсе вернёт `missing`.
2. **Нет tool-слоя** — то есть нечем «проверить компиляцию», «запустить тест», «получить
   результат» иначе, чем текстом через Bash. Это ядро работы, а не деталь.
3. **Нет `TestPlan` как типа** — есть markdown.
4. **Нет trace** — не существует носителя для «полного execution trace».
5. **Repair-цикл** требует Failure Classifier, работающего со структурой, а не с текстом.

### 12.2 Предлагаемый более реалистичный первый срез

**MVP-0: «Наблюдаемый прогон на showcase-mock».** Взять **уже работающий** участок и
сделать его машинно-проверяемым — не добавляя ни одной новой возможности агента.

Границы (сознательно узкие):
- Единственный кейс: REST-сценарий против `showcase-mock` — **единственный сервис с полным
  контрактом в KB** (`endpoints/showcase-mock.yml`, 6 endpoint'ов, привязка
  `environments/ift.yml` → `SHOWCASE_MOCK_BASE_URL`).
- Единственный трек: **AI-формат** (JSON/YAML), а не Java DSL. Причина: у него **уже есть**
  сквозной машинный контракт (Schema → parser → validator, fail-closed) — единственная стадия
  пайплайна, чей результат проверяется механизмом. Java-трек добавляет компилятор и checkstyle,
  но не даёт семантической проверки.
- Repair: **0 итераций** в MVP-0. Сначала — наблюдаемость, потом автоматика.

Что делается:

1. **`RunManifest` (JSON-схема + запись).** Каждая стадия пайплайна дописывает запись:
   `stage`, `promptName`, `promptVersion`, `inputHash`, `outputPath`, `verdict`, `startedAt/finishedAt`.
   Это закрывает A-03 (нет следа), A-07 (нет версий), A-14 (нет run id) одним артефактом.
   Валидируется тестом в `stand-test-ai-schema` — рядом с уже существующими схемными тестами.
2. **`version` в frontmatter всех 32 промтов** + тест, что версия присутствует и упомянута
   в манифесте.
3. **Два тонких tool'а поверх существующего API** (Java, без новых зависимостей):
   `validateScenarioDocument(json) → {schemaMessages[], parseError?, validatorFindings[]}` —
   обёртка над `AiSchemaResources` + `AiScenarioParser` + `DefaultScenarioValidator`;
   `runScenarioTest(class) → {tests, skipped, failures[]}` — парсер
   `build/test-results/test/TEST-*.xml`. Оба — чистые функции над тем, что уже есть.
4. **Golden-кейс #1** — текстовый кейс на `showcase-mock` + ожидаемый `matched`-набор KB-записей
   + ожидаемый набор типов шагов. Офлайн-проверка: `matched` из прогона ⊇ ожидаемого.

Критерий готовности MVP-0 (проверяемый, не декларативный):
> После прогона существует `RunManifest`, в котором для каждой из стадий 1-11 указан статус
> `PASS`/`FAIL`/`NOT-RUN` с версией использованного промта, и утверждение «safety review PASS»
> невозможно записать, не записав его вход и выход.

**MVP-1 (следующий срез, после MVP-0):** добавить Failure Classifier поверх структурированного
результата `runScenarioTest` + ограниченный repair-цикл (`maxRepairIterations = 2`, критерий
прогресса: изменился `stepId` или класс ошибки). Только после этого — Java-трек и наполнение KB.

**Почему именно такой порядок.** Текущая система не может ответить на вопрос «стало ли лучше
после правки промта» — нет ни следа, ни версий, ни корпуса. Любая функциональная доработка до
закрытия этого пробела увеличивает объём непроверяемого поведения.

---

## 13. Основные технические риски

| # | Риск | Вероятность [A] | Влияние | Триггер / индикатор |
|---|---|---|---|---|
| **R-1** | Контекстное окно выбранной модели не вмещает 11-стадийный пайплайн (≥1000 строк промтов + KB + исходники потребителя) | Высокая | Пайплайн деградирует до «модель делает вид, что прошла стадии» — ровно тот режим отказа, который описан в `pipeline.md:74-78` | Прогоны, где стадии «пройдены», но артефакты стадий не созданы |
| **R-2** | Наполнение KB не масштабируется человеческим ревью: candidate-first конвейер требует ручного review каждого кандидата | Высокая | KB остаётся пустой → `kb-lookup` возвращает `missing` → агент бесполезен на реальных кейсах | Один документ (`ФС ПАКТ.Льготы`) дал 3 сервиса без контрактов |
| **R-3** | Дрейф промтов относительно SDK | Средняя (частично закрыта) | Генерируется код на несуществующем API | Уже случалось: `BundleParityTest` создан после того, как gRPC-матчеры разъехались «for a whole release» |
| **R-4** | Скомпрометированная учётка `designer@tksdev3psg1` не ротирована; blob'ы в истории | [U] неизвестно | Действующий доступ к DEV Postgres у любого, кто клонировал репо | Коммит `58282f2` |
| **R-5** | Bundle с прод-MCP скопирован в потребительский проект «как есть» | Средняя | Агент в чужом проекте получает `execute_sql` к прод-БД | `README.md:73-78` предписывает `cp -R docs/ai-agent/.opencode/*` |
| **R-6** | Неограниченный repair-цикл на реальном стенде | Средняя | Многократные записи в БД/Kafka на DEV/IFT; расход токенов; «зелёный любой ценой» | A-05 |
| **R-7** | Отсутствие CI: инварианты (паритет, схемы, guardrail-покрытие) не проверяются автоматически | Высокая | Регрессия обнаруживается только при ручном `./gradlew build` | A-13 |
| **R-8** | Java-трек — трек по умолчанию, но его guardrail-проверка существует только как 17 grep-паттернов, исполняемых моделью | Высокая | Нарушение guardrail в Java-тесте проходит все гейты, если модель его не заметила: пункты 8, 10-15, 17 из `safety-review/SKILL.md:32-41` **не имеют рантайм-защиты** (это признано в `SKILL.md:43-48`) | Прямая цитата: «Items 8, 10-15 and 17 have **no runtime enforcement**… the review is the only net» |
| **R-9** | Модель кейса раздвоена — невозможно проверить, что сгенерированный тест покрывает исходный кейс | Высокая | «Зелёный тест, проверяющий не то» — самый дорогой режим отказа для тест-фреймворка | §6.3, строка «Можно ли проверить полноту?» |

---

## 14. Список неизвестных данных [U]

| # | Что неизвестно | Почему важно | Как узнать |
|---|---|---|---|
| U-1 | Параметры модели `alfagen/VIP-EVC-DeepSeek-V4-Flash`: контекст, стоимость, лимиты, качество на длинных инструкциях | Определяет реализуемость 11-стадийного пайплайна (R-1) | Документация AlfaGen / замер |
| U-2 | Ротирована ли учётка `designer@tksdev3psg1`; переписана ли история master/feat | Действующий риск (R-4) | Запрос владельцу БД / `git log --all -- <path>` в удалённом репо |
| U-3 | Существует ли внутренний CI, куда можно подключить сборку; есть ли политика запрета | Блокирует A-13 | Инфраструктурная команда |
| U-4 | Проводился ли **реальный** сквозной прогон агента от текста до зелёного теста на стенде | Ключевой вопрос: работает ли пайплайн вообще. Косвенные признаки: коммиты `f6a6892` («close the two gaps an end-to-end run exposed»), `e962c94` — [A] прогон был, но его результат не зафиксирован проверяемым артефактом | Спросить у команды; поискать `mappings/` со статусом `validated`/`approved` |
| U-5 | Сколько реальных кейсов прошло через кит и с каким процентом успеха | Базовая линия для любой метрики | Опрос команды (в репозитории данных нет) |
| U-6 | Кто и как часто наполняет KB; есть ли владелец процесса | R-2 | Организационный вопрос |
| U-7 | Требования к автономности: должен ли агент работать без человека или human-in-the-loop остаётся навсегда | Влияет на приоритет approval-gate vs автоматизации | Продуктовое решение |
| U-8 | Целевой масштаб: сколько тестов в месяц, сколько сервисов в KB | Определяет, нужен ли индекс/семантический поиск | Продуктовое решение |
| U-9 | Есть ли у потребителя (QA_TEST и др.) собственный `knowledge-base/`, и какого объёма | KB в SDK-репо — контракт; реальная может быть в другом месте | Доступ к потребительским репозиториям |
| U-10 | Насколько строго соблюдается пайплайн на практике (пропускаются ли стадии) | Прямое следствие A-03: измерить нечем | Только после введения `RunManifest` |

---

## 15. Вопросы, которые необходимо решить до проектирования

**Блокирующие (без ответа проектировать нельзя):**

1. **Кто исполняет агента?** Остаётся ли хостом Claude Code / opencode (тогда «агент» — это
   всегда набор промтов + tools, и Java-код для оркестрации не нужен), или планируется
   собственный runtime на JVM (тогда нужен LLM-клиент, state machine, и это совсем другой
   объём работы)? От ответа зависит **всё** — вплоть до того, куда класть `RunManifest`.
2. **Где живёт агентский код?** Текущий инвариант — `stand-test-core` не зависит ни от чего,
   кроме `slf4j-api`; SDK публикуется потребителям как тестовая зависимость. Агентские tools,
   trace, память — это новый модуль (`stand-test-agent`?), который **не должен** попадать в
   compile-граф SDK. Нужно решение до первой строки кода.
3. **Human-in-the-loop навсегда или временно?** Сейчас approval-гейты — центральный элемент
   дизайна (`pipeline.md:72`: «The human merges»). Если цель — автономность, надо решать, что
   заменяет человека на гейте, и это меняет требования к evaluation.
4. **Что считать успехом агента?** «Тест скомпилировался» / «тест зелёный» / «тест зелёный и
   покрывает кейс» / «тест нашёл дефект». Без определения нельзя строить Evaluation Engine,
   а без него — нельзя улучшать промты измеримо.

**Важные (влияют на приоритеты):**

5. **Java DSL или AI-формат как основной трек агента?** Сейчас по умолчанию Java DSL, но
   машинный контракт есть только у AI-формата. Это прямое противоречие между «трек по умолчанию»
   и «трек, который можно проверить» (R-8).
6. **Кто владеет наполнением KB и по какому SLA?** Без ответа R-2 не закрывается, а без KB
   агент не работает.
7. **Допустим ли рефакторинг «единая модель тест-кейса»,** или три представления (§6.1) —
   осознанный компромисс, который трогать нельзя?
8. **Приемлемо ли устранить дублирование bundle генерацией,** или обе копии должны оставаться
   редактируемыми вручную?
9. **Какова политика по MCP-доступам агента к БД?** Read-only? Только DEV? Всегда `ask`?
   Ответ должен быть зафиксирован как проверяемое правило, а не как договорённость (A-01/A-15).
10. **Нужен ли `agentRunId` в артефактах SDK** (например, как tag в `ScenarioContext`), чтобы
    связать прогон агента с прогоном теста и Allure-отчётом? Это единственное место, где
    агентский слой может потребовать изменения SDK — решить осознанно.

---

## 16. Приложение: сводка доказательной базы

| Утверждение | Доказательство |
|---|---|
| LLM-кода в репозитории нет | `git grep -riE 'anthropic\|openai\|gigachat\|langchain\|chat-?model\|max_tokens\|temperature'` по `*.java,*.kts,*.toml,*.json,*.yml,*.properties` → 1 совпадение, и то в конфиге loader'а |
| Классов-агентов нет | `git grep -l 'class .*Agent' -- '*.java'` → пусто |
| `stand-test-ai-schema` не исполняет ничего | `AiSchemaResources.java:11-14` (javadoc), весь main-код — 60 строк |
| Объём SDK | `find <module>/src -name '*.java'` по 14 модулям: 333 файла, ~35 800 строк |
| Объём промтов | `wc -l docs/ai-agent/.claude/{rules,commands,skills,workflows}/**` → 2 753 строки; ×2 копии |
| Промты без версий | frontmatter: 16×`{name,description}`, 12×`{description}`; `grep '^version:'` по bundle → 0 |
| Лимита repair-итераций нет | `grep -rniE 'max.{0,3}(iterations?\|attempts?\|retries)\|не более\|попыт\|итерац'` по bundle → 0 |
| Прод-БД в bundle | `docs/ai-agent/.opencode/opencode.json:92-142` |
| Утечка секретов | коммит `58282f2` (текст сообщения), файлы добавлены в `ac2baea` |
| CI отсутствует | листинг корня: нет `.gitlab-ci.yml`, `.github/`, `Jenkinsfile`, `.teamcity/` |
| Объём KB | `find knowledge-base -name '*.yml' -not -path '*/candidates/*' -exec wc -l` → 561 строка |
| Java-guardrails без рантайм-защиты | `skills/stand-test-safety-review/SKILL.md:43-48` — прямая цитата авторов |
| Метаданные сценария мертвы | `skills/stand-test-java-dsl-authoring/SKILL.md:165-177` + коммит `2f25465` |
| Паритет копий bundle не покрывает `opencode.json` | `BundleParityTest.java:33` — `OPENCODE_ONLY` |
| Тестовые зависимости | `gradle/libs.versions.toml:1-98` построчно |

---

*Документ создан в рамках анализа без изменения production-кода. Единственный созданный файл —
этот. Все выводы, помеченные [F], проверяемы по указанным путям и коммитам.*
