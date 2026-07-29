# Architecture Decision Records — агент автотестирования

Формат: контекст → решение → рассмотренные альтернативы и причины отказа → последствия.
Каждый ADR ссылается на находку [Анализа](../../agent-analysis/current-state-analysis.md),
которую он закрывает.

| # | Решение | Статус | Закрывает |
|---|---|---|---|
| [0001](0001-agent-modules-separate-from-sdk.md) | Агентский слой — отдельные модули; SDK не меняется | Accepted | §11 «Keep», инвариант core-sink |
| [0002](0002-deterministic-orchestrator-owns-the-loop.md) | Цикл принадлежит Java-оркестратору, а не LLM-хосту | Accepted | A-03, A-05, A-06, A-14 |
| [0003](0003-structured-llm-client-without-ai-framework.md) | Свой `StructuredLlmClient` вместо AI-фреймворка | Accepted | Независимость от провайдера, U-1, Q-2 |
| [0004](0004-ai-scenario-format-as-mvp-track.md) | Трек MVP — AI-формат, не Java DSL | Accepted | R-8 |
| [0005](0005-append-only-execution-trace.md) | Trace — append-only JSONL + manifest | Accepted | A-03, A-14 |
| [0006](0006-versioned-prompt-registry-single-source.md) | Промты — один версионированный источник для человека и агента | Accepted | A-07, A-09 |
| [0007](0007-four-separate-memories-kb-read-only.md) | Четыре раздельных хранилища; KB — read-only; без vector DB | Accepted | §5, Gap «Memory» |
| [0008](0008-typed-java-tools-no-mcp.md) | Tools — типизированные Java-интерфейсы в том же JVM, без MCP | Accepted | Gap «Tool abstraction» |
| [0009](0009-bounded-repair-loop.md) | Repair ограничен: 2 итерации + прогресс + anti-cheat | Accepted | A-05, R-9 |
| [0010](0010-approval-by-absence-of-tool.md) | Запрет реализуется отсутствием инструмента, а не allowlist'ом | Accepted | A-01, A-15 |
| [0011](0011-offline-evaluation-with-replay.md) | Evaluation на golden-корпусе через replay записанных вызовов | Accepted | A-08 |
| [0012](0012-zero-new-dependencies.md) | Ноль новых записей в version catalog | Accepted | §3.2 target-architecture |
| [0013](0013-experience-accumulation-gated-and-attributed.md) | Накопление опыта за гейтом рабочего цикла; трёхисходная атрибуция | Accepted | Gap «Memory»/«Skills», §9 maturity |
| [0014](0014-progressive-skill-loading.md) | Прогрессивная загрузка; отбор навыков кодом, не моделью | Accepted | стоимость и воспроизводимость контекста |

## Шаблон

```markdown
# ADR-NNNN: <решение одной фразой>
**Статус:** Proposed | Accepted | Superseded by ADR-XXXX
**Дата:** YYYY-MM-DD
## Контекст
## Решение
## Рассмотренные альтернативы
## Последствия
### Положительные / Отрицательные / Нейтральные
## Как проверить, что решение соблюдается
```

Последний раздел обязателен: решение, соблюдение которого нельзя проверить, — это пожелание.
