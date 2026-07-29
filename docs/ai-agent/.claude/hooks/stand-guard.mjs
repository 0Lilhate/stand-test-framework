#!/usr/bin/env node
//
// The kit's enforcement layer: one entry point, several subcommands.
//
// Everything the pipeline used to ask the model to do faithfully — scan before writing, notice a
// skipped test run, refuse to finish with an unreviewed artifact — happens here instead, in code the
// host runs whether or not the model remembers to. `rules/stand-test-pipeline.md` says the stage
// order is a contract; this file is what makes that true of the parts a machine can decide.
//
// One file rather than six: one place to keep in parity between the bundle copies, one stdin
// protocol, and readable lines in settings.json.
//
// Exit codes follow the Claude Code hook contract: 0 proceeds, 2 blocks and shows stderr to the
// model, anything else is an error that does not block. Nothing here ever exits non-zero because of
// its own bug — a broken guard must not become a broken session.

import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scanArtifact, scanDiff, blocking, render, gates, kindOf } from './lib/scan.mjs';
import { readResults, skipWarning } from './lib/junit.mjs';
import { fingerprint, normalise } from './lib/fingerprint.mjs';
import { kbStatus } from './lib/kb.mjs';
import { validateKnowledgeBase, checkAliases } from './lib/kb-checks.mjs';
import { classify, validateFileSet, reviewRecordFor, describe } from './lib/permit.mjs';
import { diagnose, render as renderReport } from './lib/doctor.mjs';
import { toSarif } from './lib/sarif.mjs';
import { mappings, claimsArtifact, needsMapping, template } from './lib/mapping.mjs';
import * as state from './lib/state.mjs';

const SAFETY_GATE = 'safety-review';

/** The curated knowledge base has its own gate: its reviewer is a human, and its checker is kb-validate. */
const KB_GATE = 'kb-write';

/** "Since forever" — used when no named file is an artifact this session recorded. */
const EPOCH = '1970-01-01T00:00:00.000Z';

function readStdin() {
  try {
    return JSON.parse(readFileSync(0, 'utf8') || '{}');
  } catch {
    return {};
  }
}

function block(message) {
  process.stderr.write(`${message}\n`);
  process.exit(2);
}

function proceed(message) {
  if (message) process.stdout.write(`${message}\n`);
  process.exit(0);
}

/** A path as the state file spells it: relative to the workspace, whatever form it arrived in. */
function relativise(raw, cwd) {
  const prefix = `${cwd}/`;
  return raw.startsWith(prefix) ? raw.slice(prefix.length) : raw;
}

/** The workspace-relative path of whatever a Write/Edit tool call is about to touch. */
function targetPath(input, cwd) {
  const raw = input.file_path || input.path || '';
  return raw ? relativise(raw, cwd) : '';
}

/** The content a Write/Edit tool call would leave behind, as far as the hook can know it. */
function intendedContent(toolName, input) {
  if (toolName === 'Write') return input.content || '';
  if (toolName === 'Edit') return input.new_string || '';
  if (toolName === 'MultiEdit') return (input.edits || []).map((edit) => edit.new_string || '').join('\n');
  return '';
}

// The knowledge base has its own human gate, and a dot-directory is the machinery's own bookkeeping.
const NOT_AN_ARTIFACT = [/^knowledge-base\//, /(^|\/)\./];

/**
 * Whether a path is something the safety gate must cover before the session may end.
 *
 * Everything written is SCANNED — that does not change, and a secret in a markdown report is still
 * refused at the moment of writing. What narrows here is the bookkeeping: an artifact is what the
 * stand can execute (a test, a scenario or fixture document, the build file that decides what the
 * test runs with), because that is what a safety review is a review OF.
 *
 * Registering every written file instead meant the analysis, the design and the readiness report
 * each became an artifact demanding a verdict, and the first full pipeline ended in a Stop gate
 * naming five files, none of them a test. The plan's own first risk is that a gate which is always
 * red gets switched off — and it takes the accurate findings with it.
 */
function isReviewableArtifact(path) {
  if (NOT_AN_ARTIFACT.some((pattern) => pattern.test(path))) return false;
  return ['java', 'document', 'build'].includes(kindOf(path));
}

function policyOf(cwd) {
  // The allowlist is the run's, not the kit's. Absent, the environment detector still refuses
  // production-like names — the check that does not depend on anyone having configured anything.
  const file = join(cwd, '.claude', '.stand-test', 'policy.json');
  if (!existsSync(file)) return {};
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return {};
  }
}

// ---------------------------------------------------------------------------------------------

function commandScan(argv) {
  // `--as <path>` scans a file under the name it will really have. The golden corpus is stored under
  // names that describe what each fixture violates, and a build file called after its violation is
  // not a build file to any detector — the artifact kind comes from the path, deliberately.
  const alias = argumentValue(argv, '--as');
  // The artifact as the base branch has it: `git show origin/main:<path> > /tmp/prev`. With it the
  // eighteenth finding can run — what a change stopped checking is not visible in one version.
  const against = argumentValue(argv, '--against');
  const files = positional(argv);
  const policy = policyOf(process.cwd());
  let findings = [];
  const contents = {};
  for (const file of files) {
    if (!existsSync(file)) {
      process.stderr.write(`нет файла: ${file}\n`);
      continue;
    }
    const content = readFileSync(file, 'utf8');
    contents[alias || file] = content;
    findings = findings.concat(scanArtifact(content, alias || file, policy));
    if (against !== null) {
      const previous = existsSync(against) ? readFileSync(against, 'utf8') : '';
      findings = findings.concat(scanDiff(previous, content, alias || file));
    }
  }
  const { ran, notRun } = gates({ previousVersion: against !== null && files.length > 0 });
  if (argumentValue(argv, '--format') === 'sarif') {
    // The same gate without a session: CI reads this whether or not anybody ran an agent.
    process.stdout.write(`${JSON.stringify(toSarif(findings, contents, notRun), null, 2)}\n`);
  } else if (argv.includes('--json')) {
    process.stdout.write(`${JSON.stringify({ findings, gatesRun: ran, gatesNotRun: notRun }, null, 2)}\n`);
  } else {
    findings.forEach((item) => process.stdout.write(`${render(item)}\n`));
    process.stdout.write(`\nпроверено находок: ${ran.length}, не проверено: ${notRun.length} (${notRun.join(', ') || '—'})\n`);
    process.stdout.write(`найдено: ${findings.length}, из них блокирующих: ${blocking(findings).length}\n`);
  }
  process.exit(argv.includes('--exit-code') && blocking(findings).length > 0 ? 1 : 0);
}

function commandPreWrite(payload) {
  const cwd = payload.cwd || process.cwd();
  const path = targetPath(payload.tool_input || {}, cwd);
  if (!path) proceed();

  // The knowledge base, by tier. `schema/` never; staging as always; a curated file only inside a
  // permit that named it. The permit is scope and intent — the human decision is the host's own
  // prompt on this write, and what landed is judged afterwards by the `kb-write` gate.
  const tier = classify(path);
  if (tier === 'schema') {
    block(`✖ контракт схем не пишет прогон, который он ограничивает: ${path}\n`
      + '  knowledge-base/schema/** не покрывается никаким пермитом: по этим схемам валидируется всё остальное,\n'
      + '  и правка схемы — это изменение SDK, которое делает человек.');
  }
  if (tier === 'curated') {
    const permit = state.activePermit(cwd);
    if (permit === null) {
      const expired = state.readState(cwd).permit;
      block(expired
        ? `✖ пермит истёк: выдан ${expired.issuedAt}, действовал до ${expired.expiresAt}\n  ${path}\n\n`
          + '  Выпустите новый — срок стоит для того, чтобы забытый пермит не превращался в постоянную лицензию.'
        : `✖ запись в курируемую базу знаний без пермита: ${path}\n\n`
          + '  Объявите файлы, которые собираетесь записать, — до того, как содержимое существует:\n'
          + `  → node .claude/hooks/stand-guard.mjs kb-write-permit --reason promote --document <id> ${path}\n`
          + '     (или --reason update --source <спека>, или --reason repair для находок kb-validate)\n\n'
          + '  Пермит — это область и намерение, а не разрешение: подтверждает запись человек, в момент записи.');
    }
    if (!permit.files.includes(path)) {
      block(`✖ пермит не называет этот файл: ${path}\n\n${describe(permit)}\n\n`
        + '  Пермит покрывает ровно перечисленное. Выпустите новый, назвав недостающий путь — '
        + 'это один шаг, а не тупик.');
    }
    state.consumePermit(path, cwd);
  }

  const content = intendedContent(payload.tool_name, payload.tool_input || {});
  if (!content) proceed();

  // Both versions are here: what stands on disk and what is about to replace it. So the eighteenth
  // finding runs at the one moment it is cheapest to act on — a red test being quietly made green is
  // refused while it is happening, rather than noticed in a review of the commit that did it.
  const previous = existsSync(join(cwd, path)) ? readFileSync(join(cwd, path), 'utf8') : '';
  const before = payload.tool_name === 'Write' ? previous : (payload.tool_input || {}).old_string || '';
  const findings = scanArtifact(content, path, policyOf(cwd)).concat(scanDiff(before, content, path));
  const stoppers = blocking(findings);
  if (stoppers.length > 0) {
    block(`Запись отвергнута: ${stoppers.length} блокирующих находок в ${path}\n\n`
      + `${stoppers.map(render).join('\n\n')}\n\n`
      + 'Это guardrails SDK, а не советы: исправьте содержимое, не обходите проверку.');
  }

  if (isReviewableArtifact(path)) state.recordArtifact(path, content, cwd);
  const notes = findings.filter((item) => item.severity !== 'BLOCK');
  proceed(notes.length > 0 ? `⚠ ${notes.length} находок уровня HIGH в ${path}:\n${notes.map(render).join('\n')}` : '');
}

/**
 * What a curated knowledge-base file holds now that the write has happened.
 *
 * Recorded here rather than in pre-write because here it is exact. Before the write the file on disk
 * still holds the old content, and the tool call carries the whole new file only for `Write` — an
 * `Edit` carries the replacement fragment, whose hash describes nothing anyone can re-check. After
 * the write there is one answer, and the `kb-write` gate is bound to it.
 */
function commandPostWrite(payload) {
  const cwd = payload.cwd || process.cwd();
  const path = targetPath(payload.tool_input || {}, cwd);
  if (!path || classify(path) !== 'curated') proceed();
  if (!existsSync(join(cwd, path))) proceed();

  state.recordCurated(path, readFileSync(join(cwd, path), 'utf8'), cwd);
  proceed(`курируемая база знаний изменена: ${path}\n`
    + '  Сессия не закончится, пока это не покрыто гейтом: node .claude/hooks/stand-guard.mjs kb-validate --exit-code, '
    + 'затем record-gate --gate kb-write --verdict PASS <файлы>');
}

const DANGEROUS_COMMANDS = [
  [/\brm\s+-rf?\b/, 'rm -rf'],
  [/\b(?:curl|wget|http)\b[^\n]*(?:\s-u\s+\S+:\S+|:\/\/[^/\s]+:[^@/\s]+@)/, 'креденшелы в командной строке'],
  [/\b(?:psql|mysql)\b[^\n]*\b(?:insert|update|delete|drop|truncate)\b/i, 'прямой DML в базу'],
  [/\bgit\s+push\b/, 'git push'],
];

function commandPreBash(payload) {
  const command = (payload.tool_input || {}).command || '';
  for (const [pattern, what] of DANGEROUS_COMMANDS) {
    if (pattern.test(command)) {
      block(`✖ команда отвергнута (${what}):\n  ${command}\n\n`
        + 'Периметр задан китом, а не удобством конкретного шага. Если действие действительно нужно — '
        + 'выполните его сами, вне агента.');
    }
  }
  proceed();
}

function commandPostRun(payload) {
  const cwd = payload.cwd || process.cwd();
  const command = (payload.tool_input || {}).command || '';
  if (!/gradlew/.test(command) || !/\b(test|check|build)\b/.test(command)) proceed();

  // Every module's results, and only the ones this command produced. Both halves were missing: the
  // root `build/test-results` does not exist in a multi-module project, so the check that catches a
  // green build over zero executed tests never ran there at all.
  const summary = readResults(cwd, state.consumedResults(cwd));
  if (summary.files === 0) proceed();
  state.rememberResults(summary.consumed, cwd);

  const lines = [];
  const warning = skipWarning(summary);
  if (warning) lines.push(`⚠ ${warning}`);

  for (const failure of summary.failures) {
    const id = fingerprint(failure);
    const entry = {
      ts: new Date().toISOString(),
      testClass: failure.testClass,
      testMethod: failure.testMethod,
      outcome: 'fail',
      exceptionClass: failure.exceptionClass,
      messageRaw: failure.message,
      messageNorm: normalise(failure.message),
      fingerprint: id,
    };
    state.appendJournal(entry, cwd);
    state.appendUnknownSignature({ fingerprint: id, ts: entry.ts, messageNorm: entry.messageNorm }, cwd);
    lines.push(`✖ ${failure.testClass}.${failure.testMethod} — ${failure.exceptionClass}\n  ${failure.message}\n  отпечаток: ${id}`);
  }

  if (summary.failures.length === 0 && !warning) {
    state.appendJournal({ ts: new Date().toISOString(), outcome: 'pass', tests: summary.tests, skipped: summary.skipped }, cwd);
  }
  proceed(lines.join('\n'));
}

function commandRecordGate(argv, payload) {
  const cwd = (payload && payload.cwd) || process.cwd();
  const gate = argumentValue(argv, '--gate') || SAFETY_GATE;
  const claimed = (argumentValue(argv, '--verdict') || 'PASS').toUpperCase();
  const files = positional(argv).map((file) => relativise(file, cwd));

  // A verdict has to name what it passed. Without a list there is nothing to re-scan, and the record
  // used to cover EVERY artifact of the session — one command that certified everything and verified
  // nothing, which is the exact inverse of what this gate exists for.
  if (files.length === 0) {
    block(`✖ гейт ${gate} не записан: не перечислены файлы\n`
      + '  Вердикт покрывает ровно те артефакты, которые названы, и они перепроверяются сканом.\n'
      + `  → node .claude/hooks/stand-guard.mjs record-gate --gate ${gate} --verdict ${claimed} <файл> [<файл>…]`);
  }

  // Skipping an absent path silently would record a PASS about content nobody read — the same hole
  // one level down. A path that does not resolve is a mistake worth hearing about.
  const missing = files.filter((file) => !existsSync(join(cwd, file)));
  if (missing.length > 0) {
    block(`✖ гейт ${gate} не записан: этих файлов нет на диске\n  ${missing.join('\n  ')}\n\n`
      + '  Пути указываются от корня проекта. Пропустить их молча значило бы записать вердикт о непрочитанном содержимом.');
  }

  // The verdict is not taken on trust. A subagent may judge what a scan cannot, but it may not
  // certify away what a scan can decide — so the deterministic half runs again, here.
  const policy = policyOf(cwd);
  let stoppers = [];
  for (const file of files) {
    stoppers = stoppers.concat(blocking(scanArtifact(readFileSync(join(cwd, file), 'utf8'), file, policy)));
  }
  if (claimed === 'PASS' && stoppers.length > 0) {
    state.recordGate(gate, 'BLOCK', files, cwd);
    block(`✖ PASS не записан: повторный скан нашёл ${stoppers.length} блокирующих находок\n\n${stoppers.map(render).join('\n\n')}`);
  }

  // The curated knowledge base answers to its own checker. This is the deterministic half a pre-write
  // rule could never be — before the write there is no file to judge, and on the Edit route not even
  // the whole content. Here there is.
  if (gate === KB_GATE && claimed === 'PASS') {
    const report = validateKnowledgeBase(cwd);
    const kbStoppers = blocking(report.findings).filter((item) => files.some((file) => String(item.file).startsWith(file)));
    if (kbStoppers.length > 0) {
      state.recordGate(gate, 'BLOCK', files, cwd);
      block(`✖ PASS не записан: kb-validate нашёл ${kbStoppers.length} блокирующих находок\n\n`
        + `${kbStoppers.map((item) => `✖ ${item.ruleId}\n  ${item.file}: ${item.message}\n  → ${item.fix}`).join('\n\n')}`);
    }
  }

  // "Ревью выполнил другой контекст" перестаёт быть обещанием: с момента записи артефакта
  // должен был завершиться субагент. Хук не знает, КАКОЙ и что он читал, — но знает, что делегирование
  // вообще было, и этого хватает, чтобы контекст, писавший код, не подписывал сам себя.
  if (gate === SAFETY_GATE && claimed === 'PASS') {
    const known = state.readState(cwd).artifacts;
    const written = files.map((file) => (known[file] || {}).at).filter(Boolean);
    const since = written.sort().pop() || EPOCH;
    if (!state.subagentRanSince(since, cwd)) {
      block(`✖ PASS не записан: с момента записи артефакта ни один субагент не завершился\n`
        + `  ${files.join('\n  ')}\n\n`
        + '  Safety-review выполняет ОТДЕЛЬНЫЙ контекст — субагент stand-test-safety-reviewer, у которого нет Write.\n'
        + '  Тот же контекст, что писал код, не может быть его adversarial-ревьюером: он проверяет свой замысел, а не написанное.\n'
        + '  → запустите субагента, затем повторите эту команду. Правка артефакта после ревью снимает и это покрытие.');
    }
  }

  // Fail fast on the traceability record, where the message can still name the artifact being gated.
  // The Stop hook is the backstop; this is the courtesy of finding out now rather than at the end.
  const caseId = argumentValue(argv, '--case');
  if (caseId !== null && claimed === 'PASS') {
    const known = mappings(cwd);
    if (!known.some((entry) => entry.caseId === caseId)) {
      block(`✖ гейт ${gate} не записан: в knowledge-base/mappings/ нет записи о кейсе '${caseId}'\n\n`
        + `${template(files[0], caseId)}\n\n`
        + '  Запись связи «кейс → тест» — единственное место, где эта связь вообще есть: '
        + 'через полгода по одному файлу теста не восстановить, какой кейс он закрывает и что при этом предполагалось.');
    }
  }

  const covered = Object.keys(state.recordGate(gate, claimed, files, cwd).gates[gate].covers);
  const uncovered = files.filter((file) => !covered.includes(file));
  proceed(`гейт ${gate}: ${claimed} (${files.join(', ')})`
    + (uncovered.length > 0
      ? `\n⚠ эта сессия не записывала ${uncovered.length} из перечисленных файлов, и вердикт их не покрывает: ${uncovered.join(', ')}`
      : ''));
}

function commandStop(payload) {
  const cwd = payload.cwd || process.cwd();
  const staleKb = state.staleCurated(KB_GATE, cwd);
  if (staleKb.length > 0 && !payload.stop_hook_active) {
    block(`✖ сессия не завершена: ${staleKb.length} файлов курируемой базы знаний записаны и не проверены\n  ${staleKb.join('\n  ')}\n\n`
      + `→ node .claude/hooks/stand-guard.mjs kb-validate --exit-code\n`
      + `→ node .claude/hooks/stand-guard.mjs record-gate --gate ${KB_GATE} --verdict PASS ${staleKb.join(' ')}\n`
      + 'База знаний — это конфигурация стенда: запись, которую никто не перечитал, ломает не эту сессию, а следующий сгенерированный тест.');
  }

  const stale = state.staleArtifacts(SAFETY_GATE, cwd);
  if (stale.length === 0) {
    // Reviewed, and still nowhere on the record. The mapping is the only place the join between a case
    // and the test that answers it is written down; without it the test's reason for existing lives in
    // a transcript nobody will have.
    const claimed = mappings(cwd);
    const unclaimed = Object.keys(state.readState(cwd).artifacts).filter((path) => needsMapping(path) && !claimsArtifact(claimed, path));
    if (unclaimed.length > 0 && !payload.stop_hook_active) {
      block(`✖ сессия не завершена: ${unclaimed.length} тестов прошли ревью и не заявлены в knowledge-base/mappings/\n  ${unclaimed.join('\n  ')}\n\n`
        + `${template(unclaimed[0], null)}\n\n`
        + '  mappings/ — единственный канал записи, открытый агенту, и до сих пор он держался на добросовестности.');
    }
    if (unclaimed.length > 0) proceed(`NOT-READY: ${unclaimed.length} тестов без записи в mappings/:\n  ${unclaimed.join('\n  ')}`);
    if (staleKb.length > 0) proceed(`NOT-READY: ${staleKb.length} файлов базы знаний без гейта ${KB_GATE}:\n  ${staleKb.join('\n  ')}`);
    proceed();
  }

  const instruction = `node .claude/hooks/stand-guard.mjs scan ${stale.join(' ')}`;
  if (payload.stop_hook_active) {
    // Second pass: say plainly that the gate did not pass rather than looping. A gate that cannot be
    // satisfied must still be reportable, and an endless block is not a report.
    proceed(`NOT-READY: ${stale.length} артефактов не покрыты пройденным safety-review:\n  ${stale.join('\n  ')}`);
  }
  block(`✖ сессия не завершена: ${stale.length} артефактов без пройденного safety-review\n  ${stale.join('\n  ')}\n\n`
    + `→ ${instruction}\n`
    + '→ затем запишите вердикт: node .claude/hooks/stand-guard.mjs record-gate --gate safety-review --verdict PASS <файлы>\n'
    + 'Правка файла после ревью снимает покрытие автоматически — гейт привязан к содержимому, а не к факту запуска.');
}

const PERMIT_REASONS = ['promote', 'update', 'repair'];

/**
 * Declares which curated files a write is about to touch, and why.
 *
 * `repair` asks for no evidence at all, and that is a decision rather than an oversight: the kit's own
 * `kb-validate` reports defects IN curated files — a duplicate id, a ref carrying a value — and if no
 * reason could open those files, the only fix would be outside the tool. A gate nobody can satisfy is
 * a gate people switch off, and it takes the working checks with it. So the honest statement is that
 * this command is bookkeeping the model can perform for itself; the human decision lives in the
 * host's prompt at the moment of the write.
 */
function commandKbWritePermit(argv) {
  const cwd = process.cwd();
  if (argv.includes('--revoke')) {
    const previous = state.revokePermit(cwd);
    proceed(previous ? `пермит отозван (был на ${previous.files.length} файлов)` : 'активного пермита не было');
  }

  const reason = argumentValue(argv, '--reason') || '';
  if (!PERMIT_REASONS.includes(reason)) {
    block(`✖ --reason обязателен и должен быть одним из: ${PERMIT_REASONS.join(' | ')}\n`
      + '  promote — промоут одобренных кандидатов; update — kb-update из машинного контракта; repair — починка находок kb-validate.');
  }

  const files = positional(argv).map((file) => relativise(file, cwd));
  const scope = validateFileSet(files);
  if (!scope.ok) block(`✖ пермит не выдан: ${scope.why}`);

  const document = argumentValue(argv, '--document');
  const source = argumentValue(argv, '--source');
  if (reason === 'promote') {
    const review = reviewRecordFor(cwd, document);
    if (!review.ok) {
      block(`✖ пермит не выдан: ${review.why}\n\n`
        + '  Это сверка на согласованность, а не авторизация: файл решения пишет тот же агент. '
        + 'Она ловит промоут не того документа, не более того.');
    }
  }
  if (reason === 'update') {
    if (!source) block('✖ пермит не выдан: --reason update требует --source <путь к спеке> либо --source pasted');
    if (source !== 'pasted' && !existsSync(join(cwd, source))) {
      block(`✖ пермит не выдан: источника нет на диске — ${source}\n`
        + '  Если контракт пришёл текстом, так и скажите: --source pasted (это попадёт в вывод и останется в транскрипте).');
    }
  }

  const previous = state.issuePermit({ reason, document: document || null, source: source || null, files }, cwd);
  const permit = state.activePermit(cwd);
  proceed(`${describe(permit)}\n`
    + (previous ? `⚠ заменён неизрасходованный пермит на ${previous.files.length} файлов\n` : '')
    + '  Это бухгалтерия, а не разрешение: на каждой записи хост спросит человека.');
}

function commandSubagentStop(payload) {
  state.recordSubagentStop(payload.cwd || process.cwd());
  proceed();
}

/**
 * What the registry declares and the knowledge base does not yet know.
 *
 * The cold start turns on this list, and it is read off the files rather than recalled: an alias a
 * person curated in the registry may be assumed, one nobody wrote down anywhere may not.
 */
function commandKbStatus(argv) {
  const status = kbStatus(process.cwd());
  if (argv.includes('--json')) {
    proceed(JSON.stringify(status, null, 2));
  }
  const lines = [`реестр окружений: ${status.registry || 'НЕ НАЙДЕН'}`,
    `база знаний: ${status.knowledgeBase.present ? `${status.knowledgeBase.ids} записей` : 'НЕТ'}`];
  for (const [kind, value] of Object.entries(status.kinds)) {
    lines.push(`  ${kind}: в реестре ${value.registry.length}, нет в KB ${value.missing.length}${value.missing.length > 0 ? ` (${value.missing.join(', ')})` : ''}`);
  }
  lines.push(status.missingTotal > 0
    ? `\n${status.missingTotal} алиасов засвидетельствованы реестром, но отсутствуют в KB — это рабочий список /stand-test-bootstrap-kb.\nКонтрактные детали (пути, поля, таблицы, gRPC-методы) так не берутся: их не свидетельствует ни один реестр.`
    : '\nвсе алиасы реестра известны базе знаний');
  proceed(lines.join('\n'));
}

/**
 * The knowledge base, checked at the site that uses it.
 *
 * In this repository a Gradle test validates the base against its twenty schemas. That test does not
 * travel with the bundle, so at a consumer the schemas are documents nobody executes — and a KB
 * drifts silently until a generated test fails against a stand for a reason that looks like anything
 * else. What runs here is the part a script can decide exactly, and it says which part that is.
 */
function commandKbValidate(argv) {
  const result = validateKnowledgeBase(process.cwd());
  const stoppers = blocking(result.findings);
  if (argumentValue(argv, '--format') === 'sarif') {
    // kb-validate's own "not checked" list is prose about the schemas, not rule ids: it has no rule
    // to switch off, and passing it as one would invent a rule that does not exist.
    process.stdout.write(`${JSON.stringify(toSarif(result.findings, {}, []), null, 2)}\n`);
  } else if (argv.includes('--json')) {
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  } else if (result.files === 0) {
    process.stdout.write('база знаний не найдена: нечего проверять\n');
  } else {
    result.findings.forEach((item) => process.stdout.write(`${item.severity === 'BLOCK' ? '✖' : '⚠'} ${item.ruleId}\n  ${item.file}: ${item.message}\n  → ${item.fix}\n`));
    process.stdout.write(`\nпроверено файлов: ${result.files}, находок: ${result.findings.length}, из них блокирующих: ${stoppers.length}\n`);
    process.stdout.write(`НЕ проверено (контракт — схемы в knowledge-base/schema/): ${result.notChecked.join('; ')}\n`);
  }
  process.exit(argv.includes('--exit-code') && stoppers.length > 0 ? 1 : 0);
}

/** The base against the registry, in both directions. */
function commandAliasCheck(argv) {
  const result = checkAliases(process.cwd());
  if (argv.includes('--json')) {
    proceed(JSON.stringify(result, null, 2));
  }
  if (result.registry === null) {
    proceed('реестр окружений не найден: сверять базу знаний не с чем');
  }
  const lines = [`реестр: ${result.registry}`];
  for (const [kind, value] of Object.entries(result.kinds)) {
    lines.push(`  ${kind}: реестр ${value.registry.length}, KB ${value.knowledgeBase.length}`
      + `, нет в реестре ${value.unregistered.length}${value.unregistered.length > 0 ? ` (${value.unregistered.join(', ')})` : ''}`
      + `, нет в KB ${value.missingFromKb.length}${value.missingFromKb.length > 0 ? ` (${value.missingFromKb.join(', ')})` : ''}`);
  }
  lines.push(result.findings.length > 0
    ? `\n${result.findings.length} алиасов базы знаний не объявлены реестром — тест, сгенерированный по такой записи, падает на резолвинге`
    : '\nкаждый алиас базы знаний объявлен реестром');
  proceed(lines.join('\n'));
}

/**
 * Is this installation the kit, and can it run?
 *
 * The one command that answers the questions a consumer cannot otherwise ask: which version arrived,
 * what has been edited since, whether the hooks are wired at all. Every other mechanism that holds the
 * bundle together lives in the SDK repository and stopped existing when the kit was copied.
 */
function commandDoctor(argv) {
  const report = diagnose(dirname(fileURLToPath(import.meta.url)), process.cwd());
  process.stdout.write(argv.includes('--json') ? `${JSON.stringify(report, null, 2)}\n` : `${renderReport(report)}\n`);
  process.exit(argv.includes('--exit-code') && report.problems.length > 0 ? 1 : 0);
}

function commandStatus(payload) {
  const cwd = (payload && payload.cwd) || process.cwd();
  // A new session inherits no permission from an old one: a permit is about what is being done now.
  const dropped = state.revokePermit(cwd);
  const current = state.readState(cwd);
  const artifacts = Object.keys(current.artifacts).length;
  const stale = state.staleArtifacts(SAFETY_GATE, cwd).length;
  const staleKb = state.staleCurated(KB_GATE, cwd).length;
  const kb = existsSync(join(cwd, 'knowledge-base')) ? 'есть' : 'НЕТ';
  const registry = ['stand-test-environments.yml', 'src/test/resources/stand-test-environments.yml']
    .some((path) => existsSync(join(cwd, path))) ? 'есть' : 'НЕТ';
  const { ran, notRun } = gates();
  proceed(`stand-test: KB ${kb}, реестр окружений ${registry}; артефактов за сессию ${artifacts}, `
    + `без пройденного safety-review ${stale}; файлов KB без гейта ${KB_GATE} ${staleKb}; `
    + `детекторов активно ${ran.length}/${ran.length + notRun.length}`
    + (dropped ? `\n⚠ пермит на запись в KB от ${dropped.issuedAt} снят: он принадлежал прошлой сессии` : ''));
}

const VALUE_FLAGS = new Set(['--gate', '--verdict', '--as', '--reason', '--document', '--source', '--format', '--against', '--case']);

function argumentValue(argv, name) {
  const index = argv.indexOf(name);
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : null;
}

/**
 * The bare arguments — flags and the values they consume removed.
 *
 * Filtering by "not a flag, and not equal to what the flags parsed" looked equivalent and was not:
 * `--verdict pass` left the lowercase word behind as a file name, and a file genuinely called after
 * a gate would have vanished from its own verdict.
 */
function positional(argv) {
  const values = [];
  for (let index = 0; index < argv.length; index += 1) {
    if (VALUE_FLAGS.has(argv[index])) {
      index += 1;
      continue;
    }
    if (argv[index].startsWith('--')) continue;
    values.push(argv[index]);
  }
  return values;
}

// ---------------------------------------------------------------------------------------------

const [subcommand, ...argv] = process.argv.slice(2);
try {
  switch (subcommand) {
    case 'scan': commandScan(argv); break;
    case 'pre-write': commandPreWrite(readStdin()); break;
    case 'pre-bash': commandPreBash(readStdin()); break;
    case 'post-run': commandPostRun(readStdin()); break;
    case 'record-gate': commandRecordGate(argv, {}); break;
    case 'stop': commandStop(readStdin()); break;
    case 'post-write': commandPostWrite(readStdin()); break;
    case 'subagent-stop': commandSubagentStop(readStdin()); break;
    case 'kb-write-permit': commandKbWritePermit(argv); break;
    case 'doctor': commandDoctor(argv); break;
    case 'kb-status': commandKbStatus(argv); break;
    case 'kb-validate': commandKbValidate(argv); break;
    case 'alias-check': commandAliasCheck(argv); break;
    case 'status': commandStatus({}); break;
    default:
      process.stdout.write('stand-guard: scan | pre-write | post-write | pre-bash | post-run | record-gate | kb-write-permit | stop | subagent-stop | kb-status | kb-validate | alias-check | doctor | status\n');
      process.exit(0);
  }
} catch (error) {
  // A guard that crashes must not block the session: it reports and stands aside. The alternative is
  // a bug in this file becoming an unworkable repository.
  process.stderr.write(`stand-guard: внутренняя ошибка, проверка не выполнена: ${error && error.message}\n`);
  process.exit(0);
}
