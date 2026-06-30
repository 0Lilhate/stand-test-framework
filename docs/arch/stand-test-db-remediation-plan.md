# stand-test-db — план доработки (remediation plan)

Основание: жёсткое адверсариальное ревью модуля `stand-test-db` (2026-06-30, multi-agent: 6 измерений →
независимая верификация каждой находки → ручная перепроверка ключевого). Итог ревью: **29 подтверждённых
находок — 1 CRITICAL, 6 MEDIUM, 12 LOW, 10 INFO**. Сборка на момент ревью зелёная (instruction 89% /
branch 77%, JaCoCo gate 80% проходит).

Source of truth остаётся `docs/arch/stand-test-sdk-implementation-plan.md` (§4, §8.1, §8.3, §8.6, §8.7,
§8.8, §9, §20); решения по модулю — `docs/arch/stand-test-db-decisions.md`. Этот файл — только **план
устранения** найденного, со ссылками на ID находок ревью (`DBSEC-*`, `DB-EXEC-*`, `DB-API-*`, `TQ-*`,
`F*`, `DB-DOC-*`). Номера строк указаны на момент ревью и могут сдвигаться — ориентируйтесь на
имена методов/символов.

Глобальный Definition of Done для любой задачи ниже: `./gradlew :stand-test-db:build` и
`:stand-test-core:build` зелёные; checkstyle zero-tolerance (`maxWarnings = 0`) на main и test; JaCoCo
instruction ≥ 80%; AssertJ + JUnit 5 (никаких `org.junit.jupiter.api.Assertions` / JUnit 4); без
non-JetBrains nullability-аннотаций.

---

## Сводная таблица

| Приоритет | ID задачи | Находки ревью | Severity | Тип | Суть |
|---|---|---|---|---|---|
| **P0** | R1 | DBSEC-1 | CRITICAL | fix+test | `\r` завершает line-comment в PG, но не в classifier → деструктив доходит до стенда |
| **P1** | R2 | DB-EXEC-1 | MEDIUM | fix+test | Утечка `Connection` при сбое `setAutoCommit` |
| **P1** | R3 | TQ-01 | MEDIUM | test | `EnvironmentReferenceResolver` (прод-резолвер секретов) не покрыт |
| **P1** | R4 | TQ-02 | MEDIUM | fix+test | Семантика ошибок `resolve()`/`ResolvedDatasource` (§8.3) + тесты |
| **P1** | R5 | DB-API-1, F2 | MEDIUM | fix+test | `whereTestRunId(...)` не ограничен по операции в `build()` |
| **P1** | R6 | TQ-03 | MEDIUM | test | `NamedParameterStatement.bind` fail-closed ветка не покрыта |
| **P1** | R7 | TQ-04 | MEDIUM | test | Нет негативных тестов lifecycle соединения |
| **P2** | R8 | DBSEC-2 | LOW | fix+test | Регистрозависимый schema-whitelist vs case-folding PG |
| **P2** | R9 | DB-API-2 | LOW | fix+test | `positiveMillis()` молча усекает дробные/насыщает |
| **P2** | R10 | F1 | LOW | fix+test | Unicode-грамматика имени бинда vs ASCII Javadoc/classifier |
| **P2** | R11 | TQ-05, TQ-06, TQ-08 | LOW | test | Недостающие негативные тесты (resource / expect-NULL / param-map) |
| **P2** | R12 | DB-DOC-01/02/03 | LOW | docs | Неточности README / decisions-дока |
| **P2** | R13 | F4 | LOW/INFO | docs/fix | `NaN==NaN` fast-path vs inline-комментарий |
| **P3** | R14 | F5, F3 | INFO | refactor | Единый skeletonizer-lexer для `strip()` и `parse()` |
| **P3** | R15 | DB-EXEC-2/3, DBSEC-3/4/5, F3, TQ-07, DB-DOC-04 | INFO | docs | Зафиксировать осознанные fail-closed / отложенные решения |

Положительные подтверждения ревью (действий не требуют): **DB-EXEC-4** (thread-safety, try-with-resources,
отсутствие молчаливой `StepStatus.FAILED`, закрытие в `runner.finally`), **F5** (лексеры `strip()`/`parse()`
идентичны по границам литералов — in-literal дивергенции нет), **DB-API-3** (границы модуля корректны,
секреты/мутабельные коллекции наружу не текут).

---

## P0 — Безопасность (блокер релиза)

### R1 — `\r` завершает line-comment в PostgreSQL, но не в classifier (DBSEC-1, CRITICAL)

**Проблема.** `blankUntilLineEnd()` затирает `--`-комментарий только до `\n`. PostgreSQL (`scan.l`:
`non_newline [^\n\r]`) завершает `--` и по одиночному `\r`. SQL вида
`WITH x AS (SELECT 1) --c\rDELETE FROM test_data.orders` (один символ U+000D, без LF) классифицируется как
`READ` (DELETE затёрт как «тело комментария»), `DbWriteGuard` пропускает READ без проверок, а
`DbStepExecutor` диспетчеризует по operation → `executeUpdate`/`executeQuery` отправляет raw SQL на стенд,
где PG исполняет нескоупленный полнотабличный `DELETE`/`UPDATE` (валидный одиночный statement через
WITH-CTE, без `;`, поэтому ограничение PreparedStatement на мульти-команды не срабатывает). Обходятся ВСЕ
write-проверки (writeAllowed, schema-whitelist, whereTestRunId-маркер, destructive). Воспроизведено реальным
прогоном классификатора.

**Файлы.**
- `stand-test-core/.../core/validation/SqlStatementClassifier.java` — `blankUntilLineEnd` (≈245), а также
  `containsBatchSeparatorLine` (split только по `\n`, ≈236).
- `stand-test-db/.../db/NamedParameterStatement.java` — `copyLineComment` (≈116), симметрично.

**Фикс.**
1. В `blankUntilLineEnd` завершать затирание по `\n` **или** `\r`, оставляя сам терминатор для основного
   цикла (чтобы следующий за CR keyword стал виден в skeleton):
   ```java
   while (index < sql.length()) {
       char current = sql.charAt(index);
       if (current == '\n' || current == '\r') {
           break;          // комментарий закончился; терминатор обработает основной цикл
       }
       out.append(' ');
       index++;
   }
   return index;
   ```
2. В `NamedParameterStatement.copyLineComment` — то же стоп-условие (`\n` || `\r`), чтобы `:name` в живом
   PG-SQL после CR-терминированного комментария корректно переписывался в `?` (иначе bind «потеряется»).
3. `containsBatchSeparatorLine` — разбивать и по `\r` (например `skeleton.split("\\R", -1)` или
   предварительная нормализация), чтобы `GO`/`/`, разделённые голым CR, тоже отлавливались.
4. Defense-in-depth (опционально, отдельным решением в decisions-доке): на входе `classify()` трактовать
   «голый» `\r` (CR без последующего LF) как подозрительный и нормализовать в `\n` **до** скелетонизации —
   так любая будущая CR-зависимость диалекта закрывается в одной точке.

**Тесты (DoD).**
- `SqlStatementClassifierTest`: `WITH x AS (SELECT 1) --c\rDELETE FROM test_data.orders` → `REJECTED`;
  `WITH x AS (SELECT 1) --c\rUPDATE test_data.orders SET status='X'` → `REJECTED`;
  `SELECT 1 --c\r; DROP TABLE test_data.orders` → `REJECTED`; контроль с `\n` остаётся `REJECTED`;
  «чистый» `SELECT 1 -- c\rFROM …`-подобный безопасный READ классифицируется корректно (без регресса).
- `DbStepExecutorSeedCleanupTest` (H2): `db.seed`/`db.cleanup` с CR-вектором отвергается `StandTestException`
  до IO и **не меняет ни одной строки** (по аналогии с `trailingBlockCommentRefused`).
- `NamedParameterStatementTest`: `:name` после CR-терминированного комментария переписывается в `?`.

**Зависимости.** Нет. Выполнять первым.

---

## P1 — MEDIUM

### R2 — Утечка `Connection` при сбое `setAutoCommit` (DB-EXEC-1)

**Проблема.** В `DbStepExecutor.connection()` `raw` открывается, затем вызывается `raw.setAutoCommit(true)`;
если он бросает `SQLException`, `raw` не закрыт и не зарегистрирован в `ResourceScope` → утечка физического
соединения (большинство драйверов не закрывают его в finalize). На стенде с лимитом коннектов повторяющийся
сбой исчерпывает пул.

**Файл.** `stand-test-db/.../db/DbStepExecutor.java` — метод `connection` (≈228‑240).

**Фикс.** Регистрировать ресурс в `ResourceScope` **до** конфигурирующего вызова, чтобы `closeAll()` закрыл
его при любом последующем сбое:
```java
Connection raw;
try {
    raw = this.connectionFactory.open(resolved);
} catch (SQLException failure) {
    throw new StandTestException("Failed to open a JDBC connection for datasource '" + alias + "': " + failure.getMessage(), failure);
}
RunScopedConnection connection = new RunScopedConnection(raw, alias);
scope.register(key, connection);          // зарегистрировано: closeAll закроет даже при сбое setAutoCommit
try {
    raw.setAutoCommit(true);
} catch (SQLException failure) {
    throw new StandTestException("Failed to configure the JDBC connection for datasource '" + alias + "': " + failure.getMessage(), failure);
}
return connection;
```

**Тесты (DoD).** Фейковый `ConnectionFactory`, чей `Connection.setAutoCommit` бросает → `StandTestException`
+ проверка, что соединение закрыто после `resourceScope().closeAll()` (например, флаг `closed` в фейке).

---

### R3 — Прод-резолвер секретов не покрыт тестами (TQ-01)

**Проблема.** `EnvironmentReferenceResolver` (путь §9 reference→value) имеет branch-покрытие 0% — во всех
тестах подменён passthrough. Регрессия (например, тихий `null` вместо `StandTestException` для unset env,
или отказ легитимного пустого пароля) пройдёт сборку незамеченной.

**Файл (тест).** Новый `EnvironmentReferenceResolverTest`.

**Тесты (DoD).** Инъекция `UnaryOperator<String>`-lookup:
1. blank/null reference → `StandTestException` «must not be blank»;
2. lookup → `null` → `StandTestException` «did not resolve»;
3. lookup → `""` → резолвер возвращает `""` без исключения (контракт «empty password allowed»);
4. обычное значение проходит насквозь.

---

### R4 — Семантика ошибок `resolve()`/`ResolvedDatasource` (TQ-02)

**Проблема.** Путь `resolve()` → `new ResolvedDatasource(...)` не тестируется и нарушает §8.3: пустой
`url`/`user` из env даёт `IllegalArgumentException` (из конструктора record), а не `StandTestException`,
который рантайм-обработка распознаёт как config-ошибку.

**Файлы.** `DbStepExecutor.resolve` (≈243‑248); `ResolvedDatasource` (≈17‑25).

**Фикс (решение контракта).** Привести к §8.3: оборачивать построение `ResolvedDatasource` в
`DbStepExecutor.resolve` в `StandTestException` (с указанием, какой именно ref дал пустое значение). Record
оставить с `IllegalArgumentException` как low-level инвариант. (Альтернатива — задокументировать
`IllegalArgumentException` как контракт; предпочтителен первый вариант ради единой инфра-семантики.)

**Тесты (DoD).** `ResolvedDatasourceTest`: blank url / blank user / null password. Интеграционный тест
`DbStepExecutor` с резолвером, отдающим `""` для `urlRef` → `StandTestException` (config-ошибка).

---

### R5 — `whereTestRunId(...)` не ограничен по операции (DB-API-1, F2)

**Проблема.** `DbStep.whereTestRunId(...)` принимается на любой операции. На `db.query`/`db.expectEventually`
и на `db.seed` с `INSERT ... VALUES` дописанный `WHERE <col> = :testRunId` либо бессмыслен, либо даёт
невалидный SQL, который ловится лишь СУБД в рантайме — вместо ошибки `build()` (нарушение §8.1).

**Файлы.** `DbStep.validateOperationOptions` (≈252‑268); `DbStep.whereTestRunId` (≈186‑193).

**Фикс.** В `validateOperationOptions()` отвергать `whereTestRunIdColumn != null` для `QUERY` и
`EXPECT_EVENTUALLY` с понятным сообщением (reads никогда не нуждаются в testRunId-предикате). Оставить
маркер допустимым на `SEED`/`CLEANUP` (нужен для `UPDATE`-seed и обязателен для `cleanup`). Случай
`INSERT`-seed точно поймать на этапе `build()` нельзя без классификации (намеренно отложена) — закрыть
сужением до write-операций и явной строкой в Javadoc `whereTestRunId` о применимости к `UPDATE`/`DELETE`.

**Тесты (DoD).** `DbStepTest`: `query(...).whereTestRunId(...)` и `expectEventually(...).whereTestRunId(...)`
→ `IllegalStateException` с внятным сообщением; `seed`/`cleanup` с маркером строятся как прежде.

---

### R6 — Fail-closed ветка `NamedParameterStatement.bind` не покрыта (TQ-03)

**Проблема.** Ветка «No bind value supplied» и экранирование кавычек (`copyQuoted` с удвоением) не
тестируются; самый частый failure-режим (забытый `param`) и барьер «только параметризованные binds» без
регресс-защиты. `NamedParameterStatementTest` сейчас покрывает только `parse()`.

**Тесты (DoD).** На `create()`/`bind()` (через H2):
1. SQL c `:missing` без значения → `StandTestException` «No bind value supplied»;
2. `INSERT` с `:name` через H2 — проверить, что значение реально связалось (round-trip);
3. `parse` SQL с `''` внутри литерала (`'it''s; DROP'`) и `""` внутри идентификатора — корректный пропуск;
4. неоконченный `'`/dollar/block — хвост забланкен, `parse` не падает.

---

### R7 — Негативные тесты lifecycle соединения (TQ-04)

**Проблема.** Не покрыты: open-failure, setAutoCommit-failure (см. R2), `RunScopedConnection.close()` throws →
агрегация в `ResourceScope.closeAll()`. Корректное закрытие per-run соединений (§8.7) критично для стендов с
лимитом коннектов.

**Тесты (DoD).** Фейковый `ConnectionFactory`:
- `open()` бросает `SQLException` → `StandTestException` «Failed to open»;
- `setAutoCommit` бросает → обёртка (совмещается с R2);
- два `RunScopedConnection` поверх `Connection`, чей `close()` бросает; после `closeAll()` — агрегатный
  `StandTestException` с одним cause и остальными как `suppressed` (проверяет и контракт `RunScopedConnection`,
  и агрегацию `ResourceScope` в связке с DB).

---

## P2 — LOW

### R8 — Регистрозависимый schema-whitelist (DBSEC-2)

`DatasourceDefinition.isSchemaAllowed` → `allowedSchemas.contains(schema)` сравнивает строго. PG складывает
неэкранированные идентификаторы в lowercase, поэтому при whitelist в смешанном/верхнем регистре запись может
уйти в фактически другую схему (нарушение инварианта «писать только в whitelisted-схемы»; вероятность низкая —
нужна нетипичная конфигурация).
**Фикс:** сравнивать схему регистронезависимо для неэкранированных идентификаторов **или** явно
задокументировать «allowedSchemas задаётся в нижнем регистре, как видит их PG» (в Javadoc
`DatasourceDefinition` и README §9). + тест на mixed-case whitelist/target.

### R9 — `positiveMillis()` строгость (DB-API-2)

`Number.longValue()` молча усекает дробные и насыщает огромные значения — слабое место строгого контракта
для будущего YAML-фронтенда.
**Фикс:** принимать только целочисленные (`Integer`/`Long`), иначе `StandTestException` (по образцу строгого
`expectedStatus` в REST), либо проверять `number.doubleValue() != number.longValue()`. + reject-тесты.

### R10 — Грамматика имени бинда vs Javadoc/classifier (F1)

`isNameStart`/`isNamePart` используют Unicode-aware `Character.isLetter`/`isLetterOrDigit`, тогда как Javadoc
обещает ASCII `[A-Za-z_][A-Za-z0-9_]*`, а `SqlStatementClassifier.TEST_RUN_ID_BIND` сопоставляет `:testRunId`
по ASCII-границе `\b`. Потери данных нет, но это дивергенция компонентов, объявленных единым источником (§8.6).
**Фикс:** ограничить `isNameStart`/`isNamePart` ASCII — выровнять с Javadoc и `SqlIdentifiers.PLAIN_IDENTIFIER`.
+ тест на `:testRunIdФ`-подобное имя.

### R11 — Недостающие негативные тесты (TQ-05, TQ-06, TQ-08)

- **TQ-05** (resource): `sqlFromResource('does/not/exist.sql')` → «not found»; пустой ресурс → «is empty»;
  напрямую через `GenericStep` (минуя билдер, как сделает YAML) оба ключа `sql`+`sqlResource` → «not both»,
  без обоих → «requires sql».
- **TQ-06** (expect): probe бросает `SQLException` → `StandTestException`, шаг падает сразу (фиксирует
  `ignoreExceptions(false)`); строка с `NULL` в колонке → таймаут с `lastObserved=<null>` (семантика NULL +
  покрытие `render(null)`); `DbValuesTest.valuesMatch(Double.NaN, …)` → false.
- **TQ-08** (param-map): параметризованный тест на каждую reject-ветку `DbStepParameters` (`PARAMS` не-Map;
  `PARAMS` с null-значением; `optionalString(SQL=123)`; `TIMEOUT_MILLIS='x'`; `CAPTURES` не-List; `CAPTURES=[123]`).

### R12 — Правки документации (DB-DOC-01/02/03)

- **DB-DOC-01:** `stand-test-db-decisions.md:5‑6` утверждает о наличии «analogous notes for REST/Kafka» —
  их нет в `docs/arch`. Либо смягчить формулировку, либо создать парные `stand-test-rest-decisions.md` /
  `stand-test-kafka-decisions.md`.
- **DB-DOC-02:** заменить точечное «88% instruction coverage» (`decisions.md:38`) на пороговую формулировку
  «≥ 80% instruction coverage (JaCoCo gate)», как в sibling-READMEs; при желании оговорить непокрытость
  прод-резолвера (закрывается R3).
- **DB-DOC-03:** в `README.md` (Key contracts §8.6, ≈25‑26) статическое форсирование в `ScenarioValidator`
  подано как факт, что противоречит блоку «Known follow-up» (≈45‑48). Переформулировать как
  «statically in ScenarioValidator (planned) and — today — enforced at runtime in the executor».

### R13 — `NaN==NaN` fast-path (F4)

`DbValues.valuesMatch` через `Objects.equals` возвращает `true` для двух `Double.NaN` (`Double.equals`
канонизирует NaN), не доходя до BigDecimal-ветки. Inline-комментарий рядом говорит «non-finite = mismatch».
**Фикс:** либо уточнить комментарий (NaN сравнивается по identity-семантике `Double.equals`), либо явно
отсеивать non-finite до `Objects.equals`, если требуется строгое «NaN — всегда mismatch». Минор; решить и
зафиксировать.

---

## P3 — Рефактор и фиксация осознанных решений

### R14 — Единый skeletonizer-lexer (F5, F3)

Сейчас логика «вырезать комментарии/литералы/кавычки» дублируется в `SqlStatementClassifier.strip()` и
`NamedParameterStatement.parse()`. Ревью подтвердило их идентичность сегодня (F5), но R1 — прямое следствие
того, что обе копии надо править синхронно, и расхождение = брешь. Вынести общий проход скелетонизации в один
переиспользуемый класс (`core.validation`), чтобы будущие правки (в т.ч. backslash-экранирование из F3 при
не-PostgreSQL датасорсах) делались в одном месте. Делать **после** R1, чтобы не смешивать фикс безопасности
с рефактором.

### R15 — Зафиксировать осознанные / отложенные решения в decisions-доке (INFO)

Добавить в `stand-test-db-decisions.md` явные строки про подтверждённые ревью trade-offs, чтобы они были
осознанными, а не «случайными»:
- **DB-EXEC-3 / TQ-06:** `db.expectEventually` — fail-fast на любой `SQLException` пробы
  (`ignoreExceptions=false`), без per-poll реконнекта; `>1` строки — немедленный abort. Если появятся
  не-PostgreSQL стенды/длинные poll-окна — рассмотреть селективный ретрай транзиентных ошибок.
- **DB-EXEC-2:** межадаптерное расхождение классификации — пустой результат / null-колонка в `db.query` =
  infra (`StandTestException`, это probe/precondition-fetch), а REST-capture-аналог = assertion. Подтвердить
  намеренность; добавить «no rows»/«null capture» в Javadoc-перечень инфра-кейсов `DbStepExecutor`.
- **DBSEC-3 / DBSEC-4 / DBSEC-5:** fail-closed ложно-отклонения (`UPDATE/DELETE … ONLY`, алиас таргета,
  подзапросный WHERE при маркере, keyword-как-имя-столбца в CTE, невложенные block-комментарии) — безопасные
  over-restrictive trade-offs (как `GO`/`/`); задокументировать. Пост-MVP опционально: распознавать `ONLY`/
  алиас при извлечении схемы и разрешённую форму скоупленного WHERE.
- **F3:** оба лексера предполагают SQL-стандартное удвоение кавычек, не backslash — на текущем
  PostgreSQL-флоте (`standard_conforming_strings=on`) неактуально; учесть при добавлении MySQL-датасорсов
  (синхронно в обоих лексерах — см. R14).
- **TQ-07:** H2 (MODE=PostgreSQL) ≠ боевой PG. Где возможно — добавить executor-тесты на исполнение
  (`INSERT … RETURNING` как seed; реальный `$$…$$` с `;` внутри проходит как WRITE и вставляется;
  `INSERT INTO TEST_DATA.orders` при `allowedSchemas={test_data}` → отклонён, фиксирует R8). Если H2 не
  поддерживает конструкцию — задокументировать лимит фиделити рядом с `DbTestSupport`.
- **DB-DOC-04:** отложенное подключение классификатора к статическому `ScenarioValidator` (§8.6/§8.8) —
  оставить как known follow-up; при реализации registry-aware прохода переиспользовать тот же
  `SqlStatementClassifier`, чтобы исключить дрейф статики и рантайма.

---

## Рекомендуемый порядок

1. **R1** (P0, блокер) — отдельным коммитом/PR, с регресс-тестами на CR.
2. **R2, R5** (fix+test) — небольшие правки контракта/lifecycle.
3. **R3, R4, R6, R7** (тесты + семантика ошибок) — поднимают покрытие security-значимых путей и фиксируют
   контракты для будущего YAML.
4. **R8–R13** (LOW) — пакетом; дёшево подтягивают branch к gate и чистят доки.
5. **R14** (рефактор) — после R1, чтобы не смешивать с фиксом безопасности.
6. **R15** (docs) — сопровождает соответствующие задачи или отдельным docs-коммитом.

После каждой задачи: запустить целевые тесты (`./gradlew :stand-test-db:test --tests '...'`), затем полную
`:stand-test-db:build` + `:stand-test-core:build` для checkstyle/coverage gate.
