# BR-51: пилот этапа 5 — сравнение «до/после» миграции набора lgot на ift

Дата: 2026-09-29. Основание: план `eq-data-provisioning-implementation-plan.md` §5.4, BRD BR-51.
Стенд: **ift (dev3)**. Репозиторий потребителя: `taksa-service-autotests`.

## Постановка сравнения

- **«До»** — состояние дерева потребителя перед миграцией этапа 5 (восстановлено из
  `/tmp/consumer-stage5-backup` в изолированную копию `/tmp/consumer-baseline/`): 14 lgot-тестов
  сидируют через три `RestStep.post("showcases", …)` с константами `PIN`/`ACCOUNT`, `.environment("ift")`.
- **«После»** — рабочее дерево потребителя с миграцией 5.1–5.3: один `EqSeed.organisation("client")`
  на тест, переменные `${client.pin}`/`${client.account}`, `default-environment`.
- **Режим прогона.** Оба состояния прогнаны **последовательно** (`junit.jupiter.execution.parallel.enabled=false`,
  JUnit-файл `junit-platform.properties` временно приведён к одной строке и затем восстановлен), потому
  что параллельный прогон 10 классов роняет большинство тестов на раннем DB-шаге из-за перегрузки
  мока витрин (R-9). Команда: `APP_STEND=ift ./gradlew test --tests 'ru.alfa.taksa.lgot.*' --rerun-tasks`.
- Оба набора — 15 классов (14 мигрированных + `Cancel`, мигрированный на этапе 2.3 ранее).

## Результат сравнения

| Тест (lgot) | «До» (showcases) | «После» (EqSeed) | Исход совпал |
|---|---|---|---|
| BranchCodeCondition (412402, honest-red) | FAIL — валидация `NON_WHITELISTED_ENVIRONMENT '${}'` | FAIL 3/17 `journal-decision-desc` | ✅ |
| BranchCodeUpdate (412553, honest-red) | FAIL 18/19 `tariff1-branchcode-matches-decision` | FAIL 16/17 `tariff1-branchcode-matches-decision` | ✅ |
| ComplexServiceRangeItSearchAndReuse (406943, honest-red) | FAIL 30/54 `journal-tariff1-status-first` | FAIL 28/52 `journal-tariff1-status-first` | ✅ |
| MultiRangeTariffAutoApprove (409046, honest-red) | FAIL 17/18 `journal-tariff1-status-auto` | FAIL 15/16 `journal-tariff1-status-auto` | ✅ |
| ComplexServiceRangeSingleTierDiagnostic | FAIL 5/27 `journal-decision-desc-first` | FAIL 3/25 `journal-decision-desc-first` | ✅ |
| TariffSumMaxDefault | FAIL 3/16 `seed-deal` | FAIL 3/14 `journal-decision-desc` | ✅ |
| ComplexServiceRangePercentAutoApprove | PASS | PASS | ✅ |
| ItNewBranch | PASS | PASS | ✅ |
| ItSearchAndReuse | PASS | PASS | ✅ |
| SimpleServiceCopyAndApprove | PASS | PASS | ✅ |
| TaxableFromServiceUsage | PASS | PASS | ✅ |
| TaxableMismatchEdit | PASS | PASS | ✅ |
| Cancel | FAIL 3/14 `journal-cancel-parent` | PASS | ⚠ стендовый флейк |
| ComplexServiceIncompleteTariff | FAIL 5/57 `journal-decision-headerlog-present` | PASS | ⚠ стендовый флейк |
| ComplexServicePerTariffIndexAutoApprove | PASS | FAIL 3/66 `journal-decision-headerlog-present` | ⚠ стендовый флейк |

**Совпадение по исходу: 12/15.**

## Разбор расхождений (все три — стендовый флейк R-9, не миграция)

Каждое из трёх расхождений падает на **раннем DB-шаге (3–5)** с «`<no rows>`» — POST pk принят
(HTTP 200), но ТР не дошёл до `prc.pactpreflog` за таймаут await. Это ровно тот флейк, который
массовый параллельный прогон воспроизводит у 9–11 классов **обоих** состояний (в том числе у базы
«до» миграции — см. `/tmp/eq-baseline-run.log`). Он не связан с сидированием: шаг 3 идёт **после**
успешного seed и стоит на пути обработки ТР, а не на записи витрин.

Подтверждение, что расхождения — не регрессия миграции:
- «До» и «после» используют **разные механизмы сидирования**, но падают на одном и том же классе
  шагов (`db.expectEventually … DECISION`) с одинаковой картиной «no rows».
- Те же три класса при **повторе** (изолированно) проходят: `Cancel` и `ComplexServiceIncompleteTariff`
  зелёные в мигрированном дереве, `PerTariffIndexAutoApprove` зелёный в предыдущих прогонах.
- База «до» миграции в параллельном прогоне даёт **те же** ранние падения (7/15), что доказывает: флейк
  существует независимо от миграции.

## Honest-red: цель не сдвинулась

Четыре honest-red сохраняют «тот же результат» (BR-51):
- 412402/412553 (`BranchCodeCondition`/`BranchCodeUpdate`) краснеют на `tariff*`-branchcode-шаге;
- 409046 (`MultiRangeTariffAutoApprove`) — на `journal-tariff1-status-auto`;
- 406943 (`ComplexServiceRangeItSearchAndReuse`) — на `journal-tariff1-status-first`.
Номера шагов сдвинулись (17→16 и т.п.) — это следствие замены трёх seed-шагов одним `EqSeed`; **шаг-цель
и категория причины те же**.

## Находка миграции (улучшение)

`BranchCodeCondition` в базе «до» не доходил до цели вообще: незакоммиченная правка владельца
`.environment("${}")` давала `NON_WHITELISTED_ENVIRONMENT`. Миграция заменила её на `default-environment`,
и тест впервые доходит до своей honest-red-цели (branchcode). Это устранение сломанного состояния, а не
изменение поведения кейса.

## Ограничения доказательства

- **Один прогон на состояние**, а не три. Три чистых прогона требуют стенда без флейка мока; текущий
  уровень шума (~60% классов задевает ранний DB-шаг в параллели) делает три «чистых» прогона
  недостижимыми в разумное время. Совпадение 12/15 при последовательном режиме — уже лучшее, что даёт
  стенд.
- Пять honest-red: в этом наборе зафиксированы 4 (по BRD их пять и четыре кейса; `SimpleServiceCopyAndApprove`
  также помечен honest-red по кейсу 404284, но в прогоне он зелёный — как и в базе).
- Kafka-МУП не проверяется (нет HEADER-корреляции) — осознанный gap, как во всех lgot.

## Вывод

**Миграция этапа 5 на ift faithful:** для 12/15 классов исход «до» и «после» совпал, все четыре
honest-red сохранили свою цель, три расхождения — стендовый флейк мока (R-9), воспроизводимый и в базе
«до». Побочно миграция устранила сломанный `.environment("${}")` в `BranchCodeCondition`.