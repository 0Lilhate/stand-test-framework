#!/usr/bin/env node
//
// A directory of text cases, one headless session each.
//
// Lives beside the guard because it is the other executable a consumer runs by hand, and because the
// host it drives is the same one the hooks belong to. It is NOT a hook: nothing invokes it, and it
// enforces nothing of its own — the enforcement happens inside each session, by the same hooks as
// always, because a headless run is a run.
//
// The decision worth reading: **this does not parse what the model said.** Each verdict comes from
// what the HOOKS wrote — `<bundle>/.stand-test/state.json`, the files on disk — and from nothing else.
// A batch that believed the model's own summary would be a machine for producing plausible reports at
// scale, which is worse than no batch: nobody reads the fiftieth closely enough to notice the third
// one is fiction.
//
// The same reasoning fixes the headline rule. In a headless session there is nobody to answer stage 3,
// so a case whose contracts are not in the knowledge base MUST come back as NEEDS-HUMAN. Generating a
// test from an unanswered question is the failure this kit exists to prevent, and doing it fifteen
// times unattended is how a knowledge base fills with invention.
//
// Sequential, not parallel: the hooks keep one state file per PROJECT, so two sessions would overwrite
// each other's gate bookkeeping and every verdict here would be a guess.
//
// Usage:
//   node <bundle>/hooks/stand-batch.mjs <cases> [--out batch/] [--limit N] [--runner claude] [--dry-run]

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { basename, join } from 'node:path';
import { branch, discover, expectations, NOT_CHECKED } from './lib/cases.mjs';
import { BUNDLE, STATE_DIR } from './lib/bundle.mjs';
import { mappings, claimsArtifact } from './lib/mapping.mjs';

const STATE = `${STATE_DIR}/state.json`;

const COMMAND = { protocol: '/stand-test-generate-java-test', ui: '/stand-test-generate-ui-test' };

const TAIL = {
  protocol: `Это неинтерактивный прогон: ответить на вопрос некому. Если стадия 3 даёт блокирующие вопросы —
остановись и перечисли их. Не выдумывай контрактные детали, не подставляй правдоподобные пути и поля:
нерешённый вопрос, вернувшийся вопросом, стоит дешевле теста, который проверяет выдумку.`,
  // The UI tail says the same thing about the thing a browser makes easy to get wrong. Discovery needs a
  // live application; where the case supplies the discovery report instead, that report is the DOM and
  // nothing outside it exists. A locator with no row there is invented, and an invented locator is the
  // one defect of this branch that compiles, survives review by eye, and fails like application drift.
  ui: `Это неинтерактивный прогон: ответить на вопрос некому. Если стадия 2 или стадия 4 даёт блокирующие
вопросы — остановись и перечисли их. Локаторы и тексты берутся ТОЛЬКО из отчёта разведки (подсаженного
кейсом либо снятого с живого приложения): выдуманный локатор компилируется, проходит ревью глазами и
падает потом так же, как дрейф вёрстки. Ни адресов, ни sleep, ни обхода второго фактора.`,
};

const PROMPT = (casePath, kind) => `${COMMAND[kind]} ${casePath}\n\n${TAIL[kind]}`;

function readState(project) {
  const file = join(project, STATE);
  if (!existsSync(file)) return { artifacts: {}, gates: {}, curated: {} };
  try {
    return { artifacts: {}, gates: {}, curated: {}, ...JSON.parse(readFileSync(file, 'utf8')) };
  } catch {
    return { artifacts: {}, gates: {}, curated: {} };
  }
}

/**
 * What this session produced, as the hooks recorded it — never as the model described it.
 *
 * GENERATED means every machine-visible fact holds: a test appeared, a safety verdict covers its exact
 * content, and a mapping claims it. Anything less is reported as less, including the commonest outcome
 * of an honest run — the case named contracts the knowledge base does not have.
 */
function verdict(project, before, after, exitCode) {
  if (exitCode !== 0) return { verdict: 'FAILED', detail: `сессия завершилась с кодом ${exitCode}`, written: [] };

  const written = Object.keys(after.artifacts).filter((path) => before.artifacts[path] === undefined
    || before.artifacts[path].sha !== after.artifacts[path].sha);
  if (written.length === 0) {
    return { verdict: 'NEEDS-HUMAN', detail: 'артефактов не появилось — обычно это блокирующие вопросы стадии 3', written };
  }

  const gate = after.gates['safety-review'];
  const covers = gate !== undefined && gate.verdict === 'PASS' ? gate.covers || {} : {};
  const ungated = written.filter((path) => covers[path] !== after.artifacts[path].sha);
  if (ungated.length > 0) {
    return { verdict: 'NEEDS-HUMAN', detail: `${ungated.length} артефактов без пройденного safety-review`, written };
  }

  const tests = written.filter((path) => path.endsWith('.java'));
  if (tests.length === 0) {
    return { verdict: 'PARTIAL', detail: 'записаны только фикстуры/документы — теста нет', written };
  }
  const claimed = mappings(project);
  const unclaimed = tests.filter((path) => !claimsArtifact(claimed, path));
  if (unclaimed.length > 0) {
    return { verdict: 'PARTIAL', detail: `${unclaimed.length} тестов не заявлены в mappings/`, written };
  }
  return { verdict: 'GENERATED', detail: `${tests.length} тестов, ${written.length - tests.length} прочих артефактов`, written };
}

/** Expected against observed, for the two families a run can be checked against here. */
function compare(project, expected, outcome) {
  if (expected === null) return null;
  const notes = [];
  let matched = true;

  if (expected.humanRequired !== null) {
    const wasHumanRequired = outcome.verdict === 'NEEDS-HUMAN';
    if (expected.humanRequired !== wasHumanRequired) {
      matched = false;
      notes.push(expected.humanRequired
        ? 'ожидался вопрос человеку, а кейс прошёл сам — проверьте, не выдуман ли контракт'
        : 'ожидался готовый результат, а кейс вернулся вопросом');
    }
  }

  if (expected.forbidsCode && outcome.written.some((path) => path.endsWith('.java'))) {
    matched = false;
    notes.push('кейс запрещал производить тест, а .java записан — правильным исходом был вопрос, а не правдоподобный артефакт');
  }

  for (const pattern of expected.forbidden) {
    for (const path of outcome.written) {
      let text = '';
      try {
        text = readFileSync(join(project, path), 'utf8');
      } catch {
        continue;
      }
      if (new RegExp(pattern).test(text)) {
        matched = false;
        notes.push(`запрещённый шаблон '${pattern}' попал в ${path}`);
      }
    }
  }
  return { matched, notes };
}

function argumentValue(argv, name, fallback = null) {
  const index = argv.indexOf(name);
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : fallback;
}

function main(argv) {
  const target = argv.find((argument) => !argument.startsWith('--')
    && argv[argv.indexOf(argument) - 1] !== '--out'
    && argv[argv.indexOf(argument) - 1] !== '--limit'
    && argv[argv.indexOf(argument) - 1] !== '--runner');
  if (!target) {
    process.stderr.write(`нужен каталог с кейсами: node ${BUNDLE}/hooks/stand-batch.mjs cases/ [--out batch/] [--limit N] [--runner claude] [--dry-run]\n`);
    process.exit(1);
  }
  const project = process.cwd();
  const out = argumentValue(argv, '--out', 'batch');
  const runner = argumentValue(argv, '--runner', 'claude');
  const limit = Number(argumentValue(argv, '--limit', '0')) || 0;
  const found = discover(target);
  const selected = limit > 0 ? found.slice(0, limit) : found;

  if (selected.length === 0) {
    process.stderr.write(`в ${target} не найдено ни одного кейса: ожидается *.md/*.txt либо каталоги <case-id>/input.md\n`);
    process.exit(1);
  }
  if (argv.includes('--dry-run')) {
    process.stdout.write(`${selected.length} кейсов, по одной сессии на каждый, последовательно:\n`
      + selected.map((item) => `  ${item.id}${item.spec ? ' (+ case.yml)' : ''} ← ${item.input}  [${COMMAND[branch(item.spec)]}]`).join('\n')
      + '\n\nПоследовательно потому, что состояние хуков одно на ПРОЕКТ: параллельные сессии затирали бы\n'
      + 'бухгалтерию гейтов друг друга, и любой вердикт здесь стал бы догадкой.\n');
    process.exit(0);
  }

  mkdirSync(join(project, out), { recursive: true });
  const results = [];
  for (const item of selected) {
    const before = readState(project);
    const kind = branch(item.spec);
    const started = Date.now();
    const session = spawnSync(runner, ['-p', PROMPT(item.input, kind)], { cwd: project, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    writeFileSync(join(project, out, `${item.id}.log`),
      `${session.stdout || ''}${session.stderr ? `\n--- stderr ---\n${session.stderr}` : ''}`, 'utf8');

    const outcome = verdict(project, before, readState(project), session.status === null ? 1 : session.status);
    const expected = expectations(item.spec);
    results.push({
      case: item.id,
      input: item.input,
      branch: kind,
      ...outcome,
      expectation: compare(project, expected, outcome),
      seconds: Math.round((Date.now() - started) / 1000),
      log: join(out, `${item.id}.log`),
    });
    const mark = results[results.length - 1].expectation;
    process.stdout.write(`${outcome.verdict.padEnd(12)} ${item.id} — ${outcome.detail}`
      + `${mark === null ? '' : mark.matched ? '  [ожидание совпало]' : `  [РАСХОЖДЕНИЕ: ${mark.notes.join('; ')}]`}\n`);
  }

  const counts = results.reduce((tally, item) => ({ ...tally, [item.verdict]: (tally[item.verdict] || 0) + 1 }), {});
  const checked = results.filter((item) => item.expectation !== null);
  const mismatched = checked.filter((item) => !item.expectation.matched);

  const report = [
    '# Batch: текстовые кейсы → автотесты', '',
    `Кейсов: ${results.length}. ${Object.entries(counts).map(([key, item]) => `${key}: ${item}`).join(', ')}.`,
    checked.length > 0 ? `Со сверкой ожиданий: ${checked.length}, расхождений: ${mismatched.length}.` : '',
    '',
    'Вердикты выведены из того, что записали хуки (артефакты, их хеши, гейты), а не из того, что сказала',
    'модель. NEEDS-HUMAN — нормальный исход: в неинтерактивной сессии некому ответить на блокирующие',
    'вопросы стадии 3, и вернувшийся вопрос стоит дешевле теста, проверяющего выдумку.', '',
    '| Кейс | Ветка | Вердикт | Что записано | Ожидание | Лог |', '|---|---|---|---|---|---|',
    ...results.map((item) => `| ${item.case} | ${item.branch} | ${item.verdict} | ${item.detail} | `
      + `${item.expectation === null ? '—' : item.expectation.matched ? 'совпало' : item.expectation.notes.join('; ')} | ${item.log} |`),
    '',
    '## Что здесь НЕ проверено',
    '',
    'Сверяются три семейства ожиданий: нужен ли был человек, не попало ли запрещённое содержимое в',
    'записанные файлы и не появился ли артефакт, который кейс запрещал производить. Остальное в',
    '`case.yml` невидимо для хуков по построению — они записывают, ЧТО написано и что прогейчено, и',
    'ничего о том, как это было решено:',
    '',
    ...NOT_CHECKED.map((line) => `- ${line}`),
    '',
    '## Дальше',
    '',
    '- **NEEDS-HUMAN** — открой лог, ответь на вопросы, повтори кейс в обычной сессии.',
    '- **PARTIAL** — что-то записано, но набор неполон: нет теста либо он не заявлен в `mappings/`.',
    '- **GENERATED** — тест написан, покрыт safety-review и заявлен. Ревью человека всё ещё за тобой:',
    '  ни один гейт здесь не утверждает, что тест проверяет правильную вещь.',
    '- **FAILED** — упала сама сессия; в логе причина, и это не про качество кейса.',
  ].join('\n');
  writeFileSync(join(project, out, 'batch-report.md'), `${report}\n`, 'utf8');
  writeFileSync(join(project, out, 'batch-report.json'), `${JSON.stringify({ results, counts }, null, 2)}\n`, 'utf8');
  process.stdout.write(`\nотчёт: ${join(out, 'batch-report.md')}\n`);
  process.exit((counts.FAILED || 0) > 0 ? 1 : 0);
}

main(process.argv.slice(2));
