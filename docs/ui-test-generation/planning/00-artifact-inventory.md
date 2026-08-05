# 00. Инвентаризация артефактов

| | |
|---|---|
| **Документ** | Инвентаризация (доказательная база перед планированием) |
| **Дата** | 2026-08-03 |
| **Ветка** | `feat/ai-agent-kit`, HEAD `46d3cfb` |
| **Метод** | чтение файлов и кода, запуск проверок репозитория; production-код не изменялся |
| **Основание** | [`docs/brd/ui-test-generation-brd.md`](../../brd/ui-test-generation-brd.md) (фактический путь подтверждён) |

Статусы: `ACTIVE` — актуальность подтверждена кодом или прогоном; `DRAFT` — предложено, решение не
принято; `OUTDATED` — утверждения опровергнуты фактическим состоянием; `PARTIALLY_RELEVANT` — часть
верна, часть опровергнута; `SUPERSEDED` — заменён; `UNKNOWN` — проверить не удалось.

> `ACTIVE` не ставится по факту существования файла. Там, где документ не проверялся против кода
> построчно, стоит `PARTIALLY_RELEVANT` с указанием проверенной части.

---

## 0. Факт, который меняет чтение всей таблицы

**Вся UI-линия работ не закоммичена.** `git ls-files stand-test-ui` возвращает 0 файлов при 70
`.java` на диске; `git ls-files docs/ui-test-generation` — 0; `git ls-files docs/brd` — 0.

| Что | Значение |
|---|---|
| Последний коммит | `46d3cfb feat(core,config,starter): UI applications addressed by registry alias` |
| Untracked файлов | **183** (86 `.md`, 81 `.java`, 12 `.yml`, +`build.gradle.kts` модуля, 2 файла `META-INF/services`) |
| Строк в untracked `.java` | **10 340** |
| Изменения в трекаемых файлах | 45 файлов, +2625 / −168 |

Следствия для планирования, не для оценки качества:

1. **Иерархия доказательности сохраняется** — код на диске компилируется и проходит 1481 тест, это
   выше документа по любой шкале. Но «код» здесь означает рабочее дерево одной машины.
2. Ни один из перечисленных ниже UI-артефактов **не виден в клоне репозитория**. Любой план,
   предполагающий, что коллега «посмотрит модуль», сначала требует коммита.
3. Ревью объёма 10 340 строк одной порцией — самостоятельный риск (см. `03-conflicts-and-gaps.md`,
   GAP-11).

---

## 1. Бизнес-источники

| Путь | Тип | Назначение | Статус | Требования BRD | Подтверждено кодом | Незакрытые решения | Годен как источник плана |
|---|---|---|---|---|---|---|---|
| `docs/brd/ui-test-generation-brd.md` (1195 строк) | BRD v0.3 | Единственный бизнес-источник: D-1…D-10, BR-01…BR-37, NFR-01…09, SEC-01…10, KPI-1…9, G-1…G-6, OQ-01…13, §18 волны, §19 гейты | **ACTIVE** (кроме §21) | все | частично — см. `02-requirements-traceability.md` | OQ-01…OQ-13 открыты | **да** — базовый источник |
| `docs/brd/…brd.md` §21 «Приложение А» | пример | Иллюстрация целевого теста | **OUTDATED** | BR-06, UC-03 | нет | — | нет: строка 622 утверждает «Модуля `stand-test-ui` пока нет» — модуль существует (70 файлов, 189 тестов). API примера не совпадает с реализованным `UiStep`/`UiLocator` |
| `docs/brd/…brd.md` §22 «Приложение Б» | пример | UI-сценарий в AI-формате | **DRAFT** | BR-32 | **опровергнуто** | OQ-09 | нет: JSON Schema не содержит ни одного `ui.*` (проверено: типы шагов — `rest.get/post/expectEventually`, `kafka.send/expect`, `db.expectEventually`, `grpc.unary`), корень закрыт `additionalProperties: false`, раздела `cleanup` нет. Сам BRD это и says |

SRS/ФС в репозитории **нет**. Ближайший аналог — `docs/ai-agent/example-test-case-specification.md`
(пример входного кейса для кита), не спецификация продукта.

## 2. Аналитика и планирование UI-волны

Все файлы датированы 2026-08-01 и сняты на коммите `cf81358` — **до** появления модуля.

| Путь | Тип | Статус | Что подтверждено | Что опровергнуто | Годен как источник плана |
|---|---|---|---|---|---|
| `docs/ui-test-generation/00-current-state.md` (761) | карта «как есть» | **PARTIALLY_RELEVANT** | таблица S-1…S-N точек расширения; §7.2 про fail-closed реестр; §S-2 «ServiceLoader-регистрация» — сбылось буквально | строка 45: «Модуля `stand-test-ui` не существует» — существует; строка 495 про набор исправлена 2026-08-03 | да, как карта швов; нет, как описание текущего состояния |
| `docs/ui-test-generation/01-brd-traceability.md` | матрица BRD→код | **PARTIALLY_RELEVANT** | строка BR-01 обновлена 2026-08-03 | остальные строки сняты до модуля | нет — заменяется `02-requirements-traceability.md` этого каталога |
| `docs/ui-test-generation/02-open-questions.md` | Q-01…Q-15 | **ACTIVE** | Q-15 частично закрыт 2026-08-03 | — | да |
| `docs/ui-test-generation/10-target-architecture.md` (1085) | техдизайн волны 1 | **PARTIALLY_RELEVANT** | §2–§9 совпали с реализацией по проверенным точкам (модуль→core+await, ServiceLoader, `UiDriver` как шов, Playwright в отдельном пакете, headless/браузер/вьюпорт конфигурацией) | §12 «Артефакты и отчётность» не реализован; §14 cleanup не реализован | да — как дизайн; сверять по разделам |
| `docs/ui-test-generation/20-wave-1-backlog.md` (992) | бэклог S-0.1…S-6.3 | **PARTIALLY_RELEVANT** | состав задач актуален; S-5.5 дополнен 2026-08-03 | шапка «Статус: Проект. Production-код не изменялся» — неверна: E1, E3, E4, S-5.1, S-5.2, S-5.5 реализованы | да — при пересмотре статусов задач |
| `docs/ui-test-generation/21-wave-1-test-strategy.md` (324) | стратегия тестирования | **PARTIALLY_RELEVANT** | — | та же шапка «Production-код не изменялся» | да — сверить с фактическими 189+24 тестами модуля |

## 3. ADR

| Путь | Статус в файле | Фактическое состояние кода | Вердикт | Требования |
|---|---|---|---|---|
| `adr/ADR-UI-001-module-boundaries.md` | Accepted, реализован 2026-08-02 | подтверждён: `stand-test-ui` → `core`+`await`, ArchUnit-правила `nothingDependsOnUi`, `playwrightIsConfinedToDriverPackage` | **ACTIVE** | NFR-05, D-1 |
| `adr/ADR-UI-002-playwright-lifecycle.md` | Accepted, шаг 1 реализован | подтверждён: `PlaywrightDriverFactory`, один `BrowserContext` на прогон в `ResourceScope` | **ACTIVE** | NFR-03, BR-25 |
| `adr/ADR-UI-003-public-api.md` | Accepted, минимальный срез | подтверждён: `UiStep.open/click/fill/expect/expectEventually/login`, `UiLocator`, `UiAssertion`, `UiCapture` | **ACTIVE** | BR-06, BR-31 |
| `adr/ADR-UI-004-environment-registry-versioning.md` | **Proposed** | **реализован**: `EnvironmentConfigFormat.SUPPORTED_VERSION = 3`, `UI_APPLICATIONS_SINCE_VERSION = 2`, `UI_LOGIN_SINCE_VERSION = 3` | **КОНФЛИКТ** (CONF-01) | BR-37, D-10, OQ-13 |
| `adr/ADR-UI-005-reporting-and-artifacts.md` | Proposed | не реализован: `Attachment` — `record(String name, String mediaType, String content)`, бинарного канала нет | **DRAFT**, согласован с кодом | BR-21, BR-22, BR-35, SEC-05 |
| `adr/ADR-UI-006-authentication-and-account-pool.md` | Accepted, реализовано | подтверждён: `AccountPool`, `InProcessAccountPool`, `UiLoginService`, `StorageStateStore` | **ACTIVE** | BR-28, BR-29, BR-34, G-5 |
| `adr/ADR-UI-007-non-db-compensations.md` | Proposed | не реализован: `undoLog().register(...)` вызывается только в `DbStepExecutor:191` | **DRAFT**, согласован с кодом | BR-24, D-9, RISK-15 |

ADR агентной линии (`docs/agent-architecture/adr/`): 0004, 0006, 0007, 0012, 0013, 0014 — **ACTIVE**,
относятся к киту, не к UI. ADR 0001/0002/0003/0005/0008/0009/0010/0011 удалены вместе с рантаймом.

## 4. Планы и решения по SDK

| Путь | Тип | Статус | Замечание |
|---|---|---|---|
| `docs/arch/stand-test-sdk-implementation-plan.md` (1439) | план, источник истины по контрактам | **ACTIVE** | шапка: «Implemented», итерации 0–10 реализованы. UI в нём не описан |
| `docs/arch/architecture-overview.md` (680) | карта кода | **PARTIALLY_RELEVANT** | проверить упоминание модулей: UI появился позже |
| `docs/arch/stand-test-db-decisions.md`, `…-remediation-plan.md`, `…-rollback-design.md` | решения БД | **ACTIVE** | важны для BR-24: undo-log спроектирован под БД |
| `docs/arch/stand-test-ai-schema-design.md`, `…-remediation-plan.md` | дизайн AI-схемы | **ACTIVE** | важны для BR-32 |
| `docs/arch/stand-test-allure-implementation-plan.md` | план Allure | **ACTIVE** | важен для BR-21: сток отчётности |
| `docs/arch/stand-test-example-implementation-plan.md`, `…-next-steps.md`, `stand-test-scenario-yaml-design.md` | планы модулей | **ACTIVE** | вне UI |
| `docs/plans/ai-agent-kit-implementation.md` | план кита | **ACTIVE** | «Дальше»: сигнатуры падений, `failure-analyst`, opencode-плагин |
| `docs/plans/stand-test-{config,grpc,spring-boot-starter}-*.md` | планы модулей | **ACTIVE** | вне UI |
| `docs/publishing.md` | публикация | **ACTIVE** | эндпоинта нет — вне UI-скоупа |
| `docs/agent-analysis/current-state-analysis.md` (817) | анализ A-01…A-17 | **PARTIALLY_RELEVANT** | 2026-07-27, до UI-линии |

## 5. Кит агентной генерации (`docs/ai-agent/`)

Две копии одного набора: `.claude/` и `.opencode/`. Счётчики проверены на диске.

| Артефакт | Кол-во | Статус | Требования | Замечание |
|---|---|---|---|---|
| `MANIFEST.json` | 259 путей с хешами | **ACTIVE** | BR-36 | перегенерирован 2026-08-03; пиннится `KitManifestTest` |
| `rules/` | 3 (`stand-test-guardrails`, `stand-test-pipeline`, `stand-test-ui-guardrails`) | **ACTIVE** | BR-05, BR-23, BR-27, SEC-01…06 | UI-правила — 292 строки, 15 жёстких ограничений |
| `skills/` | 26 (17 протокол + **9 UI**) | **ACTIVE** | BR-01, BR-03, BR-05…BR-08 | UI: intake, completeness, discovery, scenario-design, page-object, java-authoring, safety-review, quality-review, generation-report |
| `commands/` | 19 (14 + **5 UI**) | **ACTIVE** | BR-01 | `/stand-test-generate-ui-test` + 4 среза |
| `agents/` | 3 (`kb-resolver`, `safety-reviewer`, `quality-reviewer`) | **ACTIVE** | SEC-06 | только в `.claude/`; opencode их не объявляет |
| `hooks/detectors.json` | **18 находок, из них UI-специфичных — 0** | **PARTIALLY_RELEVANT** | SEC-06, BR-23, BR-27 | измерено: над java-артефактом применимы 12 из 18, над прозой — 4 |
| `hooks/stand-guard.mjs` + `lib/` (15 модулей) | — | **ACTIVE** | SEC-06 | `scan`, `record-gate`, `kb-validate`, `doctor`, SARIF |
| `hooks/stand-batch.mjs` | — | **ACTIVE** | BR-01 | 2026-08-03 научен маршрутизировать UI-кейсы в `/stand-test-generate-ui-test` |
| `knowledge-base/` | 20 схем + записи | **PARTIALLY_RELEVANT** | BR-08, BR-36 | **UI-сущностей нет**: коллекции `services/endpoints/kafka/db/grpc/environments/mappings/candidates` |
| `workflows/` | 2 | **ACTIVE** | — | ingest-spec, review-and-apply |
| `usage-guide.md`, `README.md`, `install.mjs`, `example-test-case-specification.md` | — | **ACTIVE** | — | — |

## 6. Эталонный набор (evaluation dataset)

| Путь | Тип | Статус | Требования | Замечание |
|---|---|---|---|---|
| `docs/agent-evaluation/contracts/evaluation-case.schema.json` | JSON Schema, версия формата 2 | **ACTIVE** | BR-01, BR-05, BR-36 | расширен 2026-08-03 аддитивно; аддитивность проверяется тестом |
| `docs/agent-evaluation/dataset/cases/` | **27 кейсов** (15 протокол + 12 UI) | **ACTIVE** | BR-01, BR-05, BR-10, BR-11, KPI-4 | 104 требования, 31 прогон; `execution.outcome: NOT_RUN` у всех 12 UI |
| `docs/agent-evaluation/dataset/README.md` | описание набора | **ACTIVE** | — | числа пересчитываются тестом |
| `docs/agent-evaluation/kpi-collection-template.md` | шаблон замеров | **DRAFT** | KPI-1/3/4/9 | создан 2026-08-03, данных нет |
| `docs/agent-evaluation/ui-wave-1-readiness.md` | отчёт о готовности | **ACTIVE** | §19.1 | создан 2026-08-03 |

## 7. Код, сборка, проверки

| Артефакт | Путь | Статус | Замечание |
|---|---|---|---|
| Модули сборки | `settings.gradle.kts:129` | **ACTIVE** | 15 модулей, включая `stand-test-ui` |
| Общая конфигурация | `build.gradle.kts` (`subprojects`) | **ACTIVE** | нет `buildSrc`; checkstyle `maxWarnings = 0`; JaCoCo-гейт 80% |
| Линтер | `checkstyle.xml` | **ACTIVE** | баны JUnit-ассертов и non-JetBrains `@NotNull` |
| Каталог версий | `gradle/libs.versions.toml` | **ACTIVE** | `javaRelease = 17`, toolchain 21, Playwright |
| Архитектурные тесты | `stand-test-example/src/test/java/…/ModuleDependencyArchTest.java` | **ACTIVE** | 10 правил, включая `coreHasNoUiOrIoDependencies`, `scenarioHasNoUiFields`, `playwrightIsConfinedToDriverPackage` (+ проверка невакуумности), `nothingDependsOnUi` |
| SPI-регистрация | `stand-test-ui/src/main/resources/META-INF/services/…StepExecutor` | **ACTIVE** | UI-исполнитель подхватывается `StandTestExtension:170` |
| JSON Schema сценариев | `stand-test-ai-schema/src/main/resources/schema/*.json` | **ACTIVE** | 3 схемы; `ui.*` в них нет |
| **CI/CD** | — | **ОТСУТСТВУЕТ** | ни `.github/`, ни `.gitlab-ci.yml`, ни `Jenkinsfile`, ни `.teamcity/` |
| OpenAPI/AsyncAPI | — | **ОТСУТСТВУЮТ** | контракты систем-потребителей в репозитории не лежат |
| TODO/FIXME по UI | — | **ОТСУТСТВУЮТ** | в `stand-test-*/src/main/java` — 0 |

## 8. Готовность артефактов как источников плана

| Категория | Годны без оговорок | С оговорками | Не годны |
|---|---|---|---|
| Бизнес | BRD §1–§20 | — | §21, §22 (примеры) |
| Дизайн UI | ADR-UI-001/002/003/006 | 10-target-architecture (по разделам), 20-backlog (пересмотреть статусы) | 01-brd-traceability (заменён) |
| Решения к принятию | — | ADR-UI-004 (принять пост-фактум), 005, 007 | — |
| Кит | rules, skills, commands, MANIFEST | detectors.json (UI-половина пуста), KB (UI-сущностей нет) | — |
| Набор | контракт + 27 кейсов | kpi-template (пуст) | — |
| Код | модули, arch-тесты, `settings.gradle.kts` | — | CI (нет) |
