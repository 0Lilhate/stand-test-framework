# 22. Task dependency matrix

| | |
|---|---|
| **Документ** | Матрица зависимостей бэклога |
| **Дата** | 2026-08-03 · HEAD `46d3cfb` + незакоммиченное рабочее дерево · **§4 перегенерирована 2026-08-10** |
| **Поправка 2026-08-10** (`UITG-T010`) | Таблица §4 **перегенерирована из YAML и с этого дня держится тестом**. Что было исправлено, числами: колонка **Статус** расходилась в **52 строках из 79**, и однобоко — восемь **закрытых** внешних гейтов (`X001`, `X004`, `X006`…`X010`, `X013`) числились `BLOCKED`, четыре **принятых** решения (`ADR003`/`ADR004`/`ADR005`/`ADR008`) — `READY`, девять посаженных порций (`S001`…`S009`) — `IN_PROGRESS`; колонка **Blocks** — в **23 строках**; **шести карточек не было вовсе** (`T005`…`T009` плюс сама `T010`). Прежняя поправка обещала «правится перегенерацией… правка — задача `F002`», но `F002` закрылась `DONE` 2026-08-09, не сделав её: названный владелец исчез вместе с закрытием. Теперь расхождение роняет сборку — `BacklogMatrixCensusTest` |
| **Поправка 2026-08-08** | Таблица §4 **сверена с YAML программно** (81 строка). Колонка **Depends on** верна во всех 81 — этот столбец единственный пережил снимок без дрейфа. Колонка **Статус** устарела: `ADR003`/`ADR004` числятся `READY`, а **приняты**; `S011` числится `BLOCKED`, а выполнена; так же `S027`/`S028` |
| **Поправка 2026-08-06** | статусы ниже устарели (снимок на `46d3cfb`); актуальная картина — `21-task-backlog.yaml`. S018 исполнен → `IN_REVIEW` |
| **Источник** | [`21-task-backlog.yaml`](21-task-backlog.yaml) — таблица §4 **сгенерирована разбором YAML**, а не набрана вручную. Правится она **только** перегенерацией: правка ячейки руками разойдётся с источником первой же закрытой карточкой |
| **Проверено** | циклов нет; висящих ссылок нет; ни один `READY` не имеет незакрытой зависимости |

---

## 1. Обозначения

| Колонка | Значение |
|---|---|
| **Depends on** | входящие зависимости: работа не начинается, пока они не закрыты |
| **Blocks** | что ждёт эту задачу (объединение поля `blocks` и обратных ссылок `depends_on`) |
| **Critical path** | задача на критическом пути до решения гейта §19.1 |
| **Lane** | параллельный поток из §3; `W2/W3` — отложенные волны |
| **External gate** | внешнее предусловие, без которого задача не исполнима |
| **ADR gate** | решение, без которого задача не проектируется |
| ~~`UITG-…`~~ | ссылка **зачёркнута ровно тогда**, когда названная карточка в `DONE` или `REJECTED`. Это не пометка от руки, а производная от статуса: строка `V001` с одним незачёркнутым гейтом из четырёх и есть ответ на вопрос «что держит пилот» |

**Как считается `Blocks`** — объединение поля `blocks` и **обратных** ссылок `depends_on`,
`external_dependencies` и `adr_dependencies`, без исключений. Контейнеры (`I01`, `E*`, `F*`) своих
**строк** не имеют — таблица про рабочие элементы, — но в ячейках `Blocks` называются: гейт,
держащий эпик, держит его по-настоящему.

**Что в §4 машинное, а что нет.** Колонки `Статус`, `Depends on`, `Blocks`, `External gate` и
`ADR gate` выводятся из [`21-task-backlog.yaml`](21-task-backlog.yaml) и сверяются с ним тестом.
Колонки `Critical path` и `Lane` — **суждение о плане**: таких полей в YAML нет, они переносятся при
перегенерации как есть и тестом намеренно не пинятся.

## 2. Критический путь

Двенадцать задач, помеченных `**да**` в колонке Critical path:

```
UITG-S001 -> UITG-S002 -> UITG-S003 -> UITG-S004
                                          v
UITG-ADR001 --------------------------> UITG-S012 -> UITG-S013 -> UITG-S017
                                                                      v
                                                                  UITG-S026
                                                                      v
                                                                  UITG-V001 -> UITG-V004 -> UITG-V005
```

**Узел — `UITG-S012`**: единственная задача, трогающая `stand-test-core`, за ней стоят шесть Story.
Участок `S001…S017` полностью в руках команды SDK; участок `V001…V005` доминирован внешними
гейтами и разработкой не сокращается.

## 3. Параллельные потоки

| Lane | Содержание | Стартует | Конфликт по файлам |
|---|---|---|---|
| **L1** | Посадка кода: S001…S009 | немедленно | со всеми — идёт первой |
| **L2** | Отчётность: ~~ADR001~~ → ~~S012~~ → S013 → S014/S015/S016 → S017/S018 | S013 ждёт посадки S004 | `core/event`, `allure`, `ui` |
| **L3** | CI: ~~S025~~, ~~SP002~~ (`IN_REVIEW`) → X003 → S026 | ждёт X003 (внешний) | нет |
| **L4** | Гейт безопасности: ~~SP003~~ → S020 → S021; ~~S022~~ — обе `IN_REVIEW` | S020 ждёт посадки S007 | `docs/ai-agent` |
| **L5** | Измеримость: ~~SP001~~ (`IN_REVIEW`) → ~~ADR007~~ (**принят 2026-08-06**) → ~~S023~~ (**исполнен 2026-08-07, `IN_REVIEW`**); ~~S024~~ (`IN_REVIEW`) | S023 — исполнена | `docs/agent-evaluation` |
| **L6** | Решения и документы: ADR003, ADR004, ADR005, ADR008, S010, S011 | немедленно | документы |
| **L7** | Компенсации: ADR002 → S019 | по X007 | `ui` |
| **L8** | Конфигурация: S027, S028, S029 | по ADR003/ADR004 | `starter`, `config` |
| **L9** | Пилот: ADR006, S030, V001…V006 | по гейтам | вне репозитория |

**L3, L4, L5, L6 не пересекаются между собой ни по одному файлу** — запускаются одновременно.

## 4. Матрица

| Task | Тип | Статус | Depends on | Blocks | Critical path | Lane | External gate | ADR gate |
|---|---|---|---|---|---|---|---|---|
| `UITG-S001` | STOR | DONE | — | ~~UITG-S002~~, ~~UITG-S006~~, ~~UITG-S007~~ | **да** | L1 | — | — |
| `UITG-S002` | STOR | DONE | ~~UITG-S001~~ | ~~UITG-S003~~, ~~UITG-S006~~, ~~UITG-S007~~ | **да** | L1 | — | — |
| `UITG-S003` | STOR | DONE | ~~UITG-S002~~ | UITG-S004 | **да** | L1 | — | — |
| `UITG-S004` | STOR | IN_REVIEW | ~~UITG-S003~~ | ~~UITG-F004~~, ~~UITG-S005~~, ~~UITG-S013~~, UITG-S026 | **да** | L1 | UITG-X012 | — |
| `UITG-S005` | STOR | DONE | UITG-S004 | — | — | L1 | ~~UITG-X001~~, UITG-X005 | — |
| `UITG-S006` | STOR | DONE | ~~UITG-S002~~ | ~~UITG-S028~~ | — | L1 | — | — |
| `UITG-S007` | STOR | DONE | ~~UITG-S002~~ | ~~UITG-S008~~, ~~UITG-S020~~ | — | L1 | — | — |
| `UITG-S008` | STOR | DONE | ~~UITG-S007~~ | UITG-S023 | — | L1 | — | — |
| `UITG-S009` | STOR | DONE | — | ~~UITG-S010~~, ~~UITG-S011~~ | — | L1 | — | — |
| `UITG-S010` | STOR | DONE | — | — | — | L6 | — | — |
| `UITG-S011` | STOR | DONE | ~~UITG-ADR003~~ | — | — | L6 | — | ~~UITG-ADR003~~ |
| `UITG-ADR001` | ADR | DONE | — | UITG-E04, ~~UITG-F003~~, ~~UITG-F004~~, UITG-F005, UITG-I01, ~~UITG-S012~~, ~~UITG-S013~~, ~~UITG-S014~~, ~~UITG-S015~~, ~~UITG-S016~~, ~~UITG-S017~~, UITG-S018, ~~UITG-T001~~, ~~UITG-T002~~, ~~UITG-T003~~ | **да** | L2 | — | — |
| `UITG-ADR002` | ADR | DONE | — | UITG-E05, UITG-S019, UITG-S033 | — | L7 | ~~UITG-X007~~ | — |
| `UITG-ADR003` | ADR | DONE | — | UITG-E09, ~~UITG-S011~~, ~~UITG-S028~~ | — | L6 | — | — |
| `UITG-ADR004` | ADR | DONE | — | UITG-E09, ~~UITG-S027~~ | — | L6 | — | — |
| `UITG-ADR005` | ADR | DONE | — | UITG-E11, UITG-S038 | — | L6 | — | — |
| `UITG-ADR006` | ADR | DONE | — | UITG-E10, UITG-I01, UITG-V004 | — | L9 | ~~UITG-X013~~ | — |
| `UITG-ADR007` | ADR | DONE | ~~UITG-SP001~~ | UITG-E07, UITG-S023 | — | L5 | — | — |
| `UITG-ADR008` | ADR | DONE | — | UITG-E11, UITG-S034, ~~UITG-X010~~ | — | L6 | — | — |
| `UITG-SP001` | SPIK | DONE | — | ~~UITG-ADR007~~, UITG-E07, UITG-S023 | — | L5 | — | — |
| `UITG-SP002` | SPIK | DONE | — | UITG-E08, UITG-S026, UITG-X003, UITG-X012 | — | L3 | — | — |
| `UITG-SP003` | SPIK | DONE | — | UITG-E06, ~~UITG-F006~~, ~~UITG-S020~~, ~~UITG-S021~~ | — | L4 | — | — |
| `UITG-X001` | EXTE | DONE | — | UITG-E10, UITG-I01, ~~UITG-S005~~, UITG-S039, UITG-V001, UITG-V002, ~~UITG-V003~~ | — | — | — | — |
| `UITG-X002` | EXTE | READY | — | UITG-E10, UITG-I01, UITG-V004, UITG-V005, ~~UITG-V006~~ | — | — | — | — |
| `UITG-X003` | EXTE | READY | — | UITG-E08, UITG-I01, UITG-S026, UITG-S035, UITG-S041 | — | — | — | — |
| `UITG-X004` | EXTE | DONE | — | UITG-E10, UITG-E12, UITG-I01, ~~UITG-S029~~, UITG-V001, UITG-V002, ~~UITG-V003~~ | — | — | — | — |
| `UITG-X005` | EXTE | READY | ~~UITG-X010~~ | UITG-E10, UITG-I01, ~~UITG-S005~~, UITG-V001, UITG-V002, ~~UITG-V003~~ | — | — | — | — |
| `UITG-X006` | EXTE | DONE | — | UITG-E10, UITG-E11, UITG-F007, UITG-I01, UITG-S031, UITG-V001, UITG-V002, ~~UITG-V003~~, ~~UITG-X001~~, UITG-X005, ~~UITG-X007~~, ~~UITG-X013~~ | — | — | — | — |
| `UITG-X007` | EXTE | DONE | ~~UITG-X006~~ | ~~UITG-ADR002~~, ~~UITG-E02~~, UITG-E05, UITG-S019, UITG-S033 | — | — | — | — |
| `UITG-X008` | EXTE | DONE | — | UITG-E10, UITG-V004 | — | — | — | — |
| `UITG-X009` | EXTE | DONE | — | UITG-V004 | — | — | — | — |
| `UITG-X010` | EXTE | DONE | — | UITG-E11, UITG-S034, UITG-X005 | — | — | — | ~~UITG-ADR008~~ |
| `UITG-X011` | EXTE | READY | — | UITG-E10, UITG-V005 | — | — | — | — |
| `UITG-X012` | EXTE | READY | ~~UITG-SP002~~ | UITG-E08, UITG-S004, UITG-S026 | — | — | — | — |
| `UITG-X013` | EXTE | DONE | ~~UITG-X006~~ | ~~UITG-ADR006~~, ~~UITG-E02~~, UITG-V004 | — | — | — | — |
| `UITG-S012` | STOR | DONE | ~~UITG-ADR001~~ | ~~UITG-S013~~ | **да** | L2 | — | ~~UITG-ADR001~~ |
| `UITG-T001` | TASK | DONE | ~~UITG-ADR001~~ | ~~UITG-T002~~, ~~UITG-T003~~ | — | — | — | ~~UITG-ADR001~~ |
| `UITG-T002` | TASK | DONE | ~~UITG-T001~~ | — | — | — | — | ~~UITG-ADR001~~ |
| `UITG-T003` | TASK | DONE | ~~UITG-T001~~ | — | — | — | — | ~~UITG-ADR001~~ |
| `UITG-S013` | STOR | DONE | UITG-S004, ~~UITG-S012~~ | ~~UITG-S014~~, ~~UITG-S015~~, ~~UITG-S016~~, ~~UITG-S017~~, UITG-S018, ~~UITG-T006~~ | **да** | L2 | — | ~~UITG-ADR001~~ |
| `UITG-S014` | STOR | DONE | ~~UITG-S013~~ | — | — | L2 | — | ~~UITG-ADR001~~ |
| `UITG-S015` | STOR | DONE | ~~UITG-S013~~ | — | — | L2 | — | ~~UITG-ADR001~~ |
| `UITG-S016` | STOR | DONE | ~~UITG-S013~~ | — | — | L2 | — | ~~UITG-ADR001~~ |
| `UITG-S017` | STOR | DONE | ~~UITG-S013~~ | ~~UITG-T005~~, UITG-V001 | **да** | L2 | — | ~~UITG-ADR001~~ |
| `UITG-S018` | STOR | IN_REVIEW | ~~UITG-S013~~ | — | — | L2 | — | — |
| `UITG-S019` | STOR | DEFERRED | ~~UITG-ADR002~~ | UITG-S033 | — | L7 | ~~UITG-X007~~ | ~~UITG-ADR002~~ |
| `UITG-S020` | STOR | DONE | ~~UITG-S007~~, ~~UITG-SP003~~ | ~~UITG-S021~~, ~~UITG-T004~~, ~~UITG-T007~~, UITG-V001 | — | L4 | — | — |
| `UITG-T004` | TASK | DONE | ~~UITG-S020~~ | — | — | — | — | — |
| `UITG-T005` | TASK | DONE | ~~UITG-S017~~ | — | — | L2 | — | — |
| `UITG-T006` | TASK | DONE | ~~UITG-S013~~ | — | — | L2 | — | — |
| `UITG-T007` | TASK | DONE | ~~UITG-F006~~, ~~UITG-S020~~, ~~UITG-S021~~, ~~UITG-S024~~ | — | — | L5 | — | — |
| `UITG-T008` | TASK | DONE | — | — | — | L6 | — | — |
| `UITG-T009` | TASK | DONE | — | — | — | L4 | — | — |
| `UITG-T010` | TASK | IN_REVIEW | — | — | — | L6 | — | — |
| `UITG-S021` | STOR | DONE | ~~UITG-S020~~, ~~UITG-SP003~~ | ~~UITG-T007~~ | — | L4 | — | — |
| `UITG-S022` | STOR | IN_REVIEW | — | — | — | L4 | — | — |
| `UITG-S023` | STOR | IN_REVIEW | ~~UITG-ADR007~~, ~~UITG-S008~~, ~~UITG-SP001~~ | UITG-V004 | — | L5 | — | ~~UITG-ADR007~~ |
| `UITG-S024` | STOR | DONE | — | ~~UITG-T007~~, UITG-V004 | — | L5 | — | — |
| `UITG-S025` | STOR | IN_REVIEW | — | UITG-S026 | — | L3 | — | — |
| `UITG-S026` | STOR | BLOCKED | UITG-S004, UITG-S025, ~~UITG-SP002~~ | UITG-V004 | **да** | L3 | UITG-X003, UITG-X012 | — |
| `UITG-S027` | STOR | DONE | ~~UITG-ADR004~~ | — | — | L8 | — | ~~UITG-ADR004~~ |
| `UITG-S028` | STOR | DONE | ~~UITG-ADR003~~, ~~UITG-S006~~ | — | — | L8 | — | ~~UITG-ADR003~~ |
| `UITG-S029` | STOR | DONE | — | — | — | L8 | ~~UITG-X004~~ | — |
| `UITG-S030` | STOR | DONE | — | UITG-V001 | — | L9 | — | — |
| `UITG-V001` | VALI | BLOCKED | ~~UITG-S017~~, ~~UITG-S020~~, ~~UITG-S030~~ | UITG-V002, ~~UITG-V003~~, UITG-V004 | **да** | L9 | ~~UITG-X001~~, ~~UITG-X004~~, UITG-X005, ~~UITG-X006~~ | — |
| `UITG-V002` | VALI | DEFERRED | UITG-V001 | UITG-V004 | — | L9 | ~~UITG-X001~~, ~~UITG-X004~~, UITG-X005, ~~UITG-X006~~ | — |
| `UITG-V003` | VALI | REJECTED | UITG-V001 | — | — | L9 | ~~UITG-X001~~, ~~UITG-X004~~, UITG-X005, ~~UITG-X006~~ | — |
| `UITG-V004` | VALI | BLOCKED | UITG-S023, ~~UITG-S024~~, UITG-S026, UITG-V001 | UITG-V005 | **да** | L9 | UITG-X002, ~~UITG-X008~~, ~~UITG-X009~~, ~~UITG-X013~~ | ~~UITG-ADR006~~ |
| `UITG-V005` | VALI | BLOCKED | UITG-V004 | UITG-E11, UITG-F007, UITG-S031, UITG-S032, UITG-S033, UITG-S034, UITG-S035, UITG-S036, UITG-S037, UITG-S038, UITG-S039, UITG-S046 | **да** | L9 | UITG-X002, UITG-X011 | — |
| `UITG-V006` | VALI | DONE | — | UITG-V004 | — | L9 | UITG-X002 | — |
| `UITG-S031` | STOR | DEFERRED | UITG-V005 | UITG-V007 | — | W2/W3 | ~~UITG-X006~~ | — |
| `UITG-S032` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | — | — |
| `UITG-S033` | STOR | DEFERRED | UITG-S019, UITG-V005 | — | — | W2/W3 | ~~UITG-X007~~ | ~~UITG-ADR002~~ |
| `UITG-S034` | STOR | DEFERRED | ~~UITG-ADR008~~, UITG-V005 | — | — | W2/W3 | ~~UITG-X010~~ | ~~UITG-ADR008~~ |
| `UITG-S035` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | UITG-X003 | — |
| `UITG-S036` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | — | — |
| `UITG-S037` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | — | — |
| `UITG-S038` | STOR | DEFERRED | ~~UITG-ADR005~~, UITG-V005 | — | — | W2/W3 | — | ~~UITG-ADR005~~ |
| `UITG-S039` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | ~~UITG-X001~~ | — |
| `UITG-V007` | VALI | DEFERRED | UITG-S031 | UITG-E12, UITG-S040, UITG-S041, UITG-S042, UITG-S043, UITG-S044, UITG-S045 | — | W2/W3 | — | — |
| `UITG-S040` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S041` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | UITG-X003 | — |
| `UITG-S042` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S043` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S044` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S045` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S046` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | — | — |

## 5. Задачи без входящих зависимостей

**Состояние на 2026-08-10.** Открытых карточек без единой входящей зависимости шесть, и делятся они
надвое — так, что вывод раздела читается с первого взгляда:

| Карточка | Тип | Статус | Кто её двигает |
|---|---|---|---|
| `UITG-X002` (G-2) | EXTE | `READY` | QA-лиды — **замер**, а не ответ |
| `UITG-X003` (G-3) | EXTE | `READY` | владельцы CI |
| `UITG-X011` (OQ-10) | EXTE | `READY` | Руководство качества |
| `UITG-T010` | TASK | `IN_REVIEW` | выполнена этой правкой |
| `UITG-S022` | STOR | `IN_REVIEW` | ждёт гейта opencode |
| `UITG-S025` | STOR | `IN_REVIEW` | ждёт пяти ночных прогонов — **время**, а не работа |

**Сессии-исполнимого `READY` нет, и это не временное состояние.** Все пять `READY` бэклога —
`EXTERNAL` (`X002`, `X003`, `X005`, `X011`, `X012`), то есть вопросы к людям вне команды SDK; ни
одной `STORY`, `TASK` или `SPIKE` в `READY` не стоит, а `ADR` не осталось вовсе — все восемь приняты.

**Расхождение, найденное при выполнении `UITG-S010`.** Строка `UITG-S009` перечисляла `UITG-S010`
среди разблокируемых, тогда как у самой `S010` в [`21-task-backlog.yaml`](21-task-backlog.yaml)
`depends_on: []`, и очередь держала её в `NOW` как `READY`. Зависимости не было и по существу:
пересмотр статусов читает код, а не посаженные документы. Ссылка снята; карточка не менялась.

## 6. Узлы с наибольшим числом зависимых

Считаются **открытые** зависимые у **открытой** карточки: чем закрытая карточка была узлом когда-то,
здесь не помогает выбрать следующий шаг.

| Задача | Разблокирует | Почему это важно |
|---|---|---|
| **UITG-V005** (гейт §19.1) | 12 (E11, F007, S031…S039, S046) | решение GO/NO_GO — вход во всю волну 2 |
| **UITG-X003** (G-3) | 5 (E08, I01, S026, S035, S041) | без образа с браузерами не закрывается ни один прогон в CI |
| **UITG-X005** (G-5) | 4 (E10, I01, V001, V002) | **последнее** предусловие пилота: шесть техучёток плюс учётка разведки |
| **UITG-X002** (G-2) | 4 (E10, I01, V004, V005) | без базы не проверяются три порога из четырёх |
| **UITG-X012** (DEP-01) | 3 (E08, S004, S026) | доставка браузеров в закрытый контур |
| ~~**UITG-ADR001**~~, ~~**UITG-S013**~~, ~~**UITG-X006**~~ | — | **закрыты**: весь срез артефактов падения посажен, состав волны назван |

## 7. Задачи, откладываемые без блокировки волны 1

Прежний перечень (`S027`, `S028`, `S029`, `S011`, `V003`) **исчерпан, а не пересмотрен**: четыре
первых в `DONE`, `V003` — единственный `REJECTED` линии, и отклонена она условием собственной
карточки («третье приложение волны, **если** G-6 назвал три»; G-6 назвал одно).

Волну 1 сегодня не блокирует ничто из того, что делается **внутри** команды SDK: остаток —
`DEFERRED` волн 2–3 (там предмет, а не ресурс: `S019` компенсировать нечем, `S034` про чужие тесты)
и пять внешних гейтов §5.

## 8. Что проверено машиной

| Проверка | Результат |
|---|---|
| **§4 совпадает с `21-task-backlog.yaml`** (состав строк, `Статус`, `Depends on`, `Blocks`, оба столбца гейтов) | **да — и с 2026-08-10 это держит `BacklogMatrixCensusTest`, а не сверка глазами**: расхождение роняет сборку |
| Циклические зависимости (обход `depends_on` в глубину) | **не найдено** |
| Висящие ссылки в `depends_on` / `blocks` / `external_dependencies` / `adr_dependencies` | **нет** |
| Дубликаты идентификаторов | **нет** |
| `READY` с незакрытой зависимостью | **нет** (после исправления `UITG-S011`, `UITG-ADR008`, `UITG-SP002`) |
| Story без `test_requirements` | **нет** (после заполнения `S009`, `S010`, `S011`) |
| Задача без `requirement_ids` | **нет** |
| Задача без `acceptance_criteria` | **нет** |
