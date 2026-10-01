# Завершение разведки этапа 0: EQ data provisioning

Дата: 2026-09-30. Статус: **закрыт весь объём разведки, который достижим по локальному коду.**
Остаются пункты, требующие живого стенда test и внешних согласований (G0-EQ/G0-TEST/G0-JT400/G0-FL/G0-LTR/G0-PUBLISH).

Этот документ дополняет
[`eq-stage-0-discovery-2026-09-28.md`](eq-stage-0-discovery-2026-09-28.md) (факты стенда) и
[`eq-stage-0-closure-2026-09-28.md`](eq-stage-0-closure-2026-09-28.md) (решения по OQ). Здесь
зафиксированы ответы, снятые **чтением исходников** четырёх репозиториев:
`showcase-loader` (мок `showcases`), `taxa_api_ai` + библиотека `aiagents-taksa-starter:0.2.0`
(шлюз EQ), `taksa` (TKS), `taksa-service-autotests` (потребитель). Все ссылки — `путь:строка`.
Секреты не печатались: только имена свойств и переменных.

---

## 1. Сводка: что закрыто кодом, что осталось стенду

| # | Вопрос | Было | Стало | Тип |
|---|---|---|---|---|
| 0.1 | OQ-1, R-9 | `failed: null` не воспроизведён | **ОПРОВЕРГНУТО как код-путь мока**: поля `failed` нет ни в модели, ни в спеке, ни в одной ветке. `sentCount` не доказывает Kafka ACK | код ✅ |
| 0.2 | OQ-3, BR-30 | позиции расшифрованы наполовину | **Позиции `ACLM`/`ACLN`/`UACM` полностью сопоставлены** entitycode; балансовой суммы в кэше нет; `UACM[13]=BalAcc2` — балансовый счёт, не сумма | код ✅ (смысл 12-значного значения — владельцу) |
| 0.3 | R-12, AS-6 | доказано для `Cancel` | без изменений (живой ift закрыт ранее) | стенд |
| 0.5 | OQ-5, OQ-16, AS-3 | формы ответов неизвестны | **форма ответа снята из кода библиотеки**: тело — голая строка; PIN/счёт — скаляры, `KP1`/`SPU` = `{}` | код ✅ (живой снимок всё ещё желателен) |
| 0.6 | OQ-7 | фаза юнита неизвестна | **вызов подтверждён** (`ALFAINSTAL/MONUNTSTS`, parm0=юнит, parm1=4 символа); список рабочих значений — **нет** | код ✅ / стенд ⛔ |
| 0.8 | OQ-8 | поле маркировки неизвестно | подтверждено: маркер `testRunId` в поле клиента **не пишется**, только в имя и журнал; лимиты неизвестны | код ✅ / стенд ⛔ |
| 0.9 | OQ-4, R-2 | способ получения id сделки неизвестен | **`KP1`/`YFT2` возвращают `{}`, id сделки шлюз не отдаёт**; id берётся отдельным чтением витрины TKS (`sat_product_main.dealid`) | код ✅ |
| 0.10 | OQ-17 | не подтверждено | **ПОДТВЕРЖДЕНО**: `clients[].id`/`accounts[].accountId`/`pinEQ` ЛТР-сообщения намеренно не мапятся и не сверяются; расхождение игнорируется | код ✅ |
| 0.11 | OQ-9, R-10, DEP-7 | не сверено | **в TKS нет** кодов `NZCR`/`PU_NWA`/`SP_FastDevelopment`/`4.14.x` и отображения `CA → 40702` — это IFT-иллюстрации потребителя; парность на test — стенд | код ✅ / стенд ⛔ |
| 0.12 | DEP-3, DEP-5 | реестр test не собран | канонические ключи `application.yml` шлюза прочитаны (без значений) | код ✅ / стенд ⛔ |
| 0.2-ФЛ | OQ-2 | коды ФЛ неизвестны | активные форматы `FCLM`/`FCLN` видны в кэше; полная цепочка ФЛ — не подтверждена | частично |
| 0.13 | DEP-6 | лицензия не согласована | без изменений | владелец |
| 0.14 | DEP-8, R-13 | права 403 | без изменений | владелец |
| 0.4/0.5-живой | G0-EQ | шлюз не отвечает | без изменений (0 байт) | стенд |

---

## 2. 0.1 / OQ-1 / R-9 — контракт ответа `showcases`

**Ответ: `failed: null` не производится моком `showcase-loader`.**

- Модель ответа — ровно три поля. `showcase-loader-api/openapi/showcase-api.yaml:540-549`
  (`ShowcaseBatchResponse`: `sentCount`, `status`, `timestamp`); сгенерированная
  `…/model/ShowcaseBatchResponse.java:30-35`; `Status` = `OK`/`ERROR`
  (`…/model/Status.java:24-28`).
- `grep -rni "failed"` по `src`/`openapi` **всех веток** (`master`, `origin/dev2`,
  `origin/feature/add_new_codes`, `bugfix/FCNM`) — **ноль** вхождений в модель/спеку. Строка
  `failed` встречается только в **устаревшей AI-сгенерированной** AsciiDoc
  (`docs/asciidoc/POST_showcase_loadBatch/response.adoc:11`,
  `…/POST_showcase_loadList/response.adoc:11`), причём сам документ оговаривает, что формат
  «примерный», и использует старый путь `/showcase/loadList`.
- `sentCount` **не доказывает** доставку в Kafka: `KafkaSender` вызывает
  `kafkaTemplate().send(record)` и **отбрасывает** `CompletableFuture<SendResult>`
  (`showcase-loader-app/src/main/java/ru/alfabank/app/kafka/KafkaSender.java:13-23`), а
  `ShowcaseService` инкрементит `sent` сразу (`…/service/showcase/ShowcaseService.java:71-78`).
- 503-путь `KAFKA_UNAVAILABLE` — **мёртвый код**: `KafkaSendException` не бросается нигде
  (`…/controller/controlleradvice/DefaultAdvice.java:36-39` — только обработчик).

**Коды ответа (проверенная ветка):** 200 (`status: OK`, включая пустой список `sentCount:0`);
400 `BAD_REQUEST` (`ValidateException`, `DefaultAdvice.java:56-65`); 500 catch-all
(`DefaultAdvice.java:68-71`). Ошибка оформляется как `ApiError` (`…/model/ApiError.java:27-45`).

**Влияние на SDK.** Классификация `ShowcasesBackend` (`MOCK_REJECTED` / `HTTP_UNEXPECTED_STATUS` /
`UNEXPECTED`, без автоповтора и без публикации сырых тел) остаётся верной; наблюдённый `failed: null`
приходит **не из этого контракта** — вероятнее обёртка потребителя или другая/более старая ревизия.
Решение OQ-1 (OQ-1 из закрытия) подтверждается: неизвестную форму считать `UNEXPECTED`/`MOCK_REJECTED`,
никогда — подтверждённой доставкой.

**Остаточная неопределённость.** Живой IFT отвечает на неизвестный код **400** с формулировкой
`Витрина не найдена: businessCode=…`, которой нет ни в одной локальной ветке (в `master` — NPE→400,
в `origin/dev2` — `NotFoundException`→404). Стенд крутит ревизию свежее скачанных; точную строку
кода на живом стенде к локальной строке не привязать. Значения 400 из evidence-файла считаем
итоговым контрактом.

---

## 3. 0.2 / OQ-3 / BR-30 — позиции записей и баланс

Полное сопоставление 0-based индексов плана к entitycode подтверждено по кэшу
`showcase-loader-app/src/test/resources/file/cache/eq_taksa_{client,account}.json` и совпадает с
живыми ответами `GET /showcases/formats/{code}` (evidence-файл).

**`ACLM` v1** (`eq_taksa_client.json:3-63`, `hub_Client`): `[0]` BusinessKey(PIN), `[1]` Location,
`[2]` CommonTypeId, `[3]` ClientTypeId, **`[4]` `sat_Client_Main.INN` (char 20)**, `[5]` ResidentCountryId.

**`ACLN` v2** (`:100-161`): `[3]` SurName, `[4]` GivenName, `[5]` MiddleName.

**`UACM` v1** (`eq_taksa_account.json:341-474`): `[3]` Suffix, `[5]` CountryId, `[8]` ClosingDate,
`[9]` IsEnableFeeSU1, `[10]` IsEnableFeeSU2, `[11]` AccountGroupId, `[12]` IsHoldList,
**`[13]` `sat_Account_Main.BalAcc2` (char 5)**.

**Баланс.** Ни один формат кэша не содержит **денежной суммы**: скан по
`balance|amount|sum|rest|bal|ost|saldo` даёт только `BalAcc2` (UACM v1/v2) — это **балансовый счёт**
(`40702` и т. п.), а не сумма. `UACM` v1 денежного баланса не несёт. Следовательно значение BR-30
«баланс через `UACM` v1 — неподдерживаемо» подтверждается, а `UACM[13]` в плане — именно этот
балансовый счёт, не сумма.

**`ACLM[4]` (OQ-3).** Поле **помечено** `sat_Client_Main.INN` (char 20), но тестовые значения —
12 цифр (`618418411698` в 15 тестах; per-run `CLIENT_REG_NO` только в `SimpleServiceCopyAndApprove`),
что **не является** корректным ИНН ЮЛ (10 цифр). Смысл значения остаётся продуктовым решением:
пока — `.inn` на `showcases` не публиковать (уже так и сделано). Отдельной записи, несущей ИНН
организации или баланс, в кэше нет — ИНН есть только в `sat_Client_Main.INN` записей `ACLM`/`FCLM`/`UCLM`.

---

## 4. 0.5 / 0.12 — шлюз EQ: конверт, формы ответов, каноническая конфигурация

**Клиент — внешняя библиотека** `ru.alfabank.aiagents:aiagents-taksa-starter:0.2.0`
(`taxa_api_ai/build.gradle:58`). Локальный репро-клиент —
`taxa_api_ai/src/test/java/ru/alfabank/DRB_JT400_EQ/tests/utils/TaksaClientFactory.java:66-140`.

- **Конверт запроса** `{unit, option, params}`: `PostRequestDto(String unit, String option,
  Map<String,String> params)`; POST `application/json`.
- **Операции**: `ONF, ONU, VAD, OKC, YFT2, SPU, KP1` (`…/enums/TaksaOption.java:3-11`).
- **Цепочка ЮЛ**: `ONU` → по счёту `OKC` → `YFT2` → `KP1`
  (`…/service/TestOrganisationService.java:52-94`). Цепочка ФЛ: `ONF → VAD → (по счёту) OKC → YFT2 → SPU`.
- **Форма ответа (AS-3, OQ-5).** Тело читается как **голая `String`**
  (`…/client/TaksaClient.java:44`: `.body(String.class)`). PIN/счёт — скаляры, валидируются
  регэкспами `RequestValidator` (PIN `^[A-Z0-9]{6}$`, счёт 20 цифр, ИНН 10/12, документ 15 — `…:19-23,95-101`).
  `KP1`/`SPU` проверяются как **пустой объект `{}`** (`…/service/ResponseValidator.java:22-35`).
  Это совпадает с нормализацией `GatewayResponsePolicy.normalise` в SDK.
- **Канонический `application.yml`** — `taxa_api_ai/src/main/resources/application.yml`
  (подтверждён владельцем 2026-09-28): адрес шлюза `taksa.baseUrl` = `http://10.232.128.12:4567/api`;
  юнит `taksa.unit` = `K68`; система AS/400 `taksa.systemName` = `alfamosu`; отделение `taksa.gzab-branch`
  (по умолчанию `0110`); `taksa.inn-region-code` (`77`); касса-источник `taksa.sourceAccount` (ref
  `TAKSA_SOURCE_ACCOUNT_GZEND`); креды — только имена `TAKSA_USER_ID`/`TAKSA_USER_PASSWORD`.
- **Метод одной цепочки — подтверждён:** `AiAgentsTaksaStarterTests.testGenerateTestOrganisation()`
  вызывает `generateTestOrganisation()` **однажды**; `TestClass.testGenerateTestUser2()` — дважды.

---

## 5. 0.6 / OQ-7 — фаза юнита

Вызов подтверждён и совпадает в библиотеке и SDK: программа **`ALFAINSTAL/MONUNTSTS`**, parm0 — юнит
(3 символа), parm1 — выход 4 символа (библиотека `…/service/UnitPhaseService.java:46-132`; SDK
`stand-test-eq/…/backend/gateway/Jt400UnitPhaseReader.java:25-51`). **Списка допустимых значений фазы
нет нигде** — `unit-phase.allowed` задаётся только реестром test, а парсер лишь требует непустой
список (`…/config/GatewayConfigParser.java:88-102`). Предпроверка остаётся за G0-TEST/G0-JT400 (OQ-7).

---

## 6. 0.8 / OQ-8 — маркировка `testRunId`

Маркер `testRunId` **в поле клиента не пишется**: он сохраняется в append-only журнал
`eq-seeded.jsonl` (`GatewayBackend.java:92-93`, `report/SeedJournal.java`) и в имя клиента
(`defaults.organisation.name-prefix + ' ' + PIN`, `backend/SeedPlan.java:64-79`; PIN из
`EqIdGenerator.pin()`, `ids/EqIdGenerator.java:30-33`), попадая в `ONU` как `GZCUN`/`GZFNM1`
(`GatewayBackend.java:102-103`). **Лимиты длины и алфавита полей ввода не зафиксированы нигде** —
поле, длина и алфавит остаются за G0-TEST (OQ-8).

---

## 7. 0.9 / OQ-4 — id сделки после `KP1`

**`KP1` (и `YFT2`) отвечают `{}` — id сделки шлюз не отдаёт.** Это подтверждает и демо
(`TaksaClientFactory.java:104-112` — `if (!"{}".equals(kp1Response)) fail(...)`), и SDK
(`GatewayResponsePolicy` — только `ONU`/`ONF`→PIN, `OKC`→счёт подтверждены). Демо отдельного запроса
id не делает.

Единственный доказанный способ получить id — **отдельное чтение витрины TKS**: колонка
`sat_product_main.dealid` через join `hub_product`/`link_product_client`/`hub_client`
(`taxa_api_ai/src/test/resources/sql/queries.sql:43-71`; TKS-константа `DEAL_ID = "dealid"`,
`taksa/taksa-lib/…/constant/Constants.java:60`). Бэкенд `showcases` id **синтезирует** —
`EqIdGenerator.deal(servicePackage) = pin() + '_' + servicePackage` (`ids/EqIdGenerator.java:57-63`).

**Решение (совпадает с закрытием OQ-4):** на `gateway` `${alias.deal.<i>}` не публиковать; если тесту
нужен id — заводить отдельную поставку чтения витрины.

---

## 8. 0.10 / OQ-17 — сверяет ли TKS `clients[].id` / `accounts[].accountId`

**Подтверждено кодом: НЕ сверяет — расхождение игнорируется.**

ЛТР-сообщение `POST /…/prodcat/DiscountScheme` (`ita-api/…/income/ProdcatController.java:45-58`)
уходит во внешний ПК-поток «ПАКТ-Льготы» (`alfa-prc` rewrite + `ita-prc` legacy). Поддерево
`clients[].id` / `clients[].accountId` / `pinEQ`:

- **Намеренно не мапится** на rewrite-стороне: Javadoc `DiscountDecisionMapper`
  (`alfa-prc/alfa-prc-app/…/itaobjects/DiscountDecisionMapper.java:27-29`) прямо перечисляет
  `TariffItem.clients` и вложенные `accounts`/`pinEQ` как «unmapped source, default-ignore», в т.ч.
  потому что это PII; домен-зеркало `…/domain/DecisionItem.java:12-13` подтверждает.
- **Не читается** legacy-обработчиком: 2991-строчный `Load.java`
  (`ita-prc-all/…/prefdecision/Load.java:181-735`) не содержит `clients`/`accountId`/`pinEQ`.
- **Микросервисы `taksa-*` это сообщение вообще не потребляют**: они слушают другие бизнес-коды
  (`ACLM/UCLM/…`, `UACM/FACM`), а их DTO — позициональный массив без понятия внешнего id
  (`taksa-client/…/TaksaClientListener.java:19-27`, `taksa-account/…/TaksaAccountListener.java:19-24`,
  `taksa-lib/…/dto/TaksaMessageDto.java:26-73`). Тип клиента выводится из бизнес-кода/hub, не из
  внешнего `clients[].id` (`…/service/ClientTypeResolver.java:25-57`).

**Следствие для плана.** `CLIENT_ID`/`ACCOUNT_ID` в тестах можно оставить генерируемыми в тесте;
G0-LTR по OQ-17 может быть закрыт этим доказательством (живого прогона не требуется).

---

## 9. 0.11 / OQ-9 / R-10 / DEP-7 — каталог test

**Кодов `NZCR`, `PU_NWA`, `SP_FastDevelopment`, услуг `4.14.x` в TKS нет** (grep по всему `taksa` —
ноль). Они существуют только в артефактах потребителя `taksa-service-autotests`
(`docs/designs/408983/ScenarioDesign.md`, `knowledge-base/mappings/lgot-cases.yml`,
фикстуры `src/test/resources/fixtures/lgot/*.json`):

- `NZCR` — `creditOrgCode`, на IFT резолвится в отделение `2932`; `PU_NWA` / `SP_FastDevelopment` —
  `codeTariffsPack` («ПУ Быстрое развитие»); `4.14.x` — индексы услуг prodcat.
- Коды самого кейса (`CHERK`, `ServicePackRegion_FastDevelopment`, `1.1.10.`) на IFT **не
  резолвятся** — R-10/DEP-7 в том, что рабочие коды надо подтвердить именно на **test**.

**`CA → 40702` в TKS отсутствует.** Единственная связь `40702` — детерминированный генератор SDK
(`EqIdGenerator.java:44`: `"40702" + код + 12 цифр`) и литерал в моке `showcases`
(`ShowcasesRecords.java:37-40`). На `gateway` тип счёта идёт как `GZACT` на `OKC`, а `GZCTP` на `ONU` —
тип **организации** (дефект Г-1 разделён). Даёт ли тип `CA` балансовый `40702` на test — вопрос
стенда/EQ-владельца (OQ-9), кодом не закрывается.

---

## 10. 0.2-ФЛ / OQ-2 — коды физических лиц

Активные форматы ФЛ видны в кэше: `FCLM` v1 (`BusinessKey, Location, CommonTypeId, ClientTypeId,
INN, ResidentCountryId, ConnectBankDate`) и `FCLN` v1 (`BusinessKey, Location, SurName, GivenName,
MiddleName, BirthDate`) — `eq_taksa_client.json:162-260`. Это **кандидаты** для бэкенда `showcases`
ФЛ, но **полная цепочка сидирования ФЛ не подтверждена** — OQ-2 остаётся открытым до G0-FL (этап 4),
как и было записано.

---

## 11. Что осталось стенду и владельцам (не закрывается кодом)

| Пункт | Что нужно | Гейт |
|---|---|---|
| 0.4/0.5 живой | Отвечающий шлюз `10.232.128.12:4567`; одна цепочка ЮЛ на `K68`; обезличенные `ONU/OKC/YFT2/KP1`; PIN; приём correlation | G0-EQ |
| 0.6 | Рабочие значения фазы юнита; вызов чтения на реальном AS/400 | G0-TEST, G0-JT400 |
| 0.7 | Медиана/максимум задержки видимости; проверенный `visibility.probe` | G0-TEST |
| 0.8 | Поле, длина, алфавит маркировки `testRunId` | G0-TEST |
| 0.11 | Каталог и `CA → 40702` на test | G0-TEST |
| 0.12 | Полный реестр test без встроенных секретов | G0-TEST |
| 0.13 | Лицензия jt400 | G0-JT400 |
| 0.14 | Права Deploy/Cache в Artifactory | G0-PUBLISH |
| OQ-2 (ФЛ) | Полная цепочка записей ФЛ + ответы `ONF/VAD/SPU` | G0-FL |

---

## 12. Влияние на код и план

- **Ничего в реализованном коде менять не требуется**: опровержение `failed: null` и уточнение форм
  ответов подтверждают уже принятые решения (`ShowcasesBackend` — `MOCK_REJECTED`/`UNEXPECTED` без
  публикации тел; `GatewayResponsePolicy` — fail-closed, PIN/счёт скалярами).
- **OQ-17 закрыт** — `CLIENT_ID`/`ACCOUNT_ID` оставляем генерируемыми; отдельная поставка не нужна.
- **OQ-1/OQ-4 дополнительно подтверждены** — 0.2 (позиции) и 0.9 (id сделки) снимают неопределённость
  BR-30 и R-2 без изменения кода.
- **Оставшийся объём** — только живой стенд и внешние согласования (см. §11), т.е. ровно те гейты,
  что уже перечислены в `eq-data-provisioning-remaining-work.md`.

## 13. Проверка дерева

Документ — только протокол разведки, кода не меняет. Ни один файл SDK не редактировался.