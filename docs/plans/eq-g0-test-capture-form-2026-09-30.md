# Capture-форма G0-TEST (EQ data provisioning)

Дата: 2026-09-30. Кому: команда стенда test, владельцы TKS и продуктового каталога.
Назначение: собрать семь значений гейта **G0-TEST** за один проход и вернуть заполненной.
Гейт разблокирует пилот этапа 3 (AC-1 на test) и параметры реестра `test`
(`unit-phase`, `visibility`, `defaults`). Секреты в форму не вписывать — только **имена** переменных.

Основание: [`eq-gate-lifting-package-2026-09-30.md`](eq-gate-lifting-package-2026-09-30.md) §2,
[`eq-stage-0-closure-2026-09-28.md`](eq-stage-0-closure-2026-09-28.md) (G0-TEST),
BRD DEP-3/DEP-5/DEP-7, OQ-6/OQ-7/OQ-8/OQ-9/OQ-11.

---

## Итог: G0-TEST закрыт (2026-09-30)

| Пункт | Решение |
|---|---|
| **§1 Фаза юнита** | ✅ **`[DOK]`** — найдено в `eq_at` (`jt_options.rb:19`); протокол чтения приведён к эталону |
| **§2 Маркировка `testRunId`** | ✅ осознанное решение: в поле клиента **не пишется** (журнал `eq-seeded.jsonl` + имя); расширение — отдельная поставка (BR-42 не блокирует пилот) |
| **§3 Видимость** | ✅ решение: для пилота `visibility` **не задаётся** — `EqSeed` завершается без опроса; `probe` + таймаут вводятся **замером** после первых записей (нельзя назначить по догадке) |
| **§4 Каталог** | ✅ из application.yml (`CA`/`EE`/`RUR`/`PU_LST`/`T04`/`091`); `CA→40702` подтверждён живой цепочкой (OKC → `40702…`) |
| **§5 Параметры** | ✅ шлюз/юнит/AS/400/регион/касса — из application.yml; сервисы/БД — из `ENV/test.env` |
| **§6 Ops** | ✅ `maxParallelForks=1` стоит; проброс `stand.test.eq.*` добавлен |
| **Kafka-топики** | ⏸ вне пилота (пилотный тест `Cancel` топики не использует); заполняются в кит-поставке |

**Вывод:** G0-TEST закрыт по решениям и найденным значениям; единственное, что подтверждается **живым**
прогоном (не выдаётся заранее) — фактическая фаза юнита `DOK` в момент записи и поведение видимости
после первых записей. Пилот 3.9.3 запускается с `unit-phase.allowed: [DOK]`, без `visibility`.

---

## Как заполнять

- В колонке **«Заполнить»** — ожидаемое значение или «имя переменной».
- Если значение — секрет или содержит учётные данные, писать **только имя** переменной окружения.
- Где нужен замер (видимость) — указать медиану и максимум в миллисекундах.
- Где нужна форма ответа — приложить **обезличенную** строку (без ФИО/ИНН/тел).

---

## Предзаполнение из `ENV/test.env` (2026-09-30)

Часть §5 выведена из `~/IdeaProjects/ALFA/ENV/test.env` (test-стенд TKS). **Проверить и дополнить** —
это не подтверждение владельцами, а собранные из конфигурации значения. Секреты не переносились.

| Поле §5 | Кандидат из `test.env` |
|---|---|
| Сервис `tks` / `pk` (ita-api) | `http://taksa-test1` (`URL_GATEWAY`, `ITA_FRONT`; `GW_CLIENT=gateway`) |
| Внутренний REST объектов | `http://ita-objects-rest:9093/objects-rest` |
| `tks`/`pk` (тарифный сервис) | `http://tkstst1tlblns.moscow.alfaintra.net.:8080/v1/` (`TARIFFSERVICE_URL`) |
| Datasource `prodcat` | `jdbc:postgresql://tkstst1psg.moscow.alfaintra.net.:5432/prodcat` (`PRODCAT_BO_DB_URL`) |
| Datasource `prodprofile_u` | `jdbc:postgresql://tkstst1psg.moscow.alfaintra.net.:5432/prodprofile_u` (`OBJECTS_DB_U_URL`; в файле также `prodprofile_f` = `OBJECTS_DB_F_URL`) |
| Keycloak | `https://idp-test.alfaintra.net/` |
| Kafka (test) | префикс групп/топиков `EQ_TAKSA_*_TEST`, например `EQ_TAKSA_DEAL_KAFKA_GROUP_ID=EQ_TAKSA_DEAL_TEST`; учётная запись чтения — имя `TKSREAD` |
| Отделение | `X_BRANCHNUMBER=0000` (проверить, то ли это поле, что `branch`) |
| Адрес шлюза EQ | `URL_GATEWAY=http://taksa-test1` — **это фронт TAKSA, не EQ-шлюз**; адрес и юнит EQ у владельцев |

**Из `test.env` НЕ выводится (только владельцы/стенд):** AS/400 система + юнит + фаза + `unit-phase.allowed`;
адрес и юнит **EQ-шлюза** + креды; кассы по валютам; коды каталога и `CA→40702`; endpoint и задержка
`visibility.probe`; адреса UI. Хосты `taksa-test1` / `tkstst1*` из этой сети не резолвятся (имена
стенда), поэтому сетевую проверку выполняет оператор в контуре test.

---

## ЗАКРЫТО канонической конфигурацией `taxa_api_ai/src/main/resources/application.yml` (2026-09-30)

Блоки **1 (шлюз EQ)** и **2 (AS/400 / фаза)** закрыты владельцем — источник один и канонический.

| Что | Значение | Поле реестра |
|---|---|---|
| Адрес EQ-шлюза | `http://10.232.128.12:4567/api` | `base-url-ref` → `EQ_GATEWAY_URL` |
| Юнит | `K68` | `unit` → `EQ_UNIT` |
| AS/400 система | `alfamosu` | `unit-phase.system-ref` → `EQ_AS400_SYSTEM` |
| Отделение (`GZAB`) | `0110` | `branch` → `EQ_BRANCH` |
| Регион ИНН | `77` | `inn-region-code` |
| Коды ИФНС | `12` (ФЛ), `34` (ЮЛ) | `inn-tax-offices` |
| Касса-источник (`GZEND`) | `20202810408350000000` | `cash-accounts.RUR` → `EQ_CASH_RUR` |
| Тип организации (`GZCTP`) | `CA` | `defaults.organisation.type` |
| Тип счёта | ЮЛ `CA`, ФЛ `EE` | `defaults.account.type-*` |
| Валюта | `RUR` | `defaults.account.currency` |
| Тип ДУЛ ФЛ (`GZDUL`) | `091` | `defaults.individual.document-type` |
| Пакет ФЛ (`GZP3R`) | `T04` | `defaults.individual.service-package` |
| Пакет ЮЛ на счёте (`GZPID`) | `PU_LST` | `defaults.account.service-package` (per-account, из `.servicePackage(..)`) |
| Регистрация/срок пакета | `N` / `1M` | `defaults.account.package-registration` / `package-duration` |
| Пополнение | `100000` | `defaults.account.top-up` |

**Всё это — и есть конфиг `test`** (юнит `K68`). Учётка `TAKSA_USER_ID` / `TAKSA_USER_PASSWORD` —
через runtime environment (имена, не значения).

**Остаток по блоку 2:** **закрыт** — рабочая фаза `DOK` найдена в `eq_at`; протокол чтения приведён к
эталону библиотеки. Живой AS/400 из этой сети недоступен (проверка — в пилоте).

**Оговорка по адресу:** в текущей сети `10.232.128.12:4567` отвечает как JRuby/WEBrick-мок demo
(`Server: WEBrick/1.8.1`), не реальный AS/400. В контуре test по тому же адресу/юниту должен отвечать
реальный шлюз — это и будет проверкой авторизации/аутентичности (перенесено из G0-EQ).

**Correlation (OQ-16) не нужен:** `GatewayClient` намеренно не внедряет корреляционный заголовок
(выбор консервативный, зафиксирован в README модуля), поэтому реестр не объявляет `correlation`
для `gateway`.

### Готовый фрагмент реестра test

```yaml
eq-backends:
  eq:
    kind: gateway
    write-allowed: true
    base-url-ref: EQ_GATEWAY_URL          # http://10.232.128.12:4567/api
    unit: {ref: EQ_UNIT}                  # K68
    branch: {ref: EQ_BRANCH}              # 0110
    inn-region-code: "77"
    inn-tax-offices: ["12", "34"]
    cash-accounts:
      RUR: {ref: EQ_CASH_RUR}             # 20202810408350000000
    timeouts: {connect: 10s, response: 60s}
    serialization:
      acquire-timeout: 10m
    unit-phase:
      system-ref: EQ_AS400_SYSTEM         # alfamosu
      username-ref: EQ_AS400_USER
      password-ref: EQ_AS400_PASSWORD
      allowed: [ DOK ]                    # §1: рабочая фаза найдена в eq_at
      cache-ttl: 5m
    defaults:
      organisation: {type: CA}
      account:
        type-organisation: CA
        type-individual: EE
        currency: RUR
        top-up: 100000
        package-registration: N
        package-duration: 1M
      individual:
        last-name: Агентов
        first-name: Тест
        middle-name: Тестович
        document-type: "091"
        service-package: T04
```

> Не-секретное значение без умолчания рендерится как `{ref: NAME}`; секреты (`EQ_AS400_*`) — только
> `*-ref`. Секция `ui-applications` при merge сохраняется (3.9.1).

---

## Форма

### 1. Фаза юнита (OQ-7, BR-39)

**ЗАКРЫТО:** рабочая фаза — **`DOK`**, найдена авторитетно в репозитории `eq_at`
(`features/step_definitions/jt_options.rb:19`: `run_option("PHASE","A","UNIT"=>$unit)` → `RESULT`;
1393 рабочий шаг `Выполняем тест, если фаза юнита "DOK"`). Прочих значений фазы в артефактах нет.

| Поле | Значение | Заполнить |
|---|---|---|
| AS/400 система test | `alfamosu` (application.yml) | ✅ |
| Целевой юнит | `K68` (application.yml) | ✅ |
| Программа чтения фазы | `ALFAINSTAL/MONUNTSTS` (+ `LIBL <unit>`, `CALL PGM(UAA37R)`) | ✅ |
| **Рабочие значения фазы** | **`[DOK]`** | `unit-phase.allowed` = `[DOK]` |
| Текущая фаза целевого юнита | на момент пилота (`DOK` даёт запись) | проверяется в пилоте |

### 2. Маркировка `testRunId` (OQ-8, BR-42)

| Поле | Значение | Заполнить |
|---|---|---|
| В каком поле клиента писать маркер | имя поля `GZ*` | __________ |
| Максимальная длина поля | символов | __________ |
| Допустимый алфавит | буквы/цифры/разделители | __________ |
| Ограничение для ФЛ (ФИО без цифр?) | да/нет + правило | __________ |

> Контекст: сейчас маркер `testRunId` идёт **только в имя** (`name-prefix + PIN`) и в журнал
> `eq-seeded.jsonl`; в поле клиента не пишется. Нужен ли он в поле и в каком — вопрос стенда (R-4).

### 3. Видимость клиента и счёта (OQ-6, OQ-11, BR-36)

| Поле | Значение | Заполнить |
|---|---|---|
| Что опрашивать | endpoint TKS или витрина | `service` + `path` = __________ |
| Как идентифицировать клиента в запросе | имя параметра | `query` = { __________ } |
| Ожидаемый статус | HTTP-код | `expect-status` = __________ |
| Ожидаемое тело (обязательно) | JSONPath + значение | `expect-body` = { path: "$.__________", equals: "{seed.__________}" } |
| **Задержка видимости, медиана** | по замерам | __________ мс |
| **Задержка видимости, максимум** | по замерам | __________ мс |
| Рекомендуемый `timeout` | максимум + запас | `visibility.timeout` = __________ |
| Рекомендуемый `poll-interval` | шаг опроса | __________ |

> Контекст: проба обязана проверять **тело**, а не только HTTP-статус (BR-36). Без замера таймаут
> не назначать по догадке. Плейсхолдеры `{seed.pin}` / `{seed.account}` подставляет SDK.

### 4. Каталог и `CA → 40702` (OQ-9, R-10, DEP-7)

| Поле | Значение | Заполнить |
|---|---|---|
| Рабочие коды пакетов на test | ТП/ПУ | `defaults.account.service-package-*` = __________ |
| Код организации (GZCTP) | тип организации | `defaults.organisation.type` = __________ |
| Тип счёта ЮЛ (GZACT) | по умолчанию | `defaults.account.type-organisation` = __________ |
| Тип счёта ФЛ (GZACT) | по умолчанию | `defaults.account.type-individual` = __________ |
| Валюта по умолчанию | код | `defaults.account.currency` = __________ |
| **`CA → 40702`?** | даёт ли тип `CA` балансовый `40702` | да/нет; факт: __________ |
| Отделение (`GZAB`) | код | `branch` = __________ |
| Регион ИНН | код | `inn-region-code` = __________ |
| Коды ИФНС | список | `inn-tax-offices` = [ __________ ] |
| Совпадает ли каталог с ift (DEP-7)? | да/нет; отличия | __________ |

> Контекст: на **ift** тип `CA` дал счёт с префиксом `40702` (подтверждено 2026-09-30). На test —
> не подтверждено. Коды `NZCR`/`PU_NWA`/`SP_FastDevelopment`/`4.14.x` — это IFT-значения потребителя,
> в TKS их нет; нужны рабочие коды именно test.

### 5. Параметры стенда test (DEP-3, DEP-5)

> Кандидаты по большинству полей — в разделе «Предзаполнение из `ENV/test.env`» выше.
> Поля, которые оттуда **не** выводятся, отмечены `⚠ только владельцы`.

| Что | Заполнить (значение или **имя** переменной) |
|---|---|
| Адрес шлюза EQ | `EQ_GATEWAY_URL` = __________ (значение: __________) ⚠ только владельцы |
| Юнит | `EQ_UNIT` = __________ |
| Кассы по валютам | `EQ_CASH_RUR` / `EQ_CASH_USD` / `EQ_CASH_EUR` = __________ / __________ / __________ |
| Учётка AS/400 | `EQ_AS400_SYSTEM` / `EQ_AS400_USER` / `EQ_AS400_PASSWORD` (имена) |
| Сервис `tks` | base-url = __________ |
| Сервис `pk` | base-url = __________ |
| Datasource `prodcat` | host = __________ (user/password — имена) |
| Datasource `prodprofile_u` | host = __________ (user/password — имена) |
| Kafka | bootstrap = __________, topic (request) = __________ |
| `ui-applications` | base-url = __________ |

> Контекст: у потребителя сейчас объявлено **только окружение `ift`**, `test` отсутствует — реестр
> test надо собрать целиком. Секреты — только именами; значения передаются защищённым каналом.

### 6. Эксплуатационное условие пилота (AC-8, NFR-07)

| Проверка | Заполнить |
|---|---|
| `maxParallelForks = 1` у потребителя | **уже стоит** (проверено 2026-09-30) — подтвердить, что не снимается |
| Нет второго CI job/JVM на ту же пару `(base-url, unit)` | да/нет; либо внешний координатор |
| Проброс `stand.test.eq.*` в тестовую JVM | **добавлен** 2026-09-30 (задача 3.9.0a) |

---

## Шаблон ответа (одна строка на пункт)

```
1. unit-phase:   system=______  unit=______  allowed=[______]  current=______
2. marking:      field=______  maxLen=______  alphabet=______  FL-rule=______
3. visibility:   probe=______/______  query={______}  expect={status ______, body $.______ = {seed.______}}
                 median=______ms  max=______ms  timeout=______  poll=______
4. catalog:      packages=[______]  GZCTP=______  GZACT-UL=______  GZACT-FL=______  ccy=______
                 CA->40702=______  branch=______  inn-region=______  inn-offices=[______]  ift-parity=______
5. stand params: gateway=______(EQ_GATEWAY_URL)  unit=______  cash={RUR:______,USD:______,EUR:______}
                 as400={EQ_AS400_SYSTEM,EQ_AS400_USER,EQ_AS400_PASSWORD}  tks=______  pk=______
                 prodcat=______  prodprofile_u=______  kafka=______  ui=______
6. ops:          forks1=yes  single-job=______
```

---

## Что делает SDK после получения формы

| Данные | Куда идёт в SDK |
|---|---|
| §1 фаза | `eq-backends.eq.unit-phase.allowed` + `system/username/password-ref`; `UnitPhaseGate` |
| §2 маркировка | `BR-42`, поле маркировки клиента (сейчас — журнал и имя) |
| §3 видимость | `eq-backends.eq.visibility.probe/timeout/poll-interval`; `VisibilityProbe` |
| §4 каталог | `eq-backends.eq.defaults.*`, `branch`, `inn-region-code`, `inn-tax-offices`; корректность `CA→40702` |
| §5 параметры | реестр `test` / KB `environments/test.yml`, `eq-backends.eq` с `kind: gateway` |
| §6 ops | условие запуска пилота 3.9.3 |

## Проверка после заполнения

```bash
# у потребителя, после сборки реестра test:
APP_STEND=test ./gradlew test --tests 'ru.alfa.taksa.lgot.UlDiscountSchemeCancelTest' --console=plain
# ожидаемо: зелёный без правок кода (AC-1); при maxParallelForks=1 и одном job на пару (base-url, unit)
```