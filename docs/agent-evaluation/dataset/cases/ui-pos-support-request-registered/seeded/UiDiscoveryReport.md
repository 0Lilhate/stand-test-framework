# UI Discovery Report: ui-pos-support-request-registered / client-portal

> **Подсаженный артефакт корпуса.** Это отчёт разведки, который стадия 3 произвела бы на живом
> стенде; кейс отдаёт его агенту готовым. Так UI-кейс становится прогоняемым без браузера, а гейт U1
> («у каждого локатора есть строка отчёта разведки») — проверяемым: Page Object сверяется с этой
> таблицей, и локатор, которого здесь нет, выдуман.
>
> Экран вымышленный, как и всё приложение `client-portal`. Адресов, учётных данных и персональных
> значений здесь нет и быть не может.

## Session header

| Field | Value |
|---|---|
| Case id | `ui-pos-support-request-registered` |
| Application alias | `client-portal` |
| Environment | `ift` |
| Account used | **discovery account** (`auth.discovery-account-ref`) |
| Sign-in scheme observed | `FORM` |
| Viewport | `desktop` 1440×900 |
| Channel | `playwright-mcp` |
| Date of observation | 2026-08-03 |

## Screens visited

| # | Screen (case name) | Relative path | Reached by | Notes |
|---|---|---|---|---|
| 1 | Вход | `/login` | direct | форма входа; поля перечислены в разделе «Sensitive elements» |
| 2 | Обращение в поддержку | `/requests/new` | меню «Поддержка» → «Новое обращение» | |

## Elements observed

| # | Case name | `data-testid` | role + accessible name | label | stable attribute | visible text (as loaded) | CSS fallback | Chosen locator | Why not a higher rung | Fragile? | Brittle? | Unique? |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | поле «Тема» | — | `textbox` / — | `Тема` | `name="subject"` | — | `#subject` | `label=Тема` | нет `data-testid`; подпись не связана с контролом, поэтому у роли нет доступного имени | yes (rung 3) | no | yes — 1 match |
| 2 | поле «Описание» | — | `textbox` / — | `Описание` | `name="description"` | — | `#description` | `label=Описание` | то же, на той же форме | yes (rung 3) | no | yes — 1 match |
| 3 | кнопка «Отправить» | — | `button` / `Отправить` | — | — | `Отправить` | `.request-form__send` | `role=button:Отправить` | нет `data-testid` на контроле | yes (rung 2) | no | yes — 1 match |
| 4 | поле «Статус обращения» | `request-status` | — | — | — | `Черновик` | — | `testId=request-status` | — | **no** | no | yes — 1 match |
| 5 | поле «Номер обращения» | `request-number` | — | — | — | — (в DOM есть, пусто до отправки) | — | `testId=request-number` | — | **no** | no | yes — 1 match |

Legend: `—` означает *посмотрели и нет*, а не *не смотрели*. Не осмотренное — в разделе *Unexplored*.

## Texts observed (verbatim)

| # | Element | Text as observed | Text as the case states it | Same? |
|---|---|---|---|---|
| 1 | подпись кнопки | `Отправить` | `Отправить` | yes |
| 2 | «Статус обращения» до отправки | `Черновик` | — (кейс о нём не говорит) | n/a |
| 3 | «Статус обращения» **после отправки** | **не наблюдался** — отправка необратима и разведкой не выполнялась | `Зарегистрировано` | **не проверено** |
| 4 | «Номер обращения» после отправки | **не наблюдался** — то же | `RQ-` и цифры | **не проверено** |

Строки 3 и 4 — это ровно та форма, которую таблица принимает чаще всего, и сглаживать её нельзя:
кейс называет ожидаемый текст, который разведка **не могла** подтвердить, не выполнив необратимого
действия. Значение всё равно идёт в тест — оно принадлежит кейсу, — но едет как **записанное
допущение**, а не как наблюдение.

## States observed

| # | Element | State | When | Evidence |
|---|---|---|---|---|
| 1 | кнопка «Отправить» | disabled | пока «Тема» пуста | observed on load |
| 2 | поле «Номер обращения» | present but empty | до отправки | элемент есть в DOM, поэтому его `data-testid` наблюдаем; его *текст* — нет |

## Transitions observed

| From | Control | To (screen / relative path) | Irreversible? |
|---|---|---|---|
| Обращение в поддержку | «Отправить» | тот же экран, появляется статус и номер | **yes — not performed** |

## Irreversible controls: stopped before

| # | Control | What the label / dialog says will happen | Screens left unexplored because of it |
|---|---|---|---|
| 1 | кнопка «Отправить» | создаёт обращение в системе поддержки | экран отправленного обращения |

## Sensitive elements (require `asSensitive()`)

| # | Element | What it holds | Value recorded? |
|---|---|---|---|
| 1 | поле «Пароль» на форме входа | credential | **no — shape only** |

## Unexplored

| # | What | Why | Consequence for the test |
|---|---|---|---|
| 1 | экран отправленного обращения | достижим только через необратимую отправку | проверки после отправки ограничены тем, что видно на исходном экране |
| 2 | поведение при повторной отправке той же темы | потребовало бы двух необратимых отправок | дубликат кейсом не проверяется |

## Confidence and limits

| Item | Confidence | Limit |
|---|---|---|
| локаторы строк 1–5 | high | наблюдались через автоматизационный канал, каждый проверен на единственное совпадение |
| поведение после отправки | **none** | не выполнялось (необратимо) |
