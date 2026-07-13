# Spec ingestion report — ФС ПК ПАКТ.Льготы v1.0.4 (fs-pk-pakt-lgoty-2025-04-17) → knowledge-base/candidates/fs-pk-pakt-lgoty-2025-04-17, create-candidates

## Summary

Функциональная спецификация интеграции МУП (Такса) ↔ Продуктовый каталог (Такса) в рамках интеграции с
ПАКТ.Льготы. Прочитано через pandoc (docx→gfm), 3581 строка, ~19 500 слов. Извлечено **42 контракт/
семантик-кандидата + 13 unresolved**, 0 конфликтов. Ключевой контракт — внутренний REST-сервис ПК
(статусы 200/400/404/500, но **method/path не указаны**), Kafka-уведомление МУП (**имя топика/схема в
недоступном вложении .emf**) и две таблицы БД `PACTPREFLog`/`PACTPREFMessage` (полный DDL). Вердикт: годно
для review; ни одного выдуманного method/path/topic — все пробелы вынесены в unresolved.

## Source parsed

| Property | Value |
|---|---|
| Document / documentId | ФС ПК ПАКТ.Льготы 2025.04.17 / fs-pk-pakt-lgoty-2025-04-17 |
| Version / Date | 1.0.4 / 2025-04-17 |
| Type | docx |
| Reader tool | pandoc 3.9.0.2 (docx → gfm, --extract-media) |
| Source file (`_source/raw/`) | ФС ПК ПАКТ.Льготы.2025.04.17.docx (gitignored; hash sha256:489bf9b5…253b8) |
| Sections / Tables / Figures | 8 разделов; таблицы БД+журнал+статусы; фигуры/схемы в media (.emf недоступны текстом) |
| Parse warnings | Формат Kafka-сообщения и внутренних сервисов — в бинарных вложениях image11/12.emf (не извлекаемо) |

## Confidence breakdown

| Confidence | Count | Routing |
|---|---|---|
| high | 21 | category file; eligible after human review |
| medium | 19 | category file; needs an explicit per-item tick |
| low | 2 | needs-review; не auto-apply (dwh service, jpy-fallback scenario) |
| unresolved | 13 | unresolved.candidates.yml (не применяются) |

## Candidates added (staging)

| Category | Count | Notable |
|---|---|---|
| services | 5 | pk-product-catalog (SUT), mup, tk, pakt-lgoty, dwh(low) |
| endpoints | 1 | pk-process-tr-request (success=200; method/path → unresolved) |
| kafka-topics | 1 | it-index-binding-notification (produced ПК→МУП; topic/payload → unresolved) |
| db (datasource/table/probe) | 5 | pk-taksa-db; PACTPREFLog, PACTPREFMessage; 2 read-probes |
| business-flows | 6 | transit МУП→ПК, ЛТР-auto, ИТР-manual, reprocess, complex-index, parallel |
| business-rules | 12 | ЛТР-auto/ИТР-manual, лимитный, архивная дата, UNIVERSAL, Individual_No_Standart, версии, параллельность… |
| test-scenarios | 12 | все 12 запрошенных сценариев |
| glossary (digest) | 13 сокр. + 4 терм. + 2 alias | не schema-validated (нет схемы glossary) |

## Conflicts (human resolution required)

Нет. Axis 1 (кандидат ↔ curated KB): в curated-KB нет ПК/МУП/ТК/PACTPREFLog — все `added`. Axis 2
(cross-document): единственный документ. `conflicts.candidates.yml` = `conflicts: []`.

## KB schema gaps surfaced (no curated home — human decides)

| Extracted fact | Needed field / entity | Note |
|---|---|---|
| PACTPREFLog / PACTPREFMessage | db-table (write/seed) | `promotionBlocked: curated-collection-missing`; нет tag-колонки testRunId |
| REST await/poll timeout | endpoint.timeout | нет KB-дома для REST-timeout |
| Kafka payload/captures | kafkaTopic.captures | нет в извлекаемом тексте |

## Non-KB semantic content (routed to scenario-design)

business-flows (6), business-rules (12), glossary — digest-контент, питает scenario-design/case-analysis;
в strict-контракт не входит.

## Validation result

- Candidate JSON Schema (Draft 2020-12) over 10 staged files: **PASS** (0 errors).
- Provenance completeness (documentId + documentName + quote на каждом item): **PASS** (все items).
- Secret/URL scan (`://`/jdbc/Bearer/Basic/AKIA/eyJ): **CLEAN**.
- No invented contracts: method/path/topic/точная payload-схема — **вынесены в unresolved**, не выдуманы.
- KB validation unaffected (кандидаты не в pinned-allowlist; `:stand-test-ai-schema:test` green): **CONFIRMED**.

## Files changed

Созданы под `candidates/fs-pk-pakt-lgoty-2025-04-17/`: source-document.yml, glossary.candidates.yml,
services/endpoints/kafka-topics/db/business-flows/business-rules/test-scenarios/unresolved/conflicts
.candidates.yml, extraction-report.md, _source/ (raw gitignored + normalized.md + media).

## Next actions

`/stand-test-review-kb-candidates fs-pk-pakt-lgoty-2025-04-17`. До review: получить OpenAPI внутреннего
REST-сервиса ПК (method/path/auth), формат Kafka-сообщения из image11.emf (§4.2.1.1) + имя топика, схему БД
и алиасы окружения. db-table кандидаты и семантика — promotionBlocked / digest (не промоутятся без решения человека).

## Review decisions (2026-07-13)

**business-rules — 10 high-confidence одобрены как approved digest** (см. `review-decisions.yml`).
Диспозиция: digest, питает `stand-test-scenario-design`/`case-analysis`; **в curated-KB не пишутся**
(коллекции `businessRules` в strict-схеме нет по дизайну; поля `status` у business-rule-схемы нет —
одобрение фиксируется в review-артефакте, а не в schema-locked YAML). 2 medium (`complex-index-truncated-tariff`,
`incomplete-tariff-fallback`) — held (needs-review). Контрактные семейства (services/endpoints/kafka/db) —
не одобрены (заблокированы открытыми unresolved-блокерами).
