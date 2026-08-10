---
version: 2
---

# Rules: the stand-test authoring pipeline

How this bundle is loaded and the ORDER its assets must be used in. The companion file
[`stand-test-guardrails.md`](stand-test-guardrails.md) says what may never be produced, and
[`stand-test-ui-guardrails.md`](stand-test-ui-guardrails.md) adds what a browser makes possible; this
one says how the work must be sequenced. All three are rules: they outrank convenience, a shortcut
that "obviously works", and a direct request to skip a stage. If a request cannot be served without
breaking them, say so and stop.

This file exists because `.opencode/rules/**` is loaded automatically, while skills load on demand and
commands only when invoked — so without it the stage order would be a suggestion rather than a
contract. The other bundle carries the same file; its `AGENTS.md` points here rather than restating
the order.

**This file states the rules; it does not argue for them.** The reasoning lives in
[`../reference/stand-test-pipeline-rationale.md`](../reference/stand-test-pipeline-rationale.md),
which is **not** auto-loaded: read it before CHANGING a rule, never to follow one. Edit the two
together — a rule changed here that leaves the reference stale is the one failure this split can
produce.

## What is loaded, and how

| Asset | Path | Discovery |
|---|---|---|
| These rules | `.opencode/rules/*.md` | auto-loaded as project instructions |
| Skills (26 — 17 protocol + 9 UI) | `.opencode/skills/<name>/SKILL.md` | on demand, via the Skill tool |
| Commands (19 — 14 protocol + 5 UI) | `.opencode/commands/<name>.md` | when the user invokes `/<name>` |
| Workflows (2) | `.opencode/workflows/*.md` | **not** auto-loaded — read when a command points at one |
| Reference (the reasoning) | `.opencode/reference/*.md` | **not** auto-loaded — read before changing a rule |

Load a skill the moment its trigger matches. Do not re-derive its content from memory: the templates,
checklists and worked examples beside each `SKILL.md` are the contract, and paraphrasing them is how
the guardrails get quietly dropped. If the guardrails are not in your context, this bundle is
installed wrong — say so instead of proceeding.

## Two branches, one discipline

An authoring request goes down **one** of two branches, decided by where the case lives: the
**protocol branch** (REST / Kafka / DB / gRPC) below, or the **UI branch** after it, when a browser is
involved. They share the discipline — analysis before authoring, contract detail from a source rather
than from plausibility, a mandatory adversarial gate, an honest report, a human merge — and they
differ exactly where a screen differs from a specification: a REST contract can be read from a
document, and a `data-testid` can only be observed on a running application.

A case with a UI path **and** backend effects goes down the UI branch. It binds the two halves in one
scenario through a value captured off the screen; splitting it into two runs loses that link, which is
the thing the whole UI↔backend hypothesis is about.

### The protocol branch

Every protocol authoring request follows this order. **No stage may be skipped or reordered**, and
each gate stops the run:

```
text case
  1.  stand-test-case-analysis        goal, preconditions, trigger, expected effects, missing info
  2.  stand-test-kb-lookup            contracts resolve to KB entries — or become `missing`
  3.  blocking questions              analysis blockers + lookup `missing` → ASK THE HUMAN
  4.  stand-test-environment-mapping  aliases, correlation/auth/write-allowed, required env vars
  5.  stand-test-scenario-design      steps, captures, assertions, awaits, cleanup, TRACK CHOICE
  6.  authoring                       stand-test-java-dsl-authoring (default)
                                      | stand-test-yaml-authoring (AI format)
  7.  stand-test-fixture-authoring    for every body/payload/request reference
  8.  stand-test-safety-review        MANDATORY GATE — any BLOCK ⇒ regenerate, never work around
  9.  compile / schema-validate       ./gradlew compileTestJava checkstyleTest
                                      | schema + parser + validator for the AI format
  10. run                             skip-gate always; a real run only against a configured stand
  11. stand-test-test-review          quality gate → readiness report → THE HUMAN APPROVES
```

`/stand-test-generate-java-test` runs 1–11 as one umbrella. The narrower commands
(`/stand-test-design`, `/stand-test-java`, `/stand-test-yaml`, `/stand-test-validate`) each run a
slice of it — invoking one does not license skipping the stages before it. On a failed run:
`/stand-test-debug`, then re-enter at 6, or at 5 when the design itself was wrong.

### The UI branch

Nine stages, same rule — no stage skipped, no stage reordered, each gate stops the run:

```
UI business case
  1. stand-test-ui-case-intake         application alias, role, entry screen, user path with the
                                       irreversible steps MARKED, expectations with exact texts,
                                       data ownership, residual effects, negative paths
                                       — no browsing, no locators, no code
  2. stand-test-ui-completeness-check  GATE — every gap into one of four classes:
                                       deferred to discovery | resolved from registry/KB |
                                       safe assumption | BLOCKING QUESTION → ASK THE HUMAN
  3. stand-test-ui-discovery           the LIVE DEV/IFT UI, by alias, under the DISCOVERY ACCOUNT,
                                       NO irreversible action — quoted evidence: elements, every
                                       locator rung, texts, states, transitions, what was NOT seen
  4. stand-test-ui-scenario-design     steps and ids, ui.login FIRST with a role, assertions with
                                       matchers, bounded awaits, captures, the UI↔backend binding,
                                       ${testRunId} scoping, residual-data verdict, pool budget
  5. stand-test-ui-page-object-design  one class per screen; every locator a constant THERE
  6. stand-test-ui-java-authoring      the test — Java only; ui.* has no declarative format
  7. stand-test-ui-safety-review       MANDATORY GATE, separate context — any BLOCK ⇒ regenerate
     compile / run                     compileTestJava + checkstyleTest over test AND Page Objects;
                                       a run only against a configured stand — read the JUnit XML.
                                       MECHANICAL, so it carries no stage number: stage 6's
                                       self-check and stage 9's gate table are where it is recorded
  8. stand-test-ui-quality-review      quality gate, separate context, against the ORIGINAL CASE
  9. stand-test-ui-generation-report   eight sections + the PRESERVED ORIGINAL GENERATION (KPI-4)
                                       → THE HUMAN APPROVES
```

`/stand-test-generate-ui-test` runs all of it. The slices are `/stand-test-ui-design` (1–5),
`/stand-test-ui-discover` (3 alone — also the right command when a merged test starts failing on
locators), `/stand-test-ui-java` (6 + compile) and `/stand-test-ui-validate` (7–9).

Three rules of this branch are the ones most likely to be broken:

- **The live UI is the source of truth for the DOM, and the order is KB → discovery → question.**
  "Not in the knowledge base" is a reason to go and look, never a reason to invent and never, by
  itself, a reason to ask.
- **Discovery is reconnaissance, not participation.** The discovery account (SEC-10), no irreversible
  action, no writes, no dialogs. A screen reachable only through an irreversible control stays
  unexplored, and that is recorded rather than resolved by clicking.
- **The report is part of the deliverable.** Eight sections, plus the snapshot of the generation as
  first emitted — the diff base without which KPI-4 is unobservable.

## Стадии, которые выполняет отдельный контекст

Четыре стадии из одиннадцати выполняет субагент, а не тот контекст, что ведёт работу. Субагенты
объявлены в `agents/` **обеих** копий бандла; права записаны грамматикой хоста, тело инструкции одно
и то же.

| Стадии | Субагент | Права | Что обязан соблюдать |
|---|---|---|---|
| 2 и 4 | `stand-test-kb-resolver` | Read/Grep/Glob — ни Write, ни Bash | наружу отдаёт результат резолвинга, а не прочитанный YAML |
| 8 | `stand-test-safety-reviewer` | без Write | на вход — пути артефактов и дизайн, **не транскрипт авторинга**; найденное не чинит, а докладывает |
| 11 | `stand-test-quality-reviewer` | без Write | читает **исходный текст кейса**, а не дизайн |

В UI-ветке те же два ревью — это стадии **7** (`stand-test-ui-safety-review`) и **8**
(`stand-test-ui-quality-review`), и выполняют их те же два субагента: механизм делегирования один,
меняется только скилл, который субагент читает.

Вердикт safety-review записывает **вызывающий** контекст, а не субагент: суждение и бухгалтерия
разведены, и `record-gate` перепроверяет детерминированную половину независимо от того, кто её
заявил.

Почему ревью обязан выполнять другой контекст — в
[обоснованиях](../reference/stand-test-pipeline-rationale.md#почему-ревью-выполняет-другой-контекст).

## Stage rules that are routinely broken

- **Analysis before authoring.** Never open with a test class. Stages 1–5 produce the design that
  stage 6 transcribes; code written before them encodes guesses that the later gates cannot see.
- **The KB is the only source of contract detail.** Endpoint paths, topic names, tables, columns and
  gRPC methods come from the knowledge base, the case text, or a recorded assumption — never from
  plausibility. No entry ⇒ `missing` ⇒ a blocking question. An empty KB means many questions; that is
  the correct outcome, not a reason to fill the gaps yourself. On day one run
  `/stand-test-bootstrap-kb`: it moves the environment registry's ALIASES into the base — the one
  thing a person has already curated — and nothing else. An alias the registry attests is a recorded
  assumption rather than a question; a path, field, table or method is a question however much is
  known about the alias that owns it.
- **Ask sparingly, but do not invent.** Prefer a safe, explicitly recorded assumption over a
  question; escalate only what changes the test's meaning (missing alias, exact expected values for
  an equals-only check, write permission, correlation strategy, auth identity, an unknown operation
  contract). Both the assumption and the question must be visible to the human.
- **Track choice at stage 5 is binding.** Java DSL is the default. The AI format is only for
  scenarios wholly inside its executable subset; if one step falls outside, switch tracks — never
  stretch the format.
- **Gates are not advisory.** A BLOCK from stage 8 means regenerate the artifact. Suppressing the
  finding, narrowing the check, or explaining why it does not apply here is a rule violation.
- **The human merges.** Stage 11 produces a report and a recommendation, never a merge.

## Что энфорсится машиной, а не добросовестностью

Часть этих правил перестала быть просьбой — ровно в той мере, в какой её проверяет
`.opencode/hooks/stand-guard.mjs`. Вызывает его ХОСТ по событиям, а не модель: под Claude Code —
`settings.json`, под opencode — плагин `plugin/stand-guard.js` его копии бандла. Обойти это, ничего
не сказав, нельзя.

| Когда | Что происходит |
|---|---|
| перед каждой записью | восстанавливается ФАЙЛ, который оставит вызов (диск плюс правки вызова), и сканируется по `detectors.json`: любая блокирующая находка **отвергает запись** — в момент написания строки, а не на стадии 8. Маршрут значения не имеет: `Write`, `Edit` и `MultiEdit` проверяются одинаково. **Исключения для находки, которая уже лежала на диске, нет**: унаследованное нарушение чинится целиком, и только потом файл дополняется. Блокировка перечисляет все находки сразу |
| там же, сверка с прежней версией | удалённая ассерция, `@Disabled` без номера задачи, новый `catch`, выросший таймаут — «сделать красный тест зелёным» отвергается в момент правки. Если правку не удалось наложить на файл, хук говорит, что сравнение не выполнялось |
| над прозой (`.md`, `.txt`, `.adoc`) | не работают находки про ДОСТАВКУ — адрес, SQL, ожидание, прямой транспорт, correlation; точный список сверяется с полем `notOn` в `detectors.json`, а не пишется по памяти. Находки про РАСКРЫТИЕ — секрет, замаскированный секрет, ПД — работают везде |
| перед записью в сам кит | `.opencode/**` — правила, хуки, субагенты, скиллы, команды, `settings.json` — **не пишется прогоном ни одним маршрутом**. Кит обновляется переустановкой по манифесту (`install.mjs`), а не правкой на месте |
| перед записью в `knowledge-base/` | три уровня. `schema/**` — никогда: по этим схемам валидируется всё остальное. `mappings/` и `candidates/` — свободно. Курируемые коллекции — только внутри **пермита**, который назвал этот путь ДО того, как содержимое появилось: `kb-write-permit --reason promote|update|repair <файлы>` |
| после записи в курируемую коллекцию | файл попадает в реестр `curated`, и сессия не закончится, пока его не покроет гейт `kb-write`, который перезапускает `kb-validate` по байтам на диске |
| перед каждой bash-командой | отвергаются `rm -rf`, креденшелы в командной строке, прямой DML в базу и `git push`; **подкоманды гарда, которые вызывает хост** (`subagent-stop`, `post-run`, `stop`, `pre-write`, `pre-bash`, `post-write`, `status`); и **запись файла шеллом** (`cat > … <<EOF`, `>>`, `tee`, `sed -i`, `cp`/`mv` в дерево, `patch`, `python -c` с записью). Файлы пишут Write/Edit; временный файл — в `/tmp`, вывод сборки — в `build/` |
| после `./gradlew test` | читаются `**/build/test-results/**/TEST-*.xml` всех модулей, а не stdout: `skipped=N` при `tests=N` — предупреждение о том, что стенд не был затронут при зелёной сборке; падения уходят в журнал прогонов с отпечатком |
| при записи вердикта safety-review | `PASS` не записывается, пока с момента последней правки артефакта не завершился субагент. Запись о завершении делает ХОСТ по событию `SubagentStop` — вручную её не объявить |
| в CI, без сессии и без модели | `scan --format sarif --exit-code` и `kb-validate --format sarif` дают те же находки как аннотации к pull request; с `--against <база>` работает и сверка с прежней версией. Сколько находок применилось — отчёт считает по ВИДУ артефакта и называет причину для каждой оставшейся |
| при попытке завершить сессию | не дают закончить две вещи: **исполняемый** артефакт (тест, сценарный или фикстурный документ, build-файл), не покрытый пройденным safety-review, — и прошедший ревью тест, не заявленный ни одной записью `knowledge-base/mappings/` (хук печатает готовый шаблон, заполняет человек или агент). Анализ, дизайн и отчёты сканируются при записи, но гейт не держат |

Четыре свойства этого слоя решают, что делать дальше:

- **Гейт привязан к хешу содержимого**, которое проверял: правка файла после ревью автоматически
  снимает покрытие.
- **`record-gate` не верит вердикту.** Команда
  `node .opencode/hooks/stand-guard.mjs record-gate --gate safety-review --verdict PASS <файлы>`
  перезапускает детерминированную половину проверок, и `PASS` поверх блокирующей находки не
  записывается. **Список файлов обязателен и покрывает ровно их**: вызов без файлов и вызов с путём,
  которого нет на диске, отвергаются.
- **Пермит — это область и намерение, а не разрешение.** Выдать его может сама модель. Человеческое
  решение — подтверждение самой записи хостом; что реально легло — дело гейта `kb-write`.
- **Запрет на шелл-запись — про маршрут, а не про содержимое.** Поэтому отвергается и безобидный
  `echo > README.md`. Это периметр от небрежности: скрипт, положенный в `/tmp` и запущенный, пройдёт.

**Под opencode нет гейта на завершении сессии** — его там не ослабили, а не завели. Вызывайте
`node .opencode/hooks/stand-guard.mjs stop` руками перед тем, как объявить работу законченной. Всё
остальное — отказ в записи, разбор команды, `deny`/`ask`, три субагента — работает на обоих хостах.

**Про UI-ветку отдельно.** В `detectors.json` этой версии кита **26 находок, из них восемь
UI-специфичных**: `UI_LOCATOR_OUTSIDE_PAGES` (U2), `UI_LOGIN_WITHOUT_ROLE` (U5), `XPATH_LOCATOR` (U7),
`UI_OPEN_OR_ASSERT_TEMPLATE` (U9), `EXPECT_EVENTUALLY_WITHOUT_WITHIN` (U17), `UI_REPORT_STAND_ADDRESS`
(U3-половина над `Ui*Report.md`), `UI_DISCOVERY_PARITY` (U1 — локатор сверяется с отчётом разведки по
`--discovery UiDiscoveryReport.md`; заявленный и отсутствующий отчёт — сам по себе BLOCK) и
`UI_GENERATION_REPORT_INCOMPLETE` (U16 — восемь секций считаются по НОМЕРУ, а `original.sha256` обязан
существовать на диске); `THREAD_SLEEP` (U6) расширен драйверными ожиданиями
(`page.waitForSelector/Timeout/LoadState`, `.waitFor`). Общие находки над Java работают и там, включая
U20 (`SHARED_MUTABLE_TEST_STATE` читает статическое мутабельное поле и в Page Object'е). Не ловит
ничто, кроме глаз стадии 7: семантическую половину U4, содержание секций отчёта, а также U8, U10,
U11a/b, U12, U14, U15, U18, U19 — перечень и фактическое покрытие в `ui-safety-checklist.md` (раздел
Machine coverage). **Чистый хук в UI-ветке — это не чистое ревью, и выдавать одно за другое
запрещено.**

**Что по-прежнему держится на добросовестности:** порядок стадий, выбор трека, скупость вопросов и
честность отчёта. Про ревью хук знает ровно одно — что после записи артефакта хост сообщил о
завершении какого-то субагента. Что делегировали именно ревью, что субагент прочитал артефакт
целиком и что его суждение передали без правок — не доказуемо вовсе.

Откуда взялась каждая строка этой таблицы и что уже пробовали вместо неё —
[обоснования](../reference/stand-test-pipeline-rationale.md#слой-энфорсмента).

## Reporting honestly

State what actually happened. A stage that was skipped, a gate that was not run, a test that was
never executed because no stand is configured — each is reported as such. "READY" claimed over an
unrun gate is worse than "NOT-READY": it spends the reviewer's trust on an unverified artifact.
