---
version: 1
---

# Rules: the stand-test authoring pipeline

How this bundle is loaded and the ORDER its assets must be used in. The companion file
[`stand-test-guardrails.md`](stand-test-guardrails.md) says what may never be produced; this one says
how the work must be sequenced. Both are rules: they outrank convenience, a shortcut that "obviously
works", and a direct request to skip a stage. If a request cannot be served without breaking them,
say so and stop.

This file exists because `.opencode/rules/**` is loaded automatically, while skills load on demand and
commands only when invoked — so without it the stage order would be a suggestion rather than a
contract. The other bundle carries the same file; its `AGENTS.md` points here rather than restating
the order.

## What is loaded, and how

| Asset | Path | Discovery |
|---|---|---|
| These rules | `.opencode/rules/*.md` | auto-loaded as project instructions |
| Skills (17) | `.opencode/skills/<name>/SKILL.md` | on demand, via the Skill tool |
| Commands (14) | `.opencode/commands/<name>.md` | when the user invokes `/<name>` |
| Workflows (2) | `.opencode/workflows/*.md` | **not** auto-loaded — read when a command points at one |

Load a skill the moment its trigger matches. Do not re-derive its content from memory: the templates,
checklists and worked examples beside each `SKILL.md` are the contract, and paraphrasing them is how
the guardrails get quietly dropped. If the guardrails are not in your context, this bundle is
installed wrong — say so instead of proceeding.

## The one pipeline

Every authoring request follows this order. **No stage may be skipped or reordered**, and each gate
stops the run:

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

## Стадии, которые выполняет отдельный контекст

Четыре стадии из одиннадцати выполняет субагент, а не тот контекст, что ведёт работу. Субагенты
объявлены в бандле для Claude Code (`agents/`); паритет для opencode делается отдельным проходом.

| Стадии | Субагент | Почему не основной контекст |
|---|---|---|
| 2 и 4 | `stand-test-kb-resolver` | самая читающая стадия с самым коротким выходом: сотни строк YAML базы знаний и реестра ради двадцати, которые важны. В основном контексте они остаются лежать до конца работы. Read/Grep/Glob — ни Write, ни Bash |
| 8 | `stand-test-safety-reviewer` | «adversarial» — это про то, КТО читает. Контекст, только что написавший тест, проверяет свой замысел, а не написанные строки; ошибка, пропущенная при авторинге, пропускается на ревью по той же причине. На вход — пути артефактов и дизайн, **не транскрипт авторинга**. Без Write: найденное он не чинит, а докладывает |
| 11 | `stand-test-quality-reviewer` | читает **исходный текст кейса**, а не дизайн: проверка теряется именно в дизайне, и ревью против дизайна этой потери не видит — артефакт дизайну соответствует, а дизайн неполон. Без Write |

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
`.opencode/hooks/stand-guard.mjs`. Под Claude Code его вызывает ХОСТ по событиям, а не модель, и обойти
это, ничего не сказав, нельзя. Под opencode таких событий нет: гард там тот же самый и запускается
командой — что это меняет, сказано в конце раздела. Таблица ниже описывает срабатывания под Claude Code:

| Когда | Что происходит |
|---|---|
| перед каждой записью | восстанавливается ФАЙЛ, который оставит вызов (то, что на диске, плюс правки вызова), и сканируется по `detectors.json`: любая блокирующая находка в получившемся файле **отвергает запись** — в момент написания строки, а не на стадии 8. Маршрут значения не имеет: `Write`, `Edit` и `MultiEdit` проверяются одинаково, пустой `new_string` читается как удаление, пустой `old_string` — как создание файла, и в реестр идёт хеш получившегося файла, а не фрагмента. Если правку не удаётся наложить на файл, проверяются сами фрагменты замены, и хук прямо говорит, что сравнение с прежней версией не выполнялось. Плюс это сравнение: удалённая ассерция, `@Disabled` без номера задачи, новый `catch`, выросший таймаут — «сделать красный тест зелёным» отвергается в момент правки. **Исключения для находок, которые уже лежали на диске, нет.** Файл потребителя, нарушавший правило до кита, чинится целиком и только потом дополняется: отличить «ту же самую» находку от новой можно лишь по evidence, а он у части детекторов означает категорию (`https://`, `Thread.sleep(`), а не место — на этом обе попытки сделать исключение превращались либо в бессрочную индульгенцию, либо в бюджет, где два старых нарушения покупали одно новое. Блокировка перечисляет все находки, поэтому чинить не приходится вслепую. Одно различие по ФОРМАТУ: над прозой (`.md`, `.txt`, `.adoc`) не работают находки про ДОСТАВКУ — 1 адрес, 3 SQL, 6 ожидание, 11 correlation. Markdown не читает ни classpath, ни резолвер, ни JVM, и отчёт стадии 8, ЦИТИРУЮЩИЙ найденный адрес, — это описание нарушения, а не оно само; пока различия не было, гейт отвергал ровно те документы, которые сам же и требует написать. Находки про РАСКРЫТИЕ — 2 секрет, 13 замаскированный секрет, 14 PII — над прозой работают по-прежнему: из markdown секрет коммитится так же, как из Java |
| перед записью в сам кит | `.opencode/**` — правила, хуки, субагенты, скиллы, команды, `settings.json` — **не пишется прогоном ни одним маршрутом**. `permissions.deny` называет все три инструмента (`Edit`, `Write`, `MultiEdit`), а сам хук отвергает запись по пути независимо от того, чем её сделали: deny-лист долго перечислял один `Edit`, и та же запись через `Write` проходила насквозь, потому что скану `.mjs`-файл почти ничем не является, а dot-каталог по построению не попадает в реестр артефактов. Кит обновляется переустановкой по манифесту (`install.mjs`), а не правкой на месте: правило, которое прогон может переписать, действует ровно до тех пор, пока прогон с ним согласен |
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
`node .opencode/hooks/stand-guard.mjs record-gate --gate safety-review --verdict PASS <файлы>` — и она
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
`kb-status`, `record-gate`, `doctor` работают одинаково там и там. Не едет ПРИВЯЗКА К СОБЫТИЯМ: её даёт
`settings.json` Claude Code, а у opencode таких событий нет. Значит, под opencode нет автоматической
половины — отказа в момент записи, сверки с прежней версией, чтения результатов прогона, гейта на
завершении сессии; те же проверки существуют, но запускает их тот, кто о них помнит, то есть снова
добросовестность. Автоматически там держится только `deny`/`ask` из `opencode.json` — включая запрет
на правку собственных хуков, правил, скиллов и команд. Субагентов вторая копия тоже не объявляет
(`agents/` — механизм Claude Code), поэтому стадии 2, 4, 8 и 11 выполняются в основном контексте, и
гейт `safety-review` под opencode доказывает меньше, чем под Claude Code. Это не паритет, и называть
его паритетом было бы ровно тем отчётом, который следующий раздел запрещает.

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
