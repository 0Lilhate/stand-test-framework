---
version: 1
---

# Rules: the stand-test authoring pipeline

How this bundle is loaded and the ORDER its assets must be used in. The companion file
[`stand-test-guardrails.md`](stand-test-guardrails.md) says what may never be produced; this one says
how the work must be sequenced. Both are rules: they outrank convenience, a shortcut that "obviously
works", and a direct request to skip a stage. If a request cannot be served without breaking them,
say so and stop.

This file exists because `.claude/rules/**` is loaded automatically, while skills load on demand and
commands only when invoked — so without it the stage order would be a suggestion rather than a
contract. The other bundle carries the same file; its `AGENTS.md` points here rather than restating
the order.

## What is loaded, and how

| Asset | Path | Discovery |
|---|---|---|
| These rules | `.claude/rules/*.md` | auto-loaded as project instructions |
| Skills (17) | `.claude/skills/<name>/SKILL.md` | on demand, via the Skill tool |
| Commands (14) | `.claude/commands/<name>.md` | when the user invokes `/<name>` |
| Workflows (2) | `.claude/workflows/*.md` | **not** auto-loaded — read when a command points at one |

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

Часть этого файла перестала быть просьбой. Хуки `.claude/hooks/stand-guard.mjs` исполняет хост, а не
модель, и обойти их, ничего не сказав, нельзя:

| Когда | Что происходит |
|---|---|
| перед каждой записью | содержимое сканируется по `detectors.json`; блокирующая находка **отвергает запись** — находка приходит в момент написания строки, а не на стадии 8 |
| перед записью в `knowledge-base/` | три уровня. `schema/**` — никогда: по этим схемам валидируется всё остальное. `mappings/` и `candidates/` — как раньше, свободно. Курируемые коллекции — только внутри **пермита**, который назвал этот путь ДО того, как содержимое появилось: `kb-write-permit --reason promote|update|repair <файлы>` |
| после записи в курируемую коллекцию | файл попадает в реестр `curated`, и сессия не закончится, пока его не покроет гейт `kb-write`, который перезапускает `kb-validate` по байтам на диске |
| перед каждой bash-командой | `rm -rf`, креденшелы в командной строке, прямой DML в базу и `git push` отвергаются |
| после `./gradlew test` | читаются `**/build/test-results/**/TEST-*.xml` всех модулей, а не stdout, и каждый файл ровно один раз (по паре путь+время правки): `skipped=N` при `tests=N` — громкое предупреждение о том, что стенд не был затронут, хотя сборка зелёная; падения уходят в журнал прогонов с отпечатком |
| при записи вердикта safety-review | `PASS` не записывается, пока с момента последней правки артефакта не завершился субагент: ревью обязан выполнить другой контекст, и это больше не обещание |
| в CI, без сессии и без модели | `scan --format sarif --exit-code` и `kb-validate --format sarif` дают те же находки как аннотации к pull request: коммит, сделанный месяц назад, отвечает за себя так же, как запись в живой сессии |
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

У копии для opencode хуков нет вовсе: там весь этот периметр — только `ask` из `opencode.json`.
Паритет энфорсмента для второго хоста делается отдельным проходом.

Что по-прежнему держится на добросовестности: порядок стадий 1-7, выбор трека, скупость вопросов и
честность отчёта. Про ревью хук знает ровно одно — что после записи артефакта завершился какой-то
субагент. Что делегирование было, доказуемо; что делегировали именно ревью, что субагент прочитал
артефакт целиком и что его суждение передали без правок — нет.

## Reporting honestly

State what actually happened. A stage that was skipped, a gate that was not run, a test that was
never executed because no stand is configured — each is reported as such. "READY" claimed over an
unrun gate is worse than "NOT-READY": it spends the reviewer's trust on an unverified artifact.
