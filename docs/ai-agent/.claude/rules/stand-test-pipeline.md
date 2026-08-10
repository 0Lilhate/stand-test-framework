---
version: 1
---

# Rules: the stand-test authoring pipeline

How this bundle is loaded and the ORDER its assets must be used in. The companion file
[`stand-test-guardrails.md`](stand-test-guardrails.md) says what may never be produced, and
[`stand-test-ui-guardrails.md`](stand-test-ui-guardrails.md) adds what a browser makes possible; this
one says how the work must be sequenced. All three are rules: they outrank convenience, a shortcut
that "obviously works", and a direct request to skip a stage. If a request cannot be served without
breaking them, say so and stop.

This file exists because `.claude/rules/**` is loaded automatically, while skills load on demand and
commands only when invoked — so without it the stage order would be a suggestion rather than a
contract. The other bundle carries the same file; its `AGENTS.md` points here rather than restating
the order.

## What is loaded, and how

| Asset | Path | Discovery |
|---|---|---|
| These rules | `.claude/rules/*.md` | auto-loaded as project instructions |
| Skills (26 — 17 protocol + 9 UI) | `.claude/skills/<name>/SKILL.md` | on demand, via the Skill tool |
| Commands (19 — 14 protocol + 5 UI) | `.claude/commands/<name>.md` | when the user invokes `/<name>` |
| Workflows (2) | `.claude/workflows/*.md` | **not** auto-loaded — read when a command points at one |

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

Three rules of this branch are the ones most likely to be broken, and each has its own reason:

- **The live UI is the source of truth for the DOM, and the order is KB → discovery → question.**
  "Not in the knowledge base" is a reason to go and look, never a reason to invent and never, by
  itself, a reason to ask. An invented locator is the worst artifact this branch can produce: it
  compiles, it survives review by eye, and it fails at run time exactly like application drift.
- **Discovery is reconnaissance, not participation.** The discovery account (SEC-10), no irreversible
  action, no writes, no dialogs. A screen reachable only through an irreversible control stays
  unexplored, and that is recorded rather than resolved by clicking.
- **The report is part of the deliverable.** Eight sections, plus the snapshot of the generation as
  first emitted. Without that snapshot KPI-4 is not merely imprecise — it is unobservable.

## Стадии, которые выполняет отдельный контекст

Четыре стадии из одиннадцати выполняет субагент, а не тот контекст, что ведёт работу. Субагенты
объявлены в бандле для Claude Code (`agents/`); паритет для opencode делается отдельным проходом.

| Стадии | Субагент | Почему не основной контекст |
|---|---|---|
| 2 и 4 | `stand-test-kb-resolver` | самая читающая стадия с самым коротким выходом: сотни строк YAML базы знаний и реестра ради двадцати, которые важны. В основном контексте они остаются лежать до конца работы. Read/Grep/Glob — ни Write, ни Bash |
| 8 | `stand-test-safety-reviewer` | «adversarial» — это про то, КТО читает. Контекст, только что написавший тест, проверяет свой замысел, а не написанные строки; ошибка, пропущенная при авторинге, пропускается на ревью по той же причине. На вход — пути артефактов и дизайн, **не транскрипт авторинга**. Без Write: найденное он не чинит, а докладывает |
| 11 | `stand-test-quality-reviewer` | читает **исходный текст кейса**, а не дизайн: проверка теряется именно в дизайне, и ревью против дизайна этой потери не видит — артефакт дизайну соответствует, а дизайн неполон. Без Write |

В UI-ветке те же два ревью — это стадии **7** (`stand-test-ui-safety-review`) и **8**
(`stand-test-ui-quality-review`), и выполняют их те же два субагента: механизм делегирования один,
меняется только скилл, который субагент читает. Причина отделять контекст в UI-ветке ещё сильнее:
ошибка, которую ищет стадия 7, — это **выдуманный локатор**, а он синтаксически неотличим от
настоящего. Отличить их можно единственным способом — читая отчёт разведки рядом с Page Object'ом, и
контекст, который сам этот локатор написал, помнит, что решил его, а не что увидел.

Вердикт safety-review записывает **вызывающий** контекст, а не субагент: суждение и бухгалтерия
разведены, и `record-gate` перепроверяет детерминированную половину независимо от того, кто её
заявил.

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

## Что теперь энфорсится машиной, а не добросовестностью

Часть этого файла перестала быть просьбой — ровно в той мере, в какой её проверяет
`.claude/hooks/stand-guard.mjs`. Под Claude Code его вызывает ХОСТ по событиям, а не модель, и обойти
это, ничего не сказав, нельзя. Под opencode тот же гард вызывает ПЛАГИН
(`plugin/stand-guard.js` в копии для opencode) на событиях инструментов — отказ в момент записи и разбор команды
работают там так же; что при этом всё-таки не переносится, сказано в конце раздела. Таблица ниже
описывает срабатывания под Claude Code:

| Когда | Что происходит |
|---|---|
| перед каждой записью | восстанавливается ФАЙЛ, который оставит вызов (то, что на диске, плюс правки вызова), и сканируется по `detectors.json`: любая блокирующая находка в получившемся файле **отвергает запись** — в момент написания строки, а не на стадии 8. Маршрут значения не имеет: `Write`, `Edit` и `MultiEdit` проверяются одинаково, пустой `new_string` читается как удаление, пустой `old_string` — как создание файла, и в реестр идёт хеш получившегося файла, а не фрагмента. Если правку не удаётся наложить на файл, проверяются сами фрагменты замены, и хук прямо говорит, что сравнение с прежней версией не выполнялось. Плюс это сравнение: удалённая ассерция, `@Disabled` без номера задачи, новый `catch`, выросший таймаут — «сделать красный тест зелёным» отвергается в момент правки. **Исключения для находок, которые уже лежали на диске, нет.** Файл потребителя, нарушавший правило до кита, чинится целиком и только потом дополняется: отличить «ту же самую» находку от новой можно лишь по evidence, а он у части детекторов означает категорию (`https://`, `Thread.sleep(`), а не место — на этом обе попытки сделать исключение превращались либо в бессрочную индульгенцию, либо в бюджет, где два старых нарушения покупали одно новое. Блокировка перечисляет все находки, поэтому чинить не приходится вслепую. Одно различие по ФОРМАТУ: над прозой (`.md`, `.txt`, `.adoc`) не работают находки про ДОСТАВКУ — 1 адрес, 3 SQL, 6 ожидание, 11 correlation. Markdown не читает ни classpath, ни резолвер, ни JVM, и отчёт стадии 8, ЦИТИРУЮЩИЙ найденный адрес, — это описание нарушения, а не оно само; пока различия не было, гейт отвергал ровно те документы, которые сам же и требует написать. Находки про РАСКРЫТИЕ — 2 секрет, 13 замаскированный секрет, 14 PII — над прозой работают по-прежнему: из markdown секрет коммитится так же, как из Java |
| перед записью в сам кит | `.claude/**` — правила, хуки, субагенты, скиллы, команды, `settings.json` — **не пишется прогоном ни одним маршрутом**. `permissions.deny` называет все три инструмента (`Edit`, `Write`, `MultiEdit`), а сам хук отвергает запись по пути независимо от того, чем её сделали: deny-лист долго перечислял один `Edit`, и та же запись через `Write` проходила насквозь, потому что скану `.mjs`-файл почти ничем не является, а dot-каталог по построению не попадает в реестр артефактов. Кит обновляется переустановкой по манифесту (`install.mjs`), а не правкой на месте: правило, которое прогон может переписать, действует ровно до тех пор, пока прогон с ним согласен |
| перед записью в `knowledge-base/` | три уровня. `schema/**` — никогда: по этим схемам валидируется всё остальное. `mappings/` и `candidates/` — как раньше, свободно. Курируемые коллекции — только внутри **пермита**, который назвал этот путь ДО того, как содержимое появилось: `kb-write-permit --reason promote|update|repair <файлы>` |
| после записи в курируемую коллекцию | файл попадает в реестр `curated`, и сессия не закончится, пока его не покроет гейт `kb-write`, который перезапускает `kb-validate` по байтам на диске |
| перед каждой bash-командой | `rm -rf`, креденшелы в командной строке, прямой DML в базу и `git push` отвергаются. Плюс **подкоманды гарда, которые вызывает хост**: `subagent-stop`, `post-run`, `stop`, `pre-write`, `pre-bash`, `post-write`, `status`. Они пишут бухгалтерию, по которой судят гейты, и вызванные вручную не сообщают факт, а изготавливают его: пока гард стоял в allow-листе одним префиксом, строка `stand-guard.mjs subagent-stop` целиком заменяла собой доказательство, что стадию 8 выполнил отдельный контекст. Отвергается по разбору команды, а не по написанию, поэтому `env`, `sh -c` и абсолютный путь — один и тот же случай. Плюс **запись файла шеллом**: `cat > … <<EOF`, `>>`, `tee`, `sed -i`, `cp`/`mv` в дерево, `patch`, `python -c` с записью — всё это доставляло бы содержимое мимо pre-write, где стоят и скан, и сверка с прежней версией, и запись артефакта в реестр. Пишут файлы Write/Edit/MultiEdit; временный файл — в `/tmp`, вывод сборки — в `build/` |
| после `./gradlew test` | читаются `**/build/test-results/**/TEST-*.xml` всех модулей, а не stdout, и каждый файл ровно один раз (по паре путь+время правки): `skipped=N` при `tests=N` — громкое предупреждение о том, что стенд не был затронут, хотя сборка зелёная; падения уходят в журнал прогонов с отпечатком |
| при записи вердикта safety-review | `PASS` не записывается, пока с момента последней правки артефакта не завершился субагент: ревью обязан выполнить другой контекст, и это больше не обещание. Запись о завершении субагента делает ХОСТ по событию `SubagentStop` — вручную её не объявить: команда отвергается на `pre-bash`, а сам `subagent-stop` не пишет ничего, когда его позвали не событием |
| в CI, без сессии и без модели | `scan --format sarif --exit-code` и `kb-validate --format sarif` дают те же находки как аннотации к pull request: коммит, сделанный месяц назад, отвечает за себя так же, как запись в живой сессии. С `--against <база>` (`git show origin/main:<путь>`) работает и находка 18. Сколько находок применилось — отчёт считает по ВИДУ артефакта, а не по размеру таблицы: у java-файла предмет проверки — двенадцать находок из восемнадцати, у документа — тринадцать, у прозы — четыре, и строка «проверено N из 18 (java)» называет и число, и причину для каждой оставшейся («нет прежней версии» или «вид артефакта»). Раньше там стояло «17» под любым файлом — то самое завышение покрытия, которое весь остальной отчёт запрещает |
| при попытке завершить сессию (тесты) | сгенерированный тест, прошедший ревью, но не заявленный ни одной записью `knowledge-base/mappings/`, **не даёт закончить**: связь «кейс → тест» больше не держится на добросовестности. Хук проверяет, что запись есть, и печатает готовый шаблон; заполняет её человек или агент — половина записи (`matched`, `missing`, `assumptions`) это знание, которого у хука нет |
| при попытке завершить сессию | **исполняемый** артефакт — тест, сценарный или фикстурный документ, build-файл — не покрытый пройденным safety-review, **не даёт закончить**. Анализ, дизайн и отчёты сканируются при записи, но гейт не держат: ревью бывает у того, что исполняется |

Гейт привязан к **хешу содержимого**, которое он проверял: правка файла после ревью автоматически
снимает покрытие. «Прошли ревью, потом тихо подправили» закрыто механически.

Вердикт гейта записывается командой
`node .claude/hooks/stand-guard.mjs record-gate --gate safety-review --verdict PASS <файлы>` — и она
**не верит вердикту**: детерминированная половина проверок запускается заново, и `PASS` поверх
блокирующей находки не записывается. Суждение остаётся суждением; подделать машинный вывод нельзя.

**Список файлов обязателен, и вердикт покрывает ровно их.** Вызов без файлов отвергается, как и вызов
с путём, которого нет на диске: перепроверять в этих случаях нечего, а запись «прошло» о
непрочитанном содержимом — ровно то, что этот гейт существует предотвращать.

**Пермит — это область и намерение, а не разрешение.** Выдать его может сама модель: скрипт хука в
allow-листе. Он покупает другое — пути объявлены до того, как появилось содержимое, поэтому запись
мимо них отвергается, пока это ещё поправимо, и причина остаётся на виду. Человеческое решение — это
подтверждение самой записи хостом (`permissions.ask` на `knowledge-base/**`), а что реально легло —
дело гейта `kb-write`. Ни один из трёх слоёв не претендует на силу соседнего.

**Запрет на шелл-запись — это про маршрут, а не про содержимое.** Вопрос «безопасно ли это» уже имеет
ответ, и отвечает на него скан; периметр отвечает на другой — «прошло ли содержимое той дорогой, где
этот вопрос задают». Поэтому отвергается и безобидный `echo > README.md`: разбирать содержимое
шелл-команды значило бы завести второй сканер, который однажды скажет PASS там, где первый сказал бы
BLOCK. И поэтому же периметр честно называется периметром от небрежности, а не от намеренного обхода:
скрипт, положенный в `/tmp` и запущенный, или запись, написанная в `python -c` незнакомым способом,
пройдут. Он покупает то, что быстрый путь — это проверенный путь.

**Про второй хост.** Гард едет в обе копии — это обычный Node, и `scan`, `kb-validate`, `alias-check`,
`kb-status`, `record-gate`, `doctor` работают одинаково там и там. ПРИВЯЗКА К СОБЫТИЯМ теперь тоже
едет, но другим механизмом: `settings.json` — это Claude Code, а у opencode есть плагины, и
`plugin/stand-guard.js` подхватывается автоматически из каталога `plugin/` его копии бандла. Он вешает тот же
гард на `tool.execute.before` (инструменты `write`, `edit`, `bash` → `pre-write`, `pre-bash`) и на
`tool.execute.after` (→ `post-write`, `post-run`). Хост ждёт этот хук ДО запуска инструмента, поэтому
исключение из него — это отказ в записи, а не жалоба после неё: запись с `Thread.sleep` отвергается
под opencode так же, как под Claude Code.

**Чего под opencode по-прежнему нет, и это не смягчение формулировки.** Во-первых, **гейта на
завершении сессии**: у Claude Code хук `Stop` может не дать закончить, потому что имеет право выйти с
кодом 2, а `event` у opencode возвращает void — гейт, который не может отказать, это отчёт. Он там не
ослаблен, он не заведён. Во-вторых, **субагентов**: вторая копия их не объявляет, поэтому стадии 2, 4,
8 и 11 выполняются в основном контексте, и гейт `safety-review` под opencode доказывает меньше. Эти два
пропуска — **разного рода**, и путать их не следует: гейт на завершении сессии невозможен, пока
`event` возвращает void, а субагенты просто не портированы — opencode их поддерживает
(`mode: subagent` во фронтматтере плюс секция `agent` в `opencode.json`), и работа тут в переводе
формата, а не в исследовании. В-третьих, `deny`/`ask` из `opencode.json` держатся автоматически и
дальше — включая запрет на правку собственных хуков, правил, скиллов и команд. Паритетом целиком это
называть нельзя, и половина, которой нет, названа здесь по именам — с причиной у каждой, потому что
«невозможно» и «не сделано» ведут к разным решениям.

**Про UI-ветку отдельно.** С инициатив `UITG-S020`/`S021`/`F006` в `detectors.json` этой версии кита
**26 находок, из них восемь UI-специфичных**: `UI_LOCATOR_OUTSIDE_PAGES` (U2), `UI_LOGIN_WITHOUT_ROLE`
(U5), `XPATH_LOCATOR` (U7), `UI_OPEN_OR_ASSERT_TEMPLATE` (U9), `EXPECT_EVENTUALLY_WITHOUT_WITHIN`
(U17), `UI_REPORT_STAND_ADDRESS` (U3-половина над `Ui*Report.md`), `UI_DISCOVERY_PARITY` (U1 —
локатор, которого нет в отчёте разведки, сверяется с ним по `--discovery
UiDiscoveryReport.md`; заявленный и отсутствующий отчёт — сам по себе BLOCK) и
`UI_GENERATION_REPORT_INCOMPLETE` (U16 — восемь секций отчёта генерации считаются по НОМЕРУ, а
`original.sha256` обязан существовать на диске: без снимка KPI-4 не занижен, а ненаблюдаем);
`THREAD_SLEEP` (U6)
расширен драйверными ожиданиями (`page.waitForSelector/Timeout/LoadState`, `.waitFor`). Общие находки
над Java продолжают работать и там — адрес, секрет, `Thread.sleep`, ПД ловятся в тесте и в Page
Object'е так же, как в любом другом файле; **U20 ловится ими же** — `SHARED_MUTABLE_TEST_STATE`
одинаково читает статическое мутабельное поле в тестовом классе и в Page Object'е. Но семантическая
половина «помечено ли ПД», `${…}` в
ожидаемом значении, содержание секций отчёта (детектор проверяет их наличие, а не наполнение), а
также U4(часть), U8, U10, U11a/b, U12, U14, U15, U18, U19 — не
ловит ничто, кроме глаз стадии 7; перечень гейтов и фактическое покрытие — в `ui-safety-checklist.md`
(раздел Machine coverage). Отчёт обязан говорить об этом прямо: чистый хук в UI-ветке — это не чистое
ревью, и выдавать одно за другое — та самая отчётность, которую запрещает последний раздел.

Что по-прежнему держится на добросовестности: порядок стадий 1-7, выбор трека, скупость вопросов и
честность отчёта. Про ревью хук знает ровно одно — что после записи артефакта хост сообщил о
завершении какого-то субагента. Что делегирование было, доказуемо в той мере, в какой запись об этом
делает хост, а не прогон: три слоя — deny-правило на подкоманду, разбор команды на `pre-bash` и
проверка самого события в `subagent-stop` — закрывают удобный путь, но не путь через специально
подделанный stdin в обход Bash. Что делегировали именно ревью, что субагент прочитал артефакт
целиком и что его суждение передали без правок — не доказуемо вовсе.

## Reporting honestly

State what actually happened. A stage that was skipped, a gate that was not run, a test that was
never executed because no stand is configured — each is reported as such. "READY" claimed over an
unrun gate is worse than "NOT-READY": it spends the reviewer's trust on an unverified artifact.
