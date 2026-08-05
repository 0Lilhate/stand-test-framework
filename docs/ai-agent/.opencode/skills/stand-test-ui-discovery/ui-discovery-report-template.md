# UI Discovery Report: <case-id> / <application alias>

> Output of skill `stand-test-ui-discovery` (stage 3). Evidence, not conclusions: what was observed
> on a live DEV/IFT stand, quoted. **No addresses, no credentials, no personal values.**

## Session header

| Field | Value |
|---|---|
| Case id | |
| Application alias | `<alias>` — never a URL |
| Environment | `dev` / `ift` (production is not explorable) |
| Account used | **discovery account** (`auth.discovery-account-ref`) — never a pool account |
| Sign-in scheme observed | `FORM` / `STORAGE_STATE` / none |
| Viewport | profile name + `WxH` from `viewport-profiles` |
| Channel | `playwright-mcp` / `browser-mcp` / `human-snapshot` / `screenshot` |
| Date of observation | `YYYY-MM-DD` |

> A report older than the UI it describes is a source of invented locators. Record the date, and
> re-run discovery rather than trusting an old one when a merged test starts failing on locators.

## Screens visited

| # | Screen (case name) | Relative path | Reached by | Notes |
|---|---|---|---|---|
| 1 | Вход | `/login` | direct | |
| 2 | Новая заявка | `/applications/new` | menu «Заявки» → «Создать» | |

## Elements observed

One row per element the case's steps or expectations touch. Record **every rung that exists**, not
only the chosen one — the next generation on this screen reads this table instead of browsing again.

`Fragile?`, `Brittle?` and `Unique?` are three different questions and the report answers all three:
fragility is `UiLocator.fragile()` (false only for `TEST_ID`); brittleness is the long-CSS predicate of
the guardrails §8; uniqueness is how many elements the locator matched — **more than one is a
`StandTestException` (BROKEN) at run time**, so a locator that was never checked for it is a locator
that has not been discovered.

| # | Case name | `data-testid` | role + accessible name | label | stable attribute | visible text (as loaded) | CSS fallback | Chosen locator | Why not a higher rung | Fragile? | Brittle? | Unique? |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | поле «Сумма» | — | `textbox` / — | `Сумма` | `name="amount"` | — | `#amount` | `label=Сумма` | no `data-testid`; the label is not associated with the control, so the role carries no accessible name to address it by | yes (rung 3) | no | yes — 1 match |
| 2 | кнопка «Подтвердить» | — | `button` / `Подтвердить` | — | — | `Подтвердить` | `.form__submit` | `role=button:Подтвердить` | no `data-testid` | yes (rung 2) | no | yes — 1 match |
| 3 | поле «Статус» | `application-status` | — | — | — | — (в DOM есть, пусто до отправки) | — | `testId=application-status` | — | **no** | no | yes — 1 match |
| 4 | поле «Внешний номер» | — | `textbox` / — | `Внешний номер` | `name="externalId"` | — | `#externalId` | `label=Внешний номер` | no `data-testid`; label not associated with the control | yes (rung 3) | no | yes — 1 match |
| 5 | поле «Номер заявки» | `application-number` | — | — | — | — (в DOM есть, пусто до отправки) | — | `testId=application-number` | — | **no** | no | yes — 1 match |

Legend: `—` means *observed and absent*, not *not looked at*. Anything not looked at belongs in
*Unexplored* below.

## Texts observed (verbatim)

Copy exactly: case, punctuation, «ёлочки», non-breaking spaces, trailing spaces. These become
string comparisons.

| # | Element | Text as observed | Text as the case states it | Same? |
|---|---|---|---|---|
| 1 | подпись кнопки | `Подтвердить` | `Подтвердить` | yes |
| 2 | поле «Статус» **после отправки** | **не наблюдался** — отправка необратима и не выполнялась | `Принята` | **не проверено** |

Row 2 is the shape this table takes more often than anyone would like, and it must not be smoothed
over: the case states an expected text that discovery **could not** confirm, because seeing it would
have required the irreversible action §5 forbids. The value still goes into the test — it is the
case's expectation — but it travels as a **recorded assumption**, not as an observation, and the
generation report says so. Writing `Принята` into the *observed* column because the case says so is
precisely the invented value this stage exists to prevent.

> A row where the two differ is a **finding for the human**, not a value to reconcile. The screen is
> right about what is displayed; the case is right about what should be. Which one is the defect is
> not the agent's call.

## States observed

| # | Element | State | When | Evidence |
|---|---|---|---|---|
| 1 | кнопка «Подтвердить» | disabled | пока «Сумма» пуста | observed on load |
| 2 | поле «Номер заявки» | present but empty | до отправки | observed on load — the element is in the DOM, so its `data-testid` is observable; its *text* is not |

## Transitions observed

| From | Control | To (screen / relative path) | Irreversible? |
|---|---|---|---|
| Новая заявка | «Подтвердить» | Новая заявка (тот же экран, появляется статус) | **yes — not performed** |

## Irreversible controls: stopped before

| # | Control | What the label / dialog says will happen | Screens left unexplored because of it |
|---|---|---|---|
| 1 | кнопка «Подтвердить» | создаёт заявку в системе | экран поданной заявки |

## Sensitive elements (require `asSensitive()`)

| # | Element | What it holds | Value recorded? |
|---|---|---|---|
| 1 | поле «Пароль» на форме входа | credential | **no — shape only** |

## Unexplored

| # | What | Why | Consequence for the test |
|---|---|---|---|
| 1 | экран поданной заявки | достижим только через необратимое действие | проверки после отправки ограничены тем, что видно на исходном экране |

## Proposed registry additions (for a human to apply — NOT applied here)

| Field | Proposed value | Why |
|---|---|---|
| `auth.login.username-locator` | `role=textbox:Логин` | required by `FORM` sign-in; absent today |
| `auth.login.signed-in-locator` | `testId=user-menu` | the only element present exclusively when signed in |

> Locator expressions in the registry are spelled `<strategy>=<value>` (`testId=`, `role=<role>:<name>`,
> `label=`, `text=`, `css=`). There is no `xpath=`. A credential never goes into a `*-locator` field —
> the shape check refuses it, and refuses it without echoing what was written.

## Confidence and limits

| Item | Confidence | Limit |
|---|---|---|
| locators of rows 1–5 | high | observed directly through the automation channel, each checked for a single match |
| behaviour after submit | **none** | not performed (irreversible) |
