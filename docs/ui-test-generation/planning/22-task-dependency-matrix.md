# 22. Task dependency matrix

| | |
|---|---|
| **Документ** | Матрица зависимостей бэклога |
| **Дата** | 2026-08-03 · HEAD `46d3cfb` + незакоммиченное рабочее дерево |
| **Поправка 2026-08-06** | статусы ниже устарели (снимок на `46d3cfb`); актуальная картина — `21-task-backlog.yaml`. S018 исполнен → `IN_REVIEW` |
| **Поправка 2026-08-08** | Таблица §4 **сверена с YAML программно** (81 строка). Колонка **Depends on** верна во всех 81. Колонка **Статус** устарела: `ADR003`/`ADR004` числятся `READY`, а **приняты**; `S011` числится `BLOCKED`, а выполнена; так же `S027`/`S028`. Колонка **Blocks** устарела ровно в **пяти** строках — `S013`, `S017`, `S020`, `S021`, `S024`, — и причина у всех одна: **у пяти карточек, заведённых после снимка (`T005`…`T009`), строки в таблице нет вовсе**, поэтому их обратные ссылки не попали в `Blocks`. Контейнеры (`I01`, `E*`, `F*`) отсутствуют по построению — таблица про рабочие элементы, — поэтому перевода `E09` в `IN_REVIEW` здесь и не видно. Правится это перегенерацией таблицы из `21-task-backlog.yaml`, а не руками; правка — отдельная задача гигиены документов (`F002`), не остаток `E09` |
| **Источник** | [`21-task-backlog.yaml`](21-task-backlog.yaml) — таблица §4 **сгенерирована разбором YAML**, а не набрана вручную |
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
| `UITG-S001` | STOR | IN_PROGRESS | — | UITG-S002, UITG-S006, UITG-S007 | **да** | L1 | — | — |
| `UITG-S002` | STOR | IN_PROGRESS | UITG-S001 | UITG-S003, UITG-S006, UITG-S007 | **да** | L1 | — | — |
| `UITG-S003` | STOR | IN_PROGRESS | UITG-S002 | UITG-S004 | **да** | L1 | — | — |
| `UITG-S004` | STOR | IN_PROGRESS | UITG-S003 | UITG-F004, UITG-S005, UITG-S013, UITG-S026 | **да** | L1 | UITG-X012 | — |
| `UITG-S005` | STOR | IN_PROGRESS | UITG-S004 | — | — | L1 | UITG-X001, UITG-X005 | — |
| `UITG-S006` | STOR | IN_PROGRESS | UITG-S002 | UITG-S028 | — | L1 | — | — |
| `UITG-S007` | STOR | IN_PROGRESS | UITG-S002 | UITG-S008, UITG-S020 | — | L1 | — | — |
| `UITG-S008` | STOR | IN_PROGRESS | UITG-S007 | UITG-S023 | — | L1 | — | — |
| `UITG-S009` | STOR | IN_PROGRESS | — | ~~UITG-S010~~, UITG-S011 | — | L1 | — | — |
| `UITG-S010` | STOR | IN_REVIEW | — | — | — | L6 | — | — |
| `UITG-S011` | STOR | BLOCKED | UITG-ADR003 | — | — | L6 | — | UITG-ADR003 |
| `UITG-ADR001` | ADR | IN_REVIEW (принят 2026-08-04) | — | UITG-E04, UITG-F003, UITG-S012, UITG-S013, UITG-S014, UITG-S015, UITG-S016, UITG-S017, UITG-S018, UITG-T001 | **да** | L2 | — | — |
| `UITG-ADR002` | ADR | BLOCKED | — | UITG-E05, UITG-S019 | — | L7 | UITG-X007 | — |
| `UITG-ADR003` | ADR | READY | — | UITG-E09, UITG-S011, UITG-S028 | — | L6 | — | — |
| `UITG-ADR004` | ADR | READY | — | UITG-E09, UITG-S027 | — | L6 | — | — |
| `UITG-ADR005` | ADR | READY | — | UITG-S038 | — | L6 | — | — |
| `UITG-ADR006` | ADR | BLOCKED | — | UITG-V004 | — | L9 | UITG-X013 | — |
| `UITG-ADR007` | ADR | DONE (принят 2026-08-06) | ~~UITG-SP001~~ | UITG-S023 | — | L5 | — | — |
| `UITG-ADR008` | ADR | READY | — | UITG-S034, UITG-X010 | — | L6 | — | — |
| `UITG-SP001` | SPIK | IN_REVIEW | — | UITG-ADR007, UITG-E07, UITG-S023 | — | L5 | — | — |
| `UITG-SP002` | SPIK | IN_REVIEW | — | UITG-E08, UITG-S026, UITG-X003, UITG-X012 | — | L3 | — | — |
| `UITG-SP003` | SPIK | IN_REVIEW | — | UITG-E06, UITG-F006, UITG-S020, UITG-S021 | — | L4 | — | — |
| `UITG-X001` | EXTE | BLOCKED | — | UITG-V001, UITG-V002, UITG-V003 | — | — | — | — |
| `UITG-X002` | EXTE | BLOCKED | — | UITG-V004, UITG-V005, UITG-V006 | — | — | — | — |
| `UITG-X003` | EXTE | READY | — | UITG-S026 | — | — | — | — |
| `UITG-X004` | EXTE | BLOCKED | — | UITG-V001, UITG-V002, UITG-V003 | — | — | — | — |
| `UITG-X005` | EXTE | BLOCKED | UITG-X010 | UITG-V001, UITG-V002, UITG-V003 | — | — | — | — |
| `UITG-X006` | EXTE | BLOCKED | — | UITG-V001, UITG-X001, UITG-X005, UITG-X007, UITG-X013 | — | — | — | — |
| `UITG-X007` | EXTE | BLOCKED | UITG-X006 | UITG-ADR002, UITG-S019 | — | — | — | — |
| `UITG-X008` | EXTE | BLOCKED | — | UITG-V004 | — | — | — | — |
| `UITG-X009` | EXTE | BLOCKED | — | UITG-V004 | — | — | — | — |
| `UITG-X010` | EXTE | BLOCKED | — | UITG-S034, UITG-X005 | — | — | — | UITG-ADR008 |
| `UITG-X011` | EXTE | BLOCKED | — | UITG-V005 | — | — | — | — |
| `UITG-X012` | EXTE | READY | ~~UITG-SP002~~ | UITG-S026 | — | — | — | — |
| `UITG-X013` | EXTE | BLOCKED | UITG-X006 | UITG-ADR006 | — | — | — | — |
| `UITG-S012` | STOR | IN_REVIEW | ~~UITG-ADR001~~ | UITG-S013 | **да** | L2 | — | ~~UITG-ADR001~~ |
| `UITG-T001` | TASK | IN_REVIEW | ~~UITG-ADR001~~ | UITG-T002, UITG-T003 | — | — | — | ~~UITG-ADR001~~ |
| `UITG-T002` | TASK | IN_REVIEW | ~~UITG-T001~~ | — | — | — | — | ~~UITG-ADR001~~ |
| `UITG-T003` | TASK | READY | ~~UITG-T001~~ | — | — | — | — | ~~UITG-ADR001~~ |
| `UITG-S013` | STOR | BLOCKED | ~~UITG-S012~~, UITG-S004 | UITG-S014, UITG-S015, UITG-S016, UITG-S017, UITG-S018 | **да** | L2 | — | ~~UITG-ADR001~~ |
| `UITG-S014` | STOR | BLOCKED | UITG-S013 | — | — | L2 | — | UITG-ADR001 |
| `UITG-S015` | STOR | IN_REVIEW | ~~UITG-S013~~ | — | — | L2 | — | ~~UITG-ADR001~~ |
| `UITG-S016` | STOR | IN_REVIEW | ~~UITG-S013~~ | — | — | L2 | — | ~~UITG-ADR001~~ |
| `UITG-S017` | STOR | BLOCKED | UITG-S013 | UITG-V001 | **да** | L2 | — | UITG-ADR001 |
| `UITG-S018` | STOR | IN_REVIEW | ~~UITG-S013~~ | — | — | L2 | — | — |
| `UITG-S019` | STOR | BLOCKED | UITG-ADR002 | UITG-S033 | — | L7 | UITG-X007 | UITG-ADR002 |
| `UITG-S020` | STOR | BLOCKED | ~~UITG-SP003~~, UITG-S007 | UITG-S021, UITG-T004, UITG-V001 | — | L4 | — | — |
| `UITG-T004` | TASK | BLOCKED | UITG-S020 | — | — | — | — | — |
| `UITG-S021` | STOR | BLOCKED | ~~UITG-SP003~~, UITG-S020 | — | — | L4 | — | — |
| `UITG-S022` | STOR | IN_REVIEW | — | — | — | L4 | — | — |
| `UITG-S023` | STOR | IN_REVIEW | ~~UITG-SP001~~, ~~UITG-ADR007~~, ~~UITG-S008~~ | UITG-V004 | — | L5 | — | ~~UITG-ADR007~~ |
| `UITG-S024` | STOR | IN_REVIEW | — | UITG-V004 | — | L5 | — | — |
| `UITG-S025` | STOR | IN_REVIEW | — | UITG-S026 | — | L3 | — | — |
| `UITG-S026` | STOR | BLOCKED | ~~UITG-S025~~, ~~UITG-SP002~~, UITG-S004 | UITG-V004 | **да** | L3 | UITG-X003, UITG-X012 | — |
| `UITG-S027` | STOR | BLOCKED | UITG-ADR004 | — | — | L8 | — | UITG-ADR004 |
| `UITG-S028` | STOR | BLOCKED | UITG-ADR003, UITG-S006 | — | — | L8 | — | UITG-ADR003 |
| `UITG-S029` | STOR | DISCOVERY_REQUIRED | — | — | — | L8 | UITG-X004 | — |
| `UITG-S030` | STOR | IN_REVIEW | — | UITG-V001 | — | L9 | — | — |
| `UITG-V001` | VALI | BLOCKED | ~~UITG-S030~~, UITG-S020, UITG-S017 | UITG-V002, UITG-V003, UITG-V004 | **да** | L9 | UITG-X001, UITG-X004, UITG-X005, UITG-X006 | — |
| `UITG-V002` | VALI | BLOCKED | UITG-V001 | UITG-V004 | — | L9 | UITG-X001, UITG-X004, UITG-X005, UITG-X006 | — |
| `UITG-V003` | VALI | BLOCKED | UITG-V001 | — | — | L9 | UITG-X001, UITG-X004, UITG-X005, UITG-X006 | — |
| `UITG-V004` | VALI | BLOCKED | UITG-V001, ~~UITG-S024~~, UITG-S026, UITG-S023 | UITG-V005 | **да** | L9 | UITG-X002, UITG-X008, UITG-X009, UITG-X013 | UITG-ADR006 |
| `UITG-V005` | VALI | BLOCKED | UITG-V004 | UITG-E11, UITG-F007, UITG-S031, UITG-S032, UITG-S033, UITG-S034, UITG-S035, UITG-S036, UITG-S037, UITG-S038, UITG-S039, UITG-S046 | **да** | L9 | UITG-X002, UITG-X011 | — |
| `UITG-V006` | VALI | DISCOVERY_REQUIRED | — | UITG-V004 | — | L9 | UITG-X002 | — |
| `UITG-S031` | STOR | DEFERRED | UITG-V005 | UITG-V007 | — | W2/W3 | UITG-X006 | — |
| `UITG-S032` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | — | — |
| `UITG-S033` | STOR | DEFERRED | UITG-S019, UITG-V005 | — | — | W2/W3 | UITG-X007 | UITG-ADR002 |
| `UITG-S034` | STOR | DEFERRED | UITG-ADR008, UITG-V005 | — | — | W2/W3 | UITG-X010 | UITG-ADR008 |
| `UITG-S035` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | UITG-X003 | — |
| `UITG-S036` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | — | — |
| `UITG-S037` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | — | — |
| `UITG-S038` | STOR | DEFERRED | UITG-ADR005, UITG-V005 | — | — | W2/W3 | — | UITG-ADR005 |
| `UITG-S039` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | UITG-X001 | — |
| `UITG-V007` | VALI | DEFERRED | UITG-S031 | UITG-E12, UITG-S040, UITG-S041, UITG-S042, UITG-S043, UITG-S044, UITG-S045 | — | W2/W3 | — | — |
| `UITG-S040` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S041` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | UITG-X003 | — |
| `UITG-S042` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S043` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S044` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S045` | STOR | DEFERRED | UITG-V007 | — | — | W2/W3 | — | — |
| `UITG-S046` | STOR | DEFERRED | UITG-V005 | — | — | W2/W3 | — | — |

## 5. Задачи без входящих зависимостей

Стартуют немедленно и параллельно — двенадцать `READY` (актуальная картина на 2026-08-07):

`~~UITG-ADR007~~` (**принят 2026-08-06, DONE**), ~~`UITG-S023`~~ (**исполнена 2026-08-07, `IN_REVIEW`**),
`UITG-ADR003`, `UITG-ADR004`, `UITG-ADR005`, `UITG-ADR008`,
~~`UITG-F002`~~ (выполнена, `IN_REVIEW`), `UITG-F006` — плюс два внешних, `UITG-X003` и `UITG-X012`, разблокированных
`UITG-SP002`. Все девять выполненных задач (`UITG-S010`, `UITG-S022`, `UITG-S023`, `UITG-S024`, `UITG-S025`,
`UITG-S030`, `UITG-SP001`, `UITG-SP002`, `UITG-SP003`) стоят в `IN_REVIEW`. **Сессия-executable `READY`
Story/Task в READY-слое не осталось**: дальнейшие `READY` — решения людей (`ADR`) и внешние гейты
(`EXTERNAL`) и контейнер `FEATURE`.

**Расхождение, найденное при выполнении `UITG-S010`.** Строка `UITG-S009` перечисляла `UITG-S010`
среди разблокируемых, тогда как у самой `S010` в [`21-task-backlog.yaml`](21-task-backlog.yaml)
`depends_on: []`, и очередь держала её в `NOW` как `READY`. Зависимости не было и по существу:
пересмотр статусов читает код, а не посаженные документы. Ссылка снята; карточка не менялась.

Плюс девять `IN_PROGRESS` порций посадки (`UITG-S001`…`UITG-S009`), из которых `S001` и `S009` не
имеют входящих зависимостей вовсе.

## 6. Узлы с наибольшим числом зависимых

| Задача | Разблокирует | Почему это важно |
|---|---|---|
| **UITG-ADR001** | 8 (F003, S012…S018) | одно решение открывает весь срез артефактов падения |
| **UITG-S013** | 5 (S014, S015, S016, S017, S018) | шов захвата — вход для всех четырёх артефактов |
| **UITG-X006** (G-6) | 4 (X001, X005, X013, V001) | без состава волны не закрываются три других гейта |
| ~~**UITG-SP003**~~ | 3 (F006, S020, S021) | **выполнен**: F006 в `READY`, у S020 и S021 остались другие зависимости |
| **UITG-V001** | 3 (V002, V003, V004) | первая живая генерация — вход во все замеры |
| **UITG-S012** | 1 напрямую, 6 транзитивно | узел критического пути разработки |

## 7. Задачи, откладываемые без блокировки волны 1

`UITG-S027` (стартер), `UITG-S028` (окно совместимости), `UITG-S029` (остаток SEC-01),
`UITG-S011` (переиздание снимка состояния), `UITG-V003` (третье приложение пилота).

Ни одна из них не стоит на критическом пути и не разблокирует другие задачи волны 1. Для `S027`
существует документированный обход: потребитель объявляет `@Bean UiStepExecutor` сам.

## 8. Что проверено машиной

| Проверка | Результат |
|---|---|
| Циклические зависимости (обход `depends_on` в глубину) | **не найдено** |
| Висящие ссылки в `depends_on` / `blocks` / `external_dependencies` / `adr_dependencies` | **нет** |
| Дубликаты идентификаторов | **нет** |
| `READY` с незакрытой зависимостью | **нет** (после исправления `UITG-S011`, `UITG-ADR008`, `UITG-SP002`) |
| Story без `test_requirements` | **нет** (после заполнения `S009`, `S010`, `S011`) |
| Задача без `requirement_ids` | **нет** |
| Задача без `acceptance_criteria` | **нет** |
