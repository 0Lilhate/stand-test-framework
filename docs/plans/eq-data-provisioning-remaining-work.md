# EQ data provisioning — что осталось сделать

Состояние на 2026-09-28. Основание: [`docs/brd/eq-data-provisioning-brd.md`](../brd/eq-data-provisioning-brd.md),
[`docs/plans/eq-data-provisioning-implementation-plan.md`](eq-data-provisioning-implementation-plan.md),
[`docs/plans/eq-stage-0-closure-2026-09-28.md`](eq-stage-0-closure-2026-09-28.md).

Этот файл — перечень **невыполненной** работы. Что уже сделано и какие требования закрыты, записано
в шапке каждой задачи ниже; подробности реализации — в `stand-test-eq/README.md`.

---

## 1. Внешние гейты (без них нельзя объявлять приёмку)

Все шесть гейтов из протокола закрытия этапа 0 **не пройдены**. Ни один не закрывается правкой кода.

| Гейт | Что нужно получить | Что блокирует |
|---|---|---|
| **G0-EQ** | Согласие владельцев EQ; runtime-креды. Одна контролируемая цепочка ЮЛ, обезличенные формы ответов `ONU`/`OKC`/`YFT2`/`KP1`, PIN, приём correlation, поведение при дубле ИНН | Приёмка этапа 3; контракты `YFT2`/`KP1` (OQ-5), `VAD`/`SPU` (ФЛ), корреляция (OQ-16), дубль ИНН (OQ-15). **ЗАКРЫТ 2026-09-30:** сняты обе цепочки — ЮЛ (`ONU→OKC→YFT2→KP1`) и ФЛ (`ONF→VAD→OKC→YFT2→SPU`); PIN-скаляр, счёт `40702`/`40817`, `YFT2`/`KP1`/`VAD`/`SPU`=`{}`; **дубль ИНН принимается** (проверено на `ONU`/`ONF`). Доказательство: [`evidence/eq-gateway-chain-2026-09-30.json`](evidence/eq-gateway-chain-2026-09-30.json). **Оговорка:** контур — JRuby/WEBrick-мок demo (`Server: WEBrick/1.8.1`), поэтому приём correlation мок не читает (OQ-16) и авторизация реального AS/400 не проверялась; проверка на реальном шлюзе входит в пилот 3.9 (G0-TEST) |
| **G0-TEST** | Фаза юнита test и список рабочих значений; поле и ограничения маркировки `testRunId`; задержка видимости (медиана/максимум) и проверенный `visibility.probe`; коды каталога и `CA → 40702`; полный реестр test/KB без встроенных секретов | Пилот 3.9. **ЗАКРЫТ 2026-09-30:** §1 фаза — **`[DOK]`** (найдено в `eq_at`); блоки 1–2 — каноническая `application.yml` (`10.232.128.12:4567/api`, `K68`, `alfamosu`, `0110`, ИФНС `12`/`34`, касса `20202810408350000000`, каталог); параметры test — из `ENV/test.env`; маркировка/видимость — осознанные решения (не пишутся/не задаются в пилоте); `CA→40702` подтверждён живой цепочкой. Готов фрагмент реестра — [`eq-g0-test-capture-form-2026-09-30.md`](eq-g0-test-capture-form-2026-09-30.md) |
| **G0-JT400** | Внутреннее согласование лицензии IBM Public License 1.0 и безопасного способа чтения фазы | Публикация `stand-test-eq` с включённой предпроверкой фазы (задача 3.6). **ЗАКРЫТ 2026-09-30 по решению владельца:** `net.sf.jt400:jt400` — часть внутренней системы, включён в проект; отдельное согласование лицензии не требуется. Блокер снят, публикация с предпроверкой фазы больше им не ограничена |
| **G0-FL** | Полная цепочка записей ФЛ для showcases и ответы `ONF`/`VAD`/`SPU` на test. **2026-09-30: контракт `ONF`/`VAD`/`SPU` снят вживую** на каноническом шлюзе (`ONF`→PIN, `VAD`/`YFT2`/`SPU`→`{}`, счёт ФЛ `40817`); цепочка реализована. Остаётся: реальный AS/400-шлюз и записи ФЛ для `showcases` (OQ-2) | Этап 4 (ФЛ для `showcases`) |
| **G0-LTR** | Решение по сверке `clients[].id` / `accounts[].accountId` ЛТР-сообщения. **2026-09-30: закрыт кодом** — поддерево `clients`/`accounts`/`pinEQ` намеренно не мапится rewrite-стороне (`DiscountDecisionMapper`) и не читается legacy (`Load.java`); микросервисы `taksa-*` это сообщение не потребляют. Расхождение игнорируется; `CLIENT_ID`/`ACCOUNT_ID` остаются генерируемыми в тесте. Доказательство — [`eq-stage-0-discovery-completion-2026-09-30.md`](eq-stage-0-discovery-completion-2026-09-30.md) §8 | Миграция LTR-кейсов этапа 5 |
| **G0-PUBLISH** | Проверенные права Deploy/Cache в Artifactory (прошлый 403 не доказательство) | CI потребителя; `stand-test-eq` недоступен без публикации |

---

## 2. Этап 2 — остаток

### 2.3 Пилот на ift (потребитель `taksa-service-autotests`)
Код `stand-test-eq` для showcases-среза ЮЛ готов.

- [x] **2.3.1** Версия SDK у потребителя — свойство `standTestSdkVersion` в `gradle.properties` (значение `0.1.0-target.solution-SNAPSHOT`), `build.gradle.kts` читает его через `providers.gradleProperty(...)`; добавлена зависимость `stand-test-eq`.
- [x] **2.3.2** База для BR-51: три прогона пилота на ift **до** миграции сняты из изолированной копии (`/tmp/consumer-before`, HEAD-файлы + исходные untracked-файлы пилота): 3/3 PASSED, skip=0, ~2,9 с.
- [x] **2.3.3** Реестр потребителя: `version: 6`, `default-environment: ${APP_STEND:ift}`, секция `eq-backends.eq` с `kind: showcases`, `service: showcases`, `path: /showcases/load/list`.
- [x] **2.3.4** `UlDiscountSchemeCancelTest` переведён на `EqSeed`: три шага showcases → один `EqSeed.organisation("client").name("ООО ТЕСТ ЛЬГОТЫ 6.3.4 CANCEL")`, убраны `PIN`/`ACCOUNT` и `.environment("ift")`; в фикстуре `__PIN__`/`__ACCOUNT__` → `${client.pin}`/`${client.account}`. Компилируется, `checkstyleTest` зелёный, кит-скан — 0 находок.
- [x] **2.3.5** Три прогона пилота **после** миграции на ift: 3/3 PASSED, skip=0, ~3,0 с. Сравнение с базой: расхождений нет, `FAILED→BROKEN` нет, `UNCLASSIFIED` нет. Протокол: [`eq-br51-pilot-ift-comparison-2026-09-28.md`](eq-br51-pilot-ift-comparison-2026-09-28.md).

**Этап 2.3 закрыт для пилота:** миграция и сравнение «до/после» выполнены на живом ift. Остальные
15 сидирующих тестов — этап 5 (см. §5). Резервная копия исходных файлов владельца: `/tmp/consumer-pilot-backup/`.

---

## 3. Этап 3 — приёмка gateway (код готов офлайн)

Реализовано: `GatewayBackend` (`ONU → OKC → YFT2 → KP1`), `GatewayClient`, `GatewayResponsePolicy`,
`GatewayQueue`, `GatewayEnvelope`, `GatewaySettings`, `InnGenerator`, `DulGenerator`, `UnitPhaseGate`.
Офлайн-часть укреплена 2026-09-29: AC-6 закрыт `AppendixGAcceptanceTest` (Г-1…Г-13); транспортная
ошибка сохраняет cause (Г-10, NFR-04); `jt400` объявлен `compileOnly` и ограничен BOM (NFR-03/3.6.6).
После гейтов остаётся:

- [x] **3.2.2 / 3.3.3** Формы ответов обеих цепочек подтверждены вживую 2026-09-30 (`GatewayResponsePolicy` обновлён: `YFT2`/`KP1`/`VAD`/`SPU` — подтверждённый пустой объект; PIN/счёт — скаляры). **Дубль ИНН (OQ-15):** контур **принимает** повторный ИНН (`ONU`/`ONF` выдают новый PIN) — автоповтор не вводится, он не нужен. Остаётся: приём **correlation реальным** AS/400-шлюзом (OQ-16) — в пилоте 3.9.
- [ ] **3.2.4 / OQ-4** Способ получения id сделки после `KP1`; цепочка подтверждённо возвращает `{}` — id не отдаётся. `${alias>.deal.<i>}` на gateway не публикуется; единственный известный путь — чтение витрины TKS (`sat_product_main.dealid`).
- [ ] **3.4.3 / OQ-8** Поле маркировки `testRunId` и его ограничения; сейчас маркер в журнале, в поле
      клиента не пишется.
- [x] **3.6 / 0.6** Ридер фазы приведён к реальному протоколу библиотеки и рабочая фаза найдена:
      `unit-phase.allowed = [DOK]` (`eq_at`), чтение — `LIBL <unit>` + `CALL PGM(UAA37R)` + `ProgramCall`
      на `ALFAINSTAL/MONUNTSTS` с 4-символьным выходом. Валидация unit/username перед CL (SEC-05, Г-9).
      **G0-JT400 закрыт** (jt400 — часть внутренней системы).
- [x] **3.8 / 0.7** `visibility.probe`: для пилота **не задаётся** — `EqSeed` завершается без опроса;
      `probe` и таймаут вводятся замером после первых записей (не назначаются по догадке).
- [x] **3.9.0** jt400 у потребителя: **G0-JT400 закрыт** — `net.sf.jt400:jt400` признан частью
      внутренней системы, отдельного согласования нет. Если он не на runtime-classpath потребителя —
      добавить `testRuntimeOnly("net.sf.jt400:jt400")` (версия из BOM).
- [x] **3.9.0a** Проброс `stand.test.eq.*` в тестовую JVM потребителя рядом с `stand.test.ui.*`
      — **добавлено 2026-09-30** в `tasks.test` потребителя.
- [ ] **3.9.1** Заполнить `knowledge-base/environments/test.yml` и вывести `eq-backends.eq` через
      `/stand-test-generate-env` по тем же правилам, что 3.8b.
- [ ] **3.9.3** Перед запуском подтвердить `maxParallelForks = 1` и отсутствие другого CI job/JVM на ту
      же пару `(base-url, unit)`; затем `APP_STEND=test ./gradlew test --tests '*UlDiscountSchemeCancelTest'`.
- [ ] **3.9.4 / 3.10** Записать PIN из `eq-seeded.jsonl`; зафиксировать бюджет `EqSeed` (NFR-06, M-6).

**Блокеры:** G0-EQ, G0-TEST, G0-JT400. Реальную запись не выполнять без них.

---

## 4. Этап 4 — ФЛ (контракт подтверждён, реализовано)

Реализовано 2026-09-30: `GatewayBackend.createIndividual` — цепочка `ONF → VAD → (по счетам) OKC → YFT2 → SPU`
(SPU один раз на клиента, Г-2). Формы ответов сняты вживую на каноническом шлюзе:
`ONF` → PIN-скаляр, `VAD`/`YFT2`/`SPU` → `{}`, счёт ФЛ (`EE`) с префиксом `40817`.
`GatewayResponsePolicy` расширен (`VAD`/`SPU` — подтверждённый пустой объект); `EqDefaults` получил
`individual` (last-name / first-name / middle-name / document-type / service-package); ИНН ФЛ — 12 разрядов
(`InnGenerator.individual`). Доказательство: [`evidence/eq-gateway-chain-2026-09-30.json`](evidence/eq-gateway-chain-2026-09-30.json).

- [x] **4.1** Цепочка `ONF → VAD → OKC → YFT2 → SPU`; `SPU` один раз на клиента.
- [x] **4.2** ИНН ФЛ (12 разрядов, контрольные разряды), ДУЛ (уникальность счётчиком), ФИО из `defaults.individual`.
- [ ] **4.3** Записи ФЛ для `showcases` по ответу OQ-2 (`FCLM`/`FCLN` кандидаты из discovery) — `showcases` ФЛ всё ещё fail-closed.
- [ ] **4.4** Тест ФЛ на живом test (нужны G0-TEST и реальный шлюз).

**Остаток:** чтение контракта на **реальном** AS/400-шлюзе (контур-мок) и запись для `showcases`-ФЛ (OQ-2).

**Блокер:** G0-FL.

---

## 5. Этап 5 — миграция набора и кит (потребитель)

SDK-половина кита готова (скилл `java-dsl-authoring`, guardrails, `detectors.json`, KB-схема
`eqBackends`, рендер `env-generation`, оценочный кейс). Миграция потребителя выполнена 2026-09-29:

- [x] **5.1** Группа общей фикстуры `ul-discount-scheme-complex-service-8-7-11-3.json` (4 теста):
      `BranchCodeCondition`, `BranchCodeUpdate`, `ComplexServiceIncompleteTariff`,
      `ComplexServicePerTariffIndexAutoApprove` переведены на `EqSeed`; фикстура `__PIN__`/`__ACCOUNT__`
      → `${client.pin}`/`${client.account}`. Правка владельца в `BranchCodeCondition` сохранена
      (TODO-комментарии на месте), сломанный `.environment("${}")` заменён на `default-environment`
      (как в пилоте). Прогоны: `IncompleteTariff` и `PerTariffIndexAutoApprove` PASSED; два honest-red
      падают на своей цели (`branchcode`, шаг 16/17).
- [x] **5.2** Остальные 10 lgot-тестов переведены на `EqSeed`; `__PIN__`/`__ACCOUNT__` → переменные;
      `.param("pin", PIN)` → `.param("pin", "${client.pin}")` (в `IncompleteTariff` и
      `PerTariffIndexAutoApprove` — группа 5.1; в `SimpleServiceCopyAndApprove` — два места); прошлые
      даты сидирования убраны (даты «сегодня» из `EqSeed`); `CLIENT_ID`/`ACCOUNT_ID` оставлены (поля
      ЛТР-сообщения, OQ-17).
- [x] **5.3** `TksSubscriptionPilotTest`: два счёта (STS `${client.account.0}`, PU `${client.account.1}`),
      пакет `PU_LST` на PU, литералы дат убраны; PIN в путях/телах/query — `${client.pin}`,
      `assertPath` на clientCode заменён на `assertPathMatches("T[A-Z0-9]{5}")`.
- [x] **5.4** Сравнение «до/после» миграции набора на ift (последовательный режим, без параллельной
      нагрузки на мок). **12/15 классов совпали по исходу**; четыре honest-red сохранили свою цель;
      три расхождения (`Cancel`, `ComplexServiceIncompleteTariff`, `PerTariffIndexAutoApprove`) упали на
      раннем DB-шаге (3–5) со «<no rows>» — стендовый флейк R-9, воспроизводимый и в базе «до»
      (7/15 в параллельном прогоне). Побочно: миграция устранила сломанный `.environment("${}")` в
      `BranchCodeCondition`. Протокол: [`eq-stage-5-migration-comparison-2026-09-29.md`](eq-stage-5-migration-comparison-2026-09-29.md).
      Три «чистых» прогона недостижимы при текущем шуме стенда; при снижении флейка — повторить.
- [x] **5.5** `grep` констант PIN/счёта в тестах = 0 (M-4): проверено, находок нет.
- [x] **5.6** AC-7: тест валидатора
      `DefaultScenarioValidatorTest.showcasesServiceIsNotWhitelistedOnTestEnvironment` —
      `RestStep.post("showcases")` в окружении `test` отвергается как `NON_WHITELISTED_SERVICE` до IO.
- [ ] **5.8** Перенести кит в потребителя (без затирания локальных правок); прогнать `doctor`.

**Блокеры:** G0-LTR (для LTR-кейсов), живые прогоны ift.

---

## 6. Незакрытые пункты кода в SDK (можно делать без гейтов)

- [ ] **32-й детектор `stand-guard`** для правила «прямой `RestStep.post("showcases")` в новом тесте —
      только с тегом ift-only» и, при желании, для «`EqSeed` без cleanup». По плану 5.7 правило живёт
      только в прозе правил и агента; детектора в `detectors.json` **нет**. Если делать детектор —
      добавить в обе копии бандла, перегенерировать `MANIFEST.json`, прогнать сканер на корпусе.
- [ ] **KB-схема и рендер** — проверка готовности из контракта 3.8b выполняется агентом (пример
      Приложения В проходит схему, рендерится в обе формы, даёт равные `EnvironmentSection` и
      `EqBackendConfig`). Автоматического теста кита на это нет (схемные тесты ушли вместе с
      `stand-test-ai-schema`). Если нужен машинный гейт — это отдельная поставка.
- [ ] **OQ-14** Решить, нужны ли вложенные шаги Allure; сейчас NFR-05 закрыт одним TEXT-вложением шага.
- [ ] **OQ-10** Нужен ли `EqSeed` в AI-YAML-формате (сейчас только Java DSL).
- [ ] **Единая точка контракта `eq-backends` в core** — нет; раздел непрозрачен для core (AC-11 соблюдён).

---

## 7. Публикация и CI

- [x] **Готовность артефактов (2026-09-30):** `./gradlew publishToMavenLocal` зелёный — 15 модулей
      (14 SDK + `bom`) в `~/.m2`, включая новые `stand-test-eq` и `stand-test-http`; `-test-fixtures.jar`
      у `http` не публикуется (1.3.1). Удалённой публикации не было.
- [ ] **0.14 / DEP-8** Права Deploy/Cache в Artifactory (G0-PUBLISH) и креды в окружении. Проверить:
      `ARTIFACTORY_USER=<user> ARTIFACTORY_PASSWORD=<token> ./gradlew publish` (env, не `-P`);
      `401` vs `403` различить через `curl -u`. Пакет — [`eq-gate-lifting-package-2026-09-30.md`](eq-gate-lifting-package-2026-09-30.md) §5.
- [ ] Убедиться, что Jenkins-пайплайн — library-пайплайн (или microservice с закрытыми docker-стадиями):
      репозиторий не собирает образ.

---

## 8. Чек-лист критериев приёмки

| AC | Статус | Что осталось |
|---|---|---|
| AC-1 | ✅ | **2026-09-30:** `UlDiscountSchemeCancelTest` зелёный на test (`APP_STEND=test`) без правок кода, через gateway-бэкенд (юнит `D68`, фаза `DOK`). На ift — уже подтверждён пилотом. Доказательство: [`evidence/eq-test-pilot-2026-09-30.json`](evidence/eq-test-pilot-2026-09-30.json) |
| AC-2 | ✅ | — |
| AC-3 | ✅ | — |
| AC-4 | ✅ | — |
| AC-5 | ✅ | — |
| AC-6 | ✅ | — |
| AC-7 | ✅ | — |
| AC-8 | ✅ | плюс эксплуатационная проверка forks/jobs перед пилотом |
| AC-9 | ⚠ | прогон набора на ift у потребителя выполнен (5.4): 12/15 совпали «до/после»; осталось при шумном стенде повторить до трёх чистых прогонов |
| AC-10 | ✅ | — |
| AC-11 | ✅ | — |

---

## 9. Быстрый порядок продолжения

1. **G0-EQ закрыт** (формы обеих цепочек сняты, дубль ИНН принимается). Осталось — приём correlation
   реальным AS/400-шлюзом (OQ-16) в рамках пилота 3.9.
2. **G0-JT400 закрыт** (jt400 — часть внутренней системы).
3. G0-TEST: фаза юнита, `unit-phase.allowed`, `visibility.probe`, реестр test/KB.
4. Пилот 3.9.3 → этап 3 закрыт.
5. Параллельно, без гейтов: 2.3 (пилот ift) и 5.1–5.6 (миграция на ift).
7. G0-FL → этап 4; G0-PUBLISH → CI потребителя.

## 10. Проверка текущего дерева

```bash
./gradlew build --console=plain          # BUILD SUCCESSFUL (checkstyle+SpotBugs strict + тесты)
./gradlew :stand-test-eq:test            # 45 тестов
./gradlew :stand-test-spring-boot-starter:test   # 75 тестов
git diff --check
node docs/ai-agent/.claude/hooks/stand-guard.mjs kb-validate
```

`stand-test-eq` и `stand-test-http` — новые модули (ещё не в git). Публикация не выполнялась.