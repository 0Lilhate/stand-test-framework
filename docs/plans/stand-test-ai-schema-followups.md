# План доработки — stand-test-ai-schema (follow-ups)

## Контекст

Модуль реализован: JSON Schema (2020-12) + rules-док + `AiScenarioParser` (в `scenario-yaml`) + parity-тест +
рантайм-энфорсмент (`DefaultScenarioValidator`, §Решение 6). Remediation-план (P0/P1/P2) **закрыт** —
`docs/arch/stand-test-ai-schema-remediation-plan.md`. Остались **follow-ups**: расхождения «схема ⊋ исполнимое»,
найденные по ходу. Цель — сузить разрыв между тем, что схема принимает, и тем, что рантайм реально исполняет.

Ключевой факт для приоритезации: часть конструкций **уже исполнима wire-моделью** (просто не выражена в схеме),
часть требует новой поддержки в адаптерах/сериализации, часть ждёт `stand-test-grpc`.

## Скоуп

**Входит.** Правки JSON Schema (`stand-test-scenario.schema.json`), `AiStepNormalizer`/translators в
`scenario-yaml`, rules-док, golden/parity-тесты. **Не входит.** Изменение surface `scenario-yaml` (given/then),
новый рантайм-стек, бизнес-сценарии.

## Задачи по приоритету

### P1 — дёшево и уже исполнимо wire-моделью

**F1. REST body assertions.** Схема `restStep` не имеет `assert`, хотя wire уже умеет (`RestStepParameters.ASSERTIONS`,
`RestStepExecutor` их исполняет). Добавить `assert` в `$defs/restStep` (тот же `$defs/assertion`/`assertionList`,
что у kafka.expect) + в `AiStepNormalizer.rest()` маппинг `assert[]`→yaml `assert`-map (equals-only, как для
kafka.expect — переиспользовать существующую логику). Тест: rest.post с `assert` → parity доходит до
`assertions` wire-ключа.

**F2. `restStep.query`.** Схема не имеет `query`, парсер/wire поддерживают (`RestStepParameters.QUERY`).
Добавить `query` (object, string→string) в `$defs/restStep`; `AiStepNormalizer.rest()` уже прокинет (сейчас
`query` в REST_KNOWN нормализатора? — проверить; если нет — добавить passthrough). Тест: rest.get с `query`.

**F3. `kafka.send` payload — согласовать.** В схеме `payload` optional, в рантайме `KafkaStepTranslator` (send)
требует body/bodyResource. Решение (выбрать): (a) ужесточить схему — `required: ["payload"]` в `kafkaSendStep`;
(b) разрешить пустой payload в рантайме. Рекомендация — (a) (schema fail-closed, соответствует рантайму).

### P2 — нужна новая поддержка (адаптеры/сериализация)

**F4. Rich matchers.** Схема принимает `exists`/`notNull`/`contains`/`matches`, рантайм исполняет только
`equals` (translator fail-closed). Реализовать матчеры в assertion-хелперах адаптеров (`MessageAssertions`
kafka + REST-аналог) и снять fail-closed в `AiStepNormalizer.applyAssert`. Затрагивает rest/kafka(/db) — общий
матчер-контракт лучше вынести в core (`core.assertion`?) чтобы не дублировать. Средний объём.

**F5. `db.expect.rowExists`.** Схема принимает, translator fail-closed (рантайм = equals-первой-колонки).
Добавить в DB-адаптер режим «строка существует» (`db.expectEventually` без сравнения значения) + wire-ключ +
снять fail-closed. Требует правки `DbStepExecutor`/`DbStepParameters`.

**F6. Inline JSON body (`body.json`/`payload.json`).** Сейчас fail-closed (нет JSON-сериализатора; Jackson
избегается репо-wide). Варианты: (a) минимальный hand-rolled JSON-writer в core/scenario-yaml (Map/List/scalar
→ строка), (b) использовать `json-smart` (уже транзитивно от json-path) для сериализации. Рекомендация — (b),
если API json-smart достаточно; иначе (a). Снять fail-closed в `AiStepNormalizer.applyPayload`.

**F7. Enum-коды whitelist для service/topic/grpc.** Рантайм pre-flight (§Решение 6) сейчас энфорсит только
`NON_WHITELISTED_ENVIRONMENT`/`_DATASOURCE` (адаптеры ловят service/topic/target на исполнении). Если нужен
pre-flight и для них — добавить `ForbiddenOperation.NON_WHITELISTED_SERVICE`/`_TOPIC`/`_GRPC_TARGET` (+ строки
в rules-доке + `ForbiddenOperationCoverageTest` подхватит) и проверки в `DefaultScenarioValidator.checkStep`
для rest./kafka./grpc. префиксов.

### P3 — зависит от stand-test-grpc

**F8. `grpc.unary` execution + parity.** Пока адаптер gRPC — скелет, `AiStepNormalizer` делает fail-closed на
`grpc.unary`. После реализации `stand-test-grpc` (см. отдельный план): добавить `grpcUnary`-нормализацию
(`target`/`method`/`correlation.inject`→metadata/`request.fixture`→wire/`timeout`→deadline/`expect`), снять
fail-closed, добавить grpc в parity-набор `stand-test-example`. См.
[stand-test-grpc-implementation.md](stand-test-grpc-implementation.md).

## Файлы (представительно)

- `stand-test-ai-schema/src/main/resources/schema/stand-test-scenario.schema.json` (F1–F3, F5, F6).
- `stand-test-scenario-yaml/.../AiStepNormalizer.java`, `RestStepTranslator.java`, `DbStepTranslator.java` (F1–F6, F8).
- `stand-test-core/.../validation/{ForbiddenOperation,DefaultScenarioValidator}.java` (F7).
- Адаптеры rest/kafka/db (F4, F5).
- Тесты: `ScenarioSchemaValidationTest`, `AiScenarioParserTest`, `AiSchemaParityTest`, rules-док.

## Тестирование / верификация

- Каждая F: golden valid/invalid в ai-schema + parser-тест в scenario-yaml (маппинг на wire-ключи) + (для
  исполнимых) parity в `stand-test-example`.
- `./gradlew :stand-test-ai-schema:test :stand-test-scenario-yaml:test :stand-test-example:test` → зелёные.
- `./gradlew build` — весь граф.
- `ForbiddenOperationCoverageTest` остаётся зелёным при F7 (новые коды задокументированы).

## Риски

- **F4/F6 — дублирование матчеров/сериализации** по адаптерам → выносить общий контракт в core.
- **F6 «no Jackson»** — не тянуть Jackson в main-граф; json-smart уже есть транзитивно.
- **F7** расширяет `ForbiddenOperation` → синхронно править rules-док (иначе cross-check краснеет) — это и есть
  «единый источник».

## Рекомендация к порядку

P1 (F1–F3, ~час, закрывают самые заметные schema↔wire дыры) → F7 (если нужен строгий pre-flight) → P2 (F4–F6,
по мере необходимости) → P3 (F8) после grpc.
