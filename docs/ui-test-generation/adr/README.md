# ADR по UI-тестированию (волна 1)

Архитектурные решения по модулю `stand-test-ui`. Контекст каждого — сверка BRD с фактическим кодом,
выполненная в [`../00-current-state.md`](../00-current-state.md) и
[`../01-brd-traceability.md`](../01-brd-traceability.md); открытые вопросы — в
[`../02-open-questions.md`](../02-open-questions.md); сводный дизайн —
[`../10-target-architecture.md`](../10-target-architecture.md).

Этот каталог отделён от [`docs/agent-architecture/adr/`](../../agent-architecture/adr/): тот
описывает решения по AI-киту, эти — по рантайм-модулю SDK.

> **Колонка «Статус» сверена 2026-08-04 с самими ADR.** Она показывала `Proposed` у всех семи,
> тогда как 001, 002, 003 и 006 приняты ещё 2026-08-01 и реализованы. Индекс отставал от
> документов, на которые ссылается, — то есть ровно от того, ради чего его читают.

| ADR | Решение | Статус | Правит `core`? |
|---|---|---|---|
| [ADR-UI-001](ADR-UI-001-module-boundaries.md) | Границы модуля; Playwright заперт в подпакете; три новых ArchUnit-правила **до** первой строки кода | Accepted, реализован | да — валидатор, `StepParameterKeys`, `EnvironmentDefinition` |
| [ADR-UI-002](ADR-UI-002-playwright-lifecycle.md) | `Playwright`/`Browser` — `ThreadLocal` + shutdown hook; `BrowserContext`/`Page` — на прогон в `ResourceScope`; сначала «всё на прогон», потом пул | Accepted, шаг 1 реализован | нет |
| [ADR-UI-003](ADR-UI-003-public-api.md) | `UiStep` + `UiLocator` в идиоме `RestStep`; проверки через общий `AssertionMatchers`; пять типов шагов | Accepted, реализован | нет |
| [ADR-UI-004](ADR-UI-004-environment-registry-versioning.md) | `version` вводится **отдельным релизом до** UI-секции; `ui-applications` в реестре; одна константа версии на обе поверхности | Proposed | да — константа версии, `UiApplicationDefinition` |
| [ADR-UI-005](ADR-UI-005-reporting-and-artifacts.md) | `Attachment` + компонент `Path file`; артефакты только на отказе; маскирование в DOM; **трейс запрещён при объявленных чувствительных зонах** | **Accepted 2026-08-04** | **да — `Attachment`, самое рискованное изменение волны 1** |
| [ADR-UI-006](ADR-UI-006-authentication-and-account-pool.md) | `FORM` + `STORAGE_STATE` в волне 1, `SSO` — волна 2; `AccountPool` за интерфейсом; `storageState` на учётку; ограниченный таймаут аренды | Accepted, реализован | да — `UiAuthScheme`, поля приложения |
| [ADR-UI-007](ADR-UI-007-non-db-compensations.md) | Волна 1 берёт только компенсатор адаптера (правки core **ноль**); сценарные cleanup-шаги — волна 2 | Proposed | **нет — и это главное следствие** |

## Сквозные выводы

1. **Правок `stand-test-core` в волне 1 — пять, а не одна.** §13.1 BRD называет единственной
   «компенсации не-DB шагов» (D-9) — а её волна 1 по ADR-UI-007 не делает. Вместо неё: ветка `ui.` в
   валидаторе, константы `StepParameterKeys`, `UiApplicationDefinition`, ключ `version` и
   `Attachment`. Порядок величины тот же, статьи другие; §13.1 подлежит пересмотру.

2. **Два решения обязаны быть выпущены раньше остальных работ.** Ключ `version` (ADR-UI-004) — пока
   SDK не опубликован и окно совместимости пусто. Правила графа (ADR-UI-001) — пока их отсутствие не
   успело ничего пропустить.

3. **Три ограничения названы честно, а не обойдены:** трейс при чувствительных зонах (ADR-UI-005),
   компенсация только средствами UI (ADR-UI-007), `SSO` вне волны 1 (ADR-UI-006). Каждое имеет
   компенсирующий контроль и срок снятия.
