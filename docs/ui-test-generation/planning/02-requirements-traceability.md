# 02. Матрица прослеживаемости BRD → код и артефакты

| | |
|---|---|
| **Документ** | Traceability matrix |
| **Дата** | 2026-08-03 · HEAD `46d3cfb` + незакоммиченное рабочее дерево · **три строки обновлены 2026-08-04** (BR-18, D-5, §7) — их обесценили `UITG-S025` и `UITG-S024` |
| **Источник требований** | [`docs/brd/ui-test-generation-brd.md`](../../brd/ui-test-generation-brd.md) v0.3 |
| **Правило** | документ не является доказательством реализации; статус подтверждается кодом, прогоном или отсутствием того и другого |

Статусы: `IMPLEMENTED` · `PARTIALLY_IMPLEMENTED` · `NOT_IMPLEMENTED` · `DOCUMENTED_ONLY` (есть только
в документе или промте кита, машинной опоры нет) · `ARCHITECTURAL_CONFLICT` · `EXTERNAL_DEPENDENCY` ·
`UNKNOWN`.

**Заменяет** `../01-brd-traceability.md` (снят 2026-08-01, до появления модуля).

---

## 1. Зафиксированные решения D-1…D-10

| ID | Суть | Статус | Доказательство | Недостающее / решение |
|---|---|---|---|---|
| D-1 | Модуль SDK + расширение кита | `IMPLEMENTED` | `settings.gradle.kts:129` включает `stand-test-ui`; кит несёт 9 UI-скиллов и 5 UI-команд | — |
| D-2 | Драйвер — Playwright for Java | `IMPLEMENTED` | `stand-test-ui/build.gradle.kts:21` `implementation(libs.playwright)`; `ui/playwright/PlaywrightUiDriver.java`; ADR-UI-002 Accepted | — |
| D-3 | Браузер только на генерации | `PARTIALLY_IMPLEMENTED` | правило U14 в `rules/stand-test-ui-guardrails.md` §11; в `stand-test-ui` нет ни одного обращения к модели | детектора на «LLM в рантайме» нет — проверка глазами (S-5.4) |
| D-4 | Артефакт — Java-тест + Page Objects | `IMPLEMENTED` | скиллы `stand-test-ui-java-authoring`, `stand-test-ui-page-object-design`; эталонные артефакты компилируются (`javac --release 17`, код 0) и проходят checkstyle | декларативный трек — BR-32, не сделан |
| D-5 | `data-testid` — рекомендация | `PARTIALLY_IMPLEMENTED` | компенсация объявлена: раздел 4 шаблона отчёта требует список хрупких локаторов; **KPI-9 считается статически** — подкоманда `kpi-locators` гарда, на эталонном Page Object кита 3 из 5 (`UITG-S024`) | эскалация не автоматизирована (и не будет: `out_of_scope` S024 — это организационный процесс); самого замера волны 1 нет |
| D-6 | Поставка волнами | `DOCUMENTED_ONLY` | BRD §18; бэклог `20-wave-1-backlog.md` | организационное решение |
| D-7 | Сквозные допустимы; отчёт указывает слой | `PARTIALLY_IMPLEMENTED` | слой выводится из типа шага: `DefaultScenarioRunner:534` `Step [i/total] 'id' (type)` — и в логе, и в тексте исключения; MDC несёт `stepType` | явного поля «слой» нет; отчётная половина упирается в BR-21 |
| D-8 | Связывание по данным; `correlationId` предпочтителен | `PARTIALLY_IMPLEMENTED` | оба механизма есть: `UiCapture` (захват с экрана) и `UiStepExecutor:530–535` (`setExtraHeader(CORRELATION_HEADER, …)`) | протягивание заголовка backend'ом — `EXTERNAL_DEPENDENCY` G-6/DEP-08 |
| D-9 | Откат UI-данных требует доработки core | `NOT_IMPLEMENTED` | `undoLog().register(...)` вызывается **только** в `DbStepExecutor:191`; UI кладёт в `ResourceScope` (`UiStepExecutor:331, 518`) — это освобождение ресурсов, а не откат данных | ADR-UI-007 в статусе Proposed; форма решения — Q-02 |
| D-10 | Версионирование реестра сред | `IMPLEMENTED` | `EnvironmentConfigFormat`: `SUPPORTED_VERSION = 3`, `INITIAL_VERSION = 1`, `UI_APPLICATIONS_SINCE_VERSION = 2`, `UI_LOGIN_SINCE_VERSION = 3`; обе поверхности читают одну константу | ADR-UI-004 остался `Proposed` — **CONF-01** |

## 2. Бизнес-требования BR-01…BR-37

### 2.1 Генерация теста агентом

| ID | Суть | Приор. | Статус | Доказательство | Недостающее | Область изменения |
|---|---|---|---|---|---|---|
| BR-01 | Кейс текстом → тест без кода человека | M | `PARTIALLY_IMPLEMENTED` | пайплайн из 9 стадий (`rules/stand-test-pipeline.md`), команда `/stand-test-generate-ui-test`, 12 UI-кейсов набора | **критерий приёмки не замерен**: `execution.outcome: NOT_RUN` у всех 12; прогон агента ни разу не выполнялся | замер, не код |
| BR-02 | Кейс из Jira/Confluence | S | `NOT_IMPLEMENTED` | интеграций нет; `stand-test-spec-ingestion` работает с файлами | вся интеграция | кит + DEP-04 |
| BR-03 | Живой UI как источник истины | M | `PARTIALLY_IMPLEMENTED` | скилл `stand-test-ui-discovery`; правило «KB → разведка → вопрос» | гейт U1 машинно не проверяется («an invented locator is syntactically perfect»); ни одной живой разведки не выполнено | S-5.4 + G-5 |
| BR-04 | Сверка с Figma | C | `NOT_IMPLEMENTED` | — | всё | волна 3 |
| BR-05 | Допущение или вопрос, но не выдумка | M | `PARTIALLY_IMPLEMENTED` | правила кита; 4 UI-кейса категорий `ui-incomplete/contradictory/missing-context/stale-documentation` | измерение не проводилось; для UI-кейсов проверка — подсаженный отчёт разведки, а не живой DOM | замер |
| BR-06 | Java-тест на публичном API + Page Objects | M | `IMPLEMENTED` | `UiStep`/`UiLocator` — публичный API; эталонные артефакты кита компилируются и проходят checkstyle репозитория (замер 2026-08-03) | критерий «в проекте потребителя» не проверялся | — |
| BR-07 | Отчёт + сохранённая исходная выдача | M | `PARTIALLY_IMPLEMENTED` | шаблон `ui-generation-report-template.md`: **восемь** разделов, §8 — снимок и хеши | BRD говорит «все шесть разделов» — расхождение с шаблоном (**CONF-06**); ни одного отчёта не произведено | сверка формулировки |
| BR-08 | UI-сущности в KB | S | `NOT_IMPLEMENTED` | в `docs/ai-agent/knowledge-base/` коллекций экранов/элементов/флоу нет; 20 схем — все протокольные | коллекции + схемы + промоут | S-5.3 |
| BR-09 | Детерминизм: прогон без LLM | M | `IMPLEMENTED` | в `stand-test-ui/src/main/java` нет ни одного обращения к модели; тест — обычный JUnit | — | — |

### 2.2 Полнота покрытия

| ID | Суть | Приор. | Статус | Доказательство | Недостающее |
|---|---|---|---|---|---|
| BR-10 | Позитивный сценарий | M | `PARTIALLY_IMPLEMENTED` | кейс `ui-pos-support-request-registered`; вертикальный срез работает: `UiVerticalSliceBrowserTest` зелёный | критерий «покрыт для каждого кейса набора» не замерен |
| BR-11 | Валидации форм | M | `PARTIALLY_IMPLEMENTED` | кейс `ui-form-validation-description-too-short` | тот же замер |
| BR-12 | Негативные и краевые кейсы | M | `NOT_IMPLEMENTED` | intake спрашивает про негативные пути | волна 2 по BRD §18 |
| BR-13 | UI-действие + backend-последствие | M | `PARTIALLY_IMPLEMENTED` | `UiCapture` + `injectCorrelationId`; связывание через `VariableStore` (`${var}` в REST-пути) | сквозной кейс в наборе отсутствует намеренно (волна 2); `EXTERNAL_DEPENDENCY` G-6 |
| BR-14 | Визуальные регрессы | S | `NOT_IMPLEMENTED` | — | волна 3; DEP-05 |
| BR-15 | Кросс-браузерность | S | `PARTIALLY_IMPLEMENTED` | `PlaywrightDriverFactory:89–92` — `chromium`/`firefox`/`webkit` по свойству `stand.test.ui.browser` | критичный набор в двух браузерах не прогонялся: `browserTest` использует только chromium |
| BR-16 | Адаптивность (вьюпорт) | S | `PARTIALLY_IMPLEMENTED` | `ViewportProfile` в реестре, применяется в `PlaywrightDriverFactory:47, 79` | механизм «прогон отмеченных сценариев на мобильном вьюпорте» (теги) не проверялся |
| BR-17 | Доступность (a11y) | C | `NOT_IMPLEMENTED` | — | волна 3 |
| BR-33 | Перехват и подмена сетевых ответов | S | `NOT_IMPLEMENTED` | в `stand-test-ui` нет route/intercept | волна 2; ограничение origin'ом — отдельное решение |

### 2.3 Исполнение и эксплуатация

| ID | Суть | Приор. | Статус | Доказательство | Недостающее |
|---|---|---|---|---|---|
| BR-18 | Прогон в CI headless | M | `PARTIALLY_IMPLEMENTED` / `EXTERNAL_DEPENDENCY` | `.gitlab-ci.yml`: джобы `verify` (merge request и push) и `nightly-verify` (`--rerun-tasks --no-build-cache`), закреплено `CiPipelineConfigTest` (`UITG-S025`) | **headless-джобы UI нет**: нужен образ с браузерами (G-3). Значения переменных раннера и «Pipelines must succeed» — настройка проекта |
| BR-19 | Локальный headed-прогон | M | `IMPLEMENTED` | `UiRunSettings.HEADLESS_PROPERTY`; `stand-test-ui/build.gradle.kts:48` пробрасывает `-Dstand.test.ui.headless=false` | — |
| BR-20 | Grid/Selenoid | S | `NOT_IMPLEMENTED` | упоминания только в комментариях `UiDriverFactory:10`, `UiDriver:11` («a connection to a remote grid — each is a different factory») | новая реализация `UiDriverFactory` |
| BR-21 | Артефакты падения: скриншот, трейс/видео, консоль, сеть | M | `NOT_IMPLEMENTED` + `ARCHITECTURAL_CONFLICT` | `core/event/Attachment.java:26` — `record Attachment(String name, String mediaType, String content)`; **бинарного канала нет**. `trace` в реестре парсится (`UiApplicationDefinition:37`) и ничем не потребляется | расширение контракта `Attachment` в `core` + снятие артефактов + сток в Allure. ADR-UI-005 Proposed; форма — Q-01 |
| BR-22 | Отчёт: ids, шаги, слой отказа | M | `PARTIALLY_IMPLEMENTED` | MDC несёт `scenarioId`/`testRunId`/`correlationId`/`environment` (`DefaultScenarioRunner:543–546`) и `stepId`/`stepType`/`stepIndex` (551–553); сообщение об отказе — `Step [i/total] 'id' (type)` | явного поля «слой» нет — выводится из префикса типа; отчётная часть зависит от BR-21 |
| BR-23 | Только единый await; `Thread.sleep` отсекается гейтом | M | `PARTIALLY_IMPLEMENTED` | SDK не предоставляет иного ожидания: `UiStepExecutor:19,66` — `Awaiter`; `ForbiddenOperation.THREAD_SLEEP` существует; детектор 6 кита отвергает запись файла с `Thread.sleep` | рантайм-валидатор кода не видит: `Thread.sleep` в теле теста ловится **только** хуком кита; под opencode хук не привязан к событиям |
| BR-24 | Идентификация и вычистка UI-данных, политика `ALWAYS` | M | `NOT_IMPLEMENTED` | компенсации регистрирует только БД (`DbStepExecutor:191`); `CleanupPolicy` содержит `ALWAYS`, но для UI регистрировать нечего | D-9; ADR-UI-007; способ удаления по приложениям — OQ-06 |
| BR-25 | Параллельность классов **и методов** | S | `ARCHITECTURAL_CONFLICT` | реализовано: классы `concurrent`, методы `same_thread` (`stand-test-ui/src/test/resources/junit-platform.properties`), `maxParallelForks = 1`; `UiParallelSuiteBrowserTest` зелёный | BRD требует «дополнительно на уровне методов внутри класса»; инвариант SDK — «один прогон сценария = один поток» — методы внутри класса делят состояние класса. **CONF-02** |
| BR-26 | Команда отладки классифицирует падение | S | `PARTIALLY_IMPLEMENTED` | скилл `stand-test-debugging` + команда `/stand-test-debug` | критерий «классификация верна ≥70%» не замерен; набора известных падений нет |
| BR-34 | Пул техучёток по ролям, ограниченный таймаут | M | `IMPLEMENTED` | `AccountPool`, `InProcessAccountPool`, аренда в `ResourceScope` (`UiStepExecutor:331`); `UiStep.accountTimeout(...)`, дефолт `UiStepParameters.DEFAULT_ACCOUNT_TIMEOUT_MILLIS`, бесконечного ожидания нет (`UiStep:393`) | реальные учётки — `EXTERNAL_DEPENDENCY` G-5 |

### 2.4 Конфигурация и доступы

| ID | Суть | Приор. | Статус | Доказательство | Недостающее |
|---|---|---|---|---|---|
| BR-27 | Алиас, произвольный URL запрещён | M | `IMPLEMENTED` | `UiStep:542–543` отвергает абсолютный адрес на построении; `ForbiddenOperation.NON_WHITELISTED_UI_APPLICATION` проверяется до старта браузера; детектор 1 кита ловит адрес в артефакте | — |
| BR-28 | Вход техучёткой через secret-ref | M | `IMPLEMENTED` | `UiAuthConfig.credentialsPoolRef` (строки 25, 41, 51) — ссылка, не значение; `SecretReferences`; ростер живёт в переменной окружения | — |
| BR-29 | SSO + переиспользование сессии на учётку | M | `PARTIALLY_IMPLEMENTED` | `STORAGE_STATE` реализован per-account (`StorageStateStore`, путь `<artifacts>/storage-state/<env>/<app>/<accountId>.json`) | `UiAuthScheme.SSO` объявлена и **отвергается** (`UiStepExecutor:459–460`) |
| BR-30 | Стратегия MFA/OTP/КАПЧА | M | `PARTIALLY_IMPLEMENTED` | `UiLoginChallenge` (`NONE`/`MFA`/`OTP`), шов `UiLoginChallengeHandler`, отказ с указанием гейта G-1 | «стратегия описана для каждого приложения волны» — `EXTERNAL_DEPENDENCY` G-1/OQ-01 |
| BR-31 | Конфигурация браузера — не кодом | M | `IMPLEMENTED` | `UiRunSettings` (5 системных свойств), вьюпорт из реестра; запрет UI-полей в `Scenario` закреплён тестом `scenarioHasNoUiFields` | — |
| BR-32 | Декларативный трек (YAML) | S | `NOT_IMPLEMENTED` | JSON Schema содержит 7 типов шагов, ни одного `ui.*`; корень закрыт `additionalProperties: false`; раздела `cleanup` нет | грамматика ассершенов + раздел cleanup; решение — OQ-09 |
| BR-35 | Разметка чувствительных зон | M | `PARTIALLY_IMPLEMENTED` | `UiLocator.sensitive` + `UiSecrets.guard` (`UiStepExecutor:211`) — **защищает текст исключения** | маскирование DOM **до снятия артефакта** невозможно: артефактов нет (BR-21) |
| BR-36 | Совместимость версий схем и кита | M | `PARTIALLY_IMPLEMENTED` | `MANIFEST.json` + `KitManifestTest` (регенерация, а не осмотр); контракт набора расширен аддитивно и аддитивность проверяется тестом | версионирование схем KB не определено (Q-07); UI-сущностей в KB ещё нет |
| BR-37 | Версионирование файла реестра | M | `IMPLEMENTED` | `EnvironmentConfigFormat` + `requireSectionSupported`; обе поверхности (`stand-test-config`, стартер) читают одну константу; тесты `EnvironmentConfigTest`, `EnvironmentRegistryParityTest` | окно совместимости не определено — Q-06/OQ-13 |

## 3. Нефункциональные требования

| ID | Суть | Статус | Доказательство | Недостающее |
|---|---|---|---|---|
| NFR-01 | Определение и цель flaky rate | `DOCUMENTED_ONLY` | определение в BRD | замеров нет; требует повторных прогонов и разбора человеком |
| NFR-02 | ≤30 мин на генерацию | `UNKNOWN` | не замерялось | замер на волне 1 |
| NFR-03 | ≤3 мин на UI-сценарий | `UNKNOWN` | косвенно: `browserTest` — 24 теста за 33 с на локальной машине | замер на реальном приложении |
| NFR-04 | Java 17/21/24 | `IMPLEMENTED` | `--release 17` в `subprojects`; toolchain 21 | — |
| NFR-05 | Изоляция архитектуры | `IMPLEMENTED` | `coreHasNoUiOrIoDependencies`, `scenarioHasNoUiFields`, `playwrightIsConfinedToDriverPackage` + тест невакуумности | — |
| NFR-06 | Не ломает существующие тесты | `IMPLEMENTED` | 1481 тест, 0 падений, 1 намеренный пропуск; протокольные модули не изменялись | проверять повторно при доработке core под BR-21/BR-24 (RISK-15) |
| NFR-07 | Читаемость артефакта | `PARTIALLY_IMPLEMENTED` | checkstyle репозитория проходит на эталонных артефактах кита (замер 2026-08-03, 0 нарушений) | «линтер проекта потребителя» — вне репозитория |
| NFR-08 | Наблюдаемость | `IMPLEMENTED` | `MdcScope`: `Step [i/total] 'id' (type)` в логах и в исключении; 4 идентификатора в MDC сценария | — |
| NFR-09 | Ресурсоёмкость CI | `UNKNOWN` / `EXTERNAL_DEPENDENCY` | CI отсутствует | G-3 |

## 4. Безопасность SEC-01…SEC-10

| ID | Суть | Статус | Доказательство | Недостающее |
|---|---|---|---|---|
| SEC-01 | Промышленный контур запрещён | `PARTIALLY_IMPLEMENTED` | алиас обязан быть в whitelist окружения (`NON_WHITELISTED_UI_APPLICATION`); произвольный URL невыразим (`UiStep:542`); в контракте набора `ui.environment` — enum `dev\|ift` | **никакой машинной проверки, что `base-url-ref` указывает не на пром**: значение приезжает из переменной окружения потребителя |
| SEC-02 | Нет разрушающих действий при разведке | `DOCUMENTED_ONLY` | правило §5 UI-guardrails; кейс `ui-non-reversible-bulk-delete` | по признанию самих правил — поведенческое правило, опирается на SEC-10 |
| SEC-03 | Только тестовые данные | `PARTIALLY_IMPLEMENTED` | для БД — write-guard и undo-log; для UI — правило «действуй только на своих строках» | вычистка UI-данных отсутствует (BR-24) |
| SEC-04 | Секреты только через secret-ref | `IMPLEMENTED` | `SecretReferences`; `literal://` в пользовательском конфиге отвергается fail-closed; `*-ref` обязан быть голым именем; детектор 2 кита ловит `"password": "…"` (проверено: срабатывает на нагрузке кейса `ui-secret-in-case-text`) | пересказанный прозой секрет машиной не ловится |
| SEC-05 | Маскирование до снятия артефакта | `NOT_IMPLEMENTED` | артефактов не снимается вовсе | зависит от BR-21 |
| SEC-06 | Гейт безопасности после генерации | `PARTIALLY_IMPLEMENTED` | `record-gate` перезапускает детерминированную половину и не пишет `PASS` поверх блокирующей находки; субагент-ревьюер объявлен | **в `detectors.json` 0 UI-специфичных находок** (18 всего, над java применимы 12), поэтому сегодня все 20 гейтов U1…U20 проверяются глазами. Сколько из них выразимо — измерено spike `UITG-SP003` ([`30-ui-gate-expressibility-spike.md`](30-ui-gate-expressibility-spike.md)): 9 выразимы, 11 частично, 1 невыразим |
| SEC-07 | Техучётка агента с минимальными правами | `EXTERNAL_DEPENDENCY` | — | владельцы стендов |
| SEC-08 | Синтетические ПД | `PARTIALLY_IMPLEMENTED` | детектор 14 (ПД в фикстуре), работает и над прозой | подтверждение ИБ — G-4 |
| SEC-09 | Срок жизни артефактов | `NOT_IMPLEMENTED` | артефактов нет | зависит от BR-21 |
| SEC-10 | Разведка под учёткой без необратимых прав | `PARTIALLY_IMPLEMENTED` | поле есть: `UiAuthConfig.discoveryAccountRef` (строки 30, 43, 52) | **машинной проверки на стадии разведки нет** — сами правила это фиксируют: разведка не выполняет `ui.login`, поэтому сверка ростера не запускается; выделение учётки — G-5 |

## 5. Гейты старта G-1…G-6

Ни одному гейту не присвоен статус «закрыт»: подтверждающего артефакта в репозитории нет ни для
одного.

| ID | Гейт | Владелец (по BRD) | Статус | Что было бы доказательством | Что блокирует сейчас |
|---|---|---|---|---|---|
| G-1 | Байпас MFA/OTP/КАПЧА или альтернатива | ИБ + владельцы приложений | `EXTERNAL_DEPENDENCY`, **не закрыт** | письменное согласование по каждому приложению волны | вход в приложение; SDK уже отвечает отказом с указанием гейта |
| G-2 | Базовые замеры | QA-лиды | `EXTERNAL_DEPENDENCY`, **не закрыт** | таблица базовых значений KPI-1/2/3/5 | §19.1 неприменим: пороги выражены относительно базы |
| G-3 | Инфраструктура (браузеры/grid, квоты, бюджет) | Владельцы CI | `EXTERNAL_DEPENDENCY`, **не закрыт** | описание образа/grid + квоты | BR-18, NFR-09, KPI-8; локально браузеры есть (`browserTest` зелёный) |
| G-4 | Согласование ИБ по §11 | ИБ | `EXTERNAL_DEPENDENCY`, **не закрыт** | протокол согласования | разведка живого UI и хранение артефактов |
| G-5 | Пул учёток + учётка разведки | Владельцы приложений | `EXTERNAL_DEPENDENCY`, **не закрыт** | ростер по ролям в переменной окружения стенда | BR-25, BR-34, SEC-10; механизм в SDK готов |
| G-6 | Состав волны 1 + факт протягивания корреляции | QA-лиды | `EXTERNAL_DEPENDENCY`, **не закрыт** | список приложений с владельцами и отметкой про correlation | BR-13, KPI-5 |

## 6. Открытые вопросы OQ-01…OQ-13

Соответствие с внутренним реестром `../02-open-questions.md` (Q-01…Q-15) взято из самого файла.

| BRD | Внутренний | Статус | Замечание |
|---|---|---|---|
| OQ-01 | Q-08 | открыт | = G-1 |
| OQ-02 | — | открыт | состав волны 1; = G-6/Q-10 частично |
| OQ-03 | Q-11 | открыт | = G-3 |
| OQ-04 | — | открыт | хранение эталонных снимков; волна 3 |
| OQ-05 | Q-13 | открыт | = G-2 |
| OQ-06 | Q-12 | открыт | механизм очистки UI-данных; связан с D-9/BR-24 |
| OQ-07 | Q-14 | открыт | где живут сгенерированные тесты; влияет на KPI-4 |
| OQ-08 | — | открыт | SLA на ревью |
| OQ-09 | — | открыт | нужен ли декларативный трек (BR-32) |
| OQ-10 | — | открыт | размер команды и стоимость ч/мес |
| OQ-11 | — | открыт | порог недоступности стендов |
| OQ-12 | Q-09 | открыт | = G-5 |
| OQ-13 | Q-06 | открыт | окно совместимости версии реестра — при том, что механизм **реализован** |

Внутренние вопросы без близнеца в BRD: Q-01 (бинарные артефакты), Q-02 (форма компенсации), Q-03
(граница «конфигурация/сценарий»), Q-04 (перенос guardrails на `ui.*`), Q-05 (владение `Browser`),
Q-07 (версионирование схем KB), Q-15 (объём UI-кейсов набора, частично закрыт 2026-08-03).

## 7. Волна 1 по BRD §18 — фактическое состояние

| Пункт волны 1 | Статус | Доказательство |
|---|---|---|
| Модуль SDK с базовыми шагами и ожиданиями | `IMPLEMENTED` | 6 типов шагов, `Awaiter`; 189 юнит-тестов + 24 браузерных |
| Алиасы UI в реестре | `IMPLEMENTED` | `ui-applications`, версия формата 2/3 |
| Аутентификация форма/SSO | `PARTIALLY_IMPLEMENTED` | `FORM` и `STORAGE_STATE` да; `SSO` — отказ |
| Пул учёток | `IMPLEMENTED` | `InProcessAccountPool` |
| Прогон в CI headless | `PARTIALLY_IMPLEMENTED` | протокольная джоба есть (`.gitlab-ci.yml`); UI-джобы нет — G-3 |
| Локально headed | `IMPLEMENTED` | системное свойство |
| Артефакты падения с маскированием | `NOT_IMPLEMENTED` | `Attachment` текстовый |
| Кит: разведка, дизайн, авторинг, UI-правила в гейтах | `PARTIALLY_IMPLEMENTED` | 9 скиллов есть; **UI-правил в детекторах нет** |
| Покрытие: позитивный путь и валидации форм | `PARTIALLY_IMPLEMENTED` | кейсы есть, прогонов нет |
| *Выход:* замеры KPI-1, KPI-3, KPI-4, KPI-8, KPI-9 | `NOT_IMPLEMENTED` | ни один не замерен; шаблон сбора готов, **инструмент KPI-9 готов** (`kpi-locators`) |

## 8. Гейт §19.1 — применимость

| Порог | Можно ли посчитать сегодня | Почему |
|---|---|---|
| KPI-4 ≥40% | **нет** | нужен смерженный тест и снимок исходной выдачи; знаменатель = 0 |
| KPI-3 ≤5% | **нет** | нужны повторные прогоны на живом приложении |
| KPI-1 ≤50% базы | **нет** | базы G-2 не существует |
| Окупаемость ≤18 мес | **нет** | зависит от G-2 и OQ-10 |

**Гейт §19.1 в принципе неприменим до закрытия G-2.** Это свойство самого гейта: пороги выражены
относительно базы, а базы нет.
