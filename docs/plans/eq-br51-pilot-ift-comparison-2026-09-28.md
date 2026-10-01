# BR-51: пилот IFT «до/после» миграции (UlDiscountSchemeCancelTest)

Дата: 2026-09-28. Основание: план `eq-data-provisioning-implementation-plan.md` §2.3.2 / §2.3.5,
BRD BR-51. Стенд: **ift (dev3)**. Кейс: `ru.alfa.taksa.lgot.UlDiscountSchemeCancelTest`
(#408983, «ПУ Отмена»).

## Как снято «до» (изолированная копия)

Потребитель уже содержал миграцию, поэтому «до» восстанавливалось из HEAD + исходных untracked-файлов:

```
B=/tmp/consumer-before
rsync -a --delete --exclude build/ --exclude .gradle/ --exclude allure-results/ \
  ~/IdeaProjects/ALFA/taksa-service-autotests/ "$B/"
git -C "$B" checkout -- build.gradle.kts gradle.properties src/test/resources/application.yml
cp /tmp/consumer-pilot-backup/UlDiscountSchemeCancelTest.java          "$B/src/test/java/ru/alfa/taksa/lgot/"
cp /tmp/consumer-pilot-backup/ul-discount-scheme-simple-service-6-3-4-cancel.json \
   "$B/src/test/resources/fixtures/lgot/"
```

Индикаторы состояния «до»: `stand-test-bom:0.1.0-SNAPSHOT`, `version: 5`, `.environment("ift")`,
три шага `RestStep.post("showcases", …)`, константы `PIN = "T"+…` и `ACCOUNT = "40702810"…`.

## Команды прогонов

```
# до (изолированная копия)
cd /tmp/consumer-before
./gradlew test --tests 'ru.alfa.taksa.lgot.UlDiscountSchemeCancelTest' --rerun-tasks --console=plain

# после (рабочее дерево потребителя)
cd ~/IdeaProjects/ALFA/taksa-service-autotests
./gradlew test --tests 'ru.alfa.taksa.lgot.UlDiscountSchemeCancelTest' --rerun-tasks --console=plain
```

Три прогона на каждое состояние (`--rerun-tasks` форсирует исполнение, не кеш).

## Результат

| | Прогон 1 | Прогон 2 | Прогон 3 | Итог |
|---|---|---|---|---|
| **До** (showcases ×3) | PASSED | PASSED | PASSED | 3/3 зелёные |
| **После** (EqSeed) | PASSED | PASSED | PASSED | 3/3 зелёные |

Авторитетный источник — JUnit XML:

- До: `<testsuite tests="1" skipped="0" failures="0" errors="0" time="2.942"/>`
- После: `<testsuite tests="1" skipped="0" failures="0" errors="0" time="2.981"/>`

Тест не пропущен (skip=0), падений/ошибок нет ни на одной стороне.

## Сопоставление и категория причины

| До (шаг) | После (шаг) | Исход | Категория причины |
|---|---|---|---|
| `seed-client` (RestStep showcases) | `eq.seed` (EqSeed.organisation, backend showcases) | успех → успех | не применимо (зелёный) |
| `seed-account` (RestStep showcases) | — (внутри `eq.seed`) | успех → успех | не применимо |
| `seed-deal` (RestStep showcases) | — (внутри `eq.seed`) | успех → успех | не применимо |

Смена `FAILED`→`BROKEN` отсутствует: обе стороны зелёные. `UNCLASSIFIED` нет. Сравнение по операции
и категории причины закрыто: расхождений нет, разбирать нечего.

## Находка миграции (не дефект стенда)

При первой попытке «после» тест упал на фазе `prepare`, **до IO**:

```
StandTestException: eq.seed name needs a DSL value or registry defaults.organisation.name-prefix
  at EqStepExecutor.requireShowcasesInputs
```

Причина: в оригинале запись `ACLN` несла наименование `ООО ТЕСТ ЛЬГОТЫ 6.3.4 CANCEL` (захардкожено
в inline-теле), а `EqSeed` при отсутствии `.name(...)` и без `defaults.organisation.name-prefix` в
реестре отвергает шаг. Лечится явным `.name("ООО ТЕСТ ЛЬГОТЫ 6.3.4 CANCEL")` — так паритет записи
`ACLN` сохранён. Это осознанное поведение SDK (имя обязательно), а не блокер; в отчёте фиксируется как
правка миграции.

## Ограничения доказательства

- Доказательство относится **только к этому кейсу** (`Cancel`); прочие 15 сидирующих тестов не
  прогонялись.
- Сравнение — по исходу и отсутствию расхождений; honest-red пилота нет (кейс зелёный).
- Задержка видимости не проверялась: на ift `visibility.probe` не объявлен (мок виден сразу).
- Провенанс: `--rerun-tasks`, JUnit XML сохранён в `build/test-results/test/`.

## Вывод

**Пилот этапа 2 на ift даёт тот же результат, что до миграции: 3/3 зелёные, без констант EQ в тесте.**
Задачи 2.3.2 (база) и 2.3.5 (сравнение) закрыты для этого кейса.