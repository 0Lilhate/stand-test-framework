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
import { join } from 'node:path';
import { scanArtifact, blocking, render, gates } from './lib/scan.mjs';
import { readResults, skipWarning } from './lib/junit.mjs';
import { fingerprint, normalise } from './lib/fingerprint.mjs';
import * as state from './lib/state.mjs';

const SAFETY_GATE = 'safety-review';

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
  const files = positional(argv);
  const policy = policyOf(process.cwd());
  let findings = [];
  for (const file of files) {
    if (!existsSync(file)) {
      process.stderr.write(`нет файла: ${file}\n`);
      continue;
    }
    findings = findings.concat(scanArtifact(readFileSync(file, 'utf8'), alias || file, policy));
  }
  const { ran, notRun } = gates();
  if (argv.includes('--json')) {
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

  // The knowledge base has one writable channel, and it is not this one. `mappings/` and
  // `candidates/` are the agent's; the curated collections are written only by an approved promote.
  if (/^knowledge-base\//.test(path) && !/^knowledge-base\/(mappings|candidates)\//.test(path)) {
    block(`✖ запись в курируемую базу знаний: ${path}\n`
      + '  Агенту разрешены только knowledge-base/mappings/** и knowledge-base/candidates/**.\n'
      + '  → пройдите /stand-test-review-kb-candidates и /stand-test-apply-kb-candidates — '
      + 'единственный путь, на котором есть человеческий гейт.');
  }

  const content = intendedContent(payload.tool_name, payload.tool_input || {});
  if (!content) proceed();

  const findings = scanArtifact(content, path, policyOf(cwd));
  const stoppers = blocking(findings);
  if (stoppers.length > 0) {
    block(`Запись отвергнута: ${stoppers.length} блокирующих находок в ${path}\n\n`
      + `${stoppers.map(render).join('\n\n')}\n\n`
      + 'Это guardrails SDK, а не советы: исправьте содержимое, не обходите проверку.');
  }

  state.recordArtifact(path, content, cwd);
  const notes = findings.filter((item) => item.severity !== 'BLOCK');
  proceed(notes.length > 0 ? `⚠ ${notes.length} находок уровня HIGH в ${path}:\n${notes.map(render).join('\n')}` : '');
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

  const covered = Object.keys(state.recordGate(gate, claimed, files, cwd).gates[gate].covers);
  const uncovered = files.filter((file) => !covered.includes(file));
  proceed(`гейт ${gate}: ${claimed} (${files.join(', ')})`
    + (uncovered.length > 0
      ? `\n⚠ эта сессия не записывала ${uncovered.length} из перечисленных файлов, и вердикт их не покрывает: ${uncovered.join(', ')}`
      : ''));
}

function commandStop(payload) {
  const cwd = payload.cwd || process.cwd();
  const stale = state.staleArtifacts(SAFETY_GATE, cwd);
  if (stale.length === 0) proceed();

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

function commandStatus(payload) {
  const cwd = (payload && payload.cwd) || process.cwd();
  const current = state.readState(cwd);
  const artifacts = Object.keys(current.artifacts).length;
  const stale = state.staleArtifacts(SAFETY_GATE, cwd).length;
  const kb = existsSync(join(cwd, 'knowledge-base')) ? 'есть' : 'НЕТ';
  const registry = ['stand-test-environments.yml', 'src/test/resources/stand-test-environments.yml']
    .some((path) => existsSync(join(cwd, path))) ? 'есть' : 'НЕТ';
  const { ran, notRun } = gates();
  proceed(`stand-test: KB ${kb}, реестр окружений ${registry}; артефактов за сессию ${artifacts}, `
    + `без пройденного safety-review ${stale}; детекторов активно ${ran.length}/${ran.length + notRun.length}`);
}

const VALUE_FLAGS = new Set(['--gate', '--verdict', '--as']);

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
    case 'status': commandStatus({}); break;
    default:
      process.stdout.write('stand-guard: scan | pre-write | pre-bash | post-run | record-gate | stop | status\n');
      process.exit(0);
  }
} catch (error) {
  // A guard that crashes must not block the session: it reports and stands aside. The alternative is
  // a bug in this file becoming an unworkable repository.
  process.stderr.write(`stand-guard: внутренняя ошибка, проверка не выполнена: ${error && error.message}\n`);
  process.exit(0);
}
