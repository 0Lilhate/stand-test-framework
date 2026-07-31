// Whether this installation of the kit is the kit, and whether it can actually run.
//
// Everything that holds the bundle together — the schema tests, the parity tests, the inventory
// snapshot — lives in the SDK repository and does not travel. At a consumer the kit is a directory of
// markdown that nothing checks: a file edited to soften a guardrail, a hook lost while merging
// settings.json, an install that predates half the enforcement layer. All three look exactly like a
// working kit until the moment they matter.
//
// So the manifest travels, and this compares an installation against it. Nothing here is a gate: it
// answers questions, and the answers are only as good as the manifest that arrived — a consumer who
// edited both the file and the manifest gets the report they asked for. What it does catch is drift,
// which is what actually happens.

import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { BUNDLE } from './bundle.mjs';

/** The events the kit needs wired to enforce anything at all. */
const REQUIRED_HOOKS = ['PreToolUse', 'PostToolUse', 'Stop', 'SubagentStop', 'SessionStart'];

const NOT_SHIPPED = new Set(['settings.local.json', 'scheduled_tasks.lock', '.DS_Store', 'MANIFEST.json']);

const NOT_SHIPPED_PREFIXES = ['.env', '.fetched-'];

const NOT_SHIPPED_DIRECTORIES = new Set(['.stand-test']);

function sha256(buffer) {
  return `sha256:${createHash('sha256').update(buffer).digest('hex')}`;
}

/** The bundle this hook is installed in — `.claude` normally, whatever it was renamed to otherwise. */
function bundleRoot(hooksDir) {
  return join(hooksDir, '..');
}

function walk(directory, root, found = []) {
  if (!existsSync(directory)) return found;
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!NOT_SHIPPED_DIRECTORIES.has(entry.name)) walk(join(directory, entry.name), root, found);
    } else if (!NOT_SHIPPED.has(entry.name) && !NOT_SHIPPED_PREFIXES.some((prefix) => entry.name.startsWith(prefix))) {
      found.push(relative(root, join(directory, entry.name)).split('\\').join('/'));
    }
  }
  return found;
}

/**
 * The permission policy of the opencode copy, which expresses it in its own file.
 *
 * The guard itself is plain Node and ships to both bundles — what does NOT travel is the WIRING, and
 * saying so is the whole job of this branch. Reporting "settings.json отсутствует" there would be
 * false twice: the file is not supposed to exist, and the checks it would wire are available anyway,
 * by hand. What must still hold is the perimeter opencode CAN express — its own edit denials over the
 * bundle — because a run that may rewrite its rules widens itself and then passes everything left.
 */
function checkOpencodePolicy(bundle) {
  const file = join(bundle, 'opencode.json');
  let config;
  try {
    config = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    return { ok: false, why: `opencode.json не разбирается как JSON (${error && error.message}) — хост не поднимет ни одного правила` };
  }
  const edit = JSON.stringify((config.permission || {}).edit || {});
  if (!edit.includes('/hooks/') || !edit.includes('/rules/')) {
    return {
      ok: false,
      why: 'opencode.json не запрещает правку собственных хуков и правил: прогон, который может их переписать, '
        + 'расширяет периметр, после чего проходят все оставшиеся гейты',
    };
  }
  return {
    ok: true,
    why: 'событий у хоста нет — периметр в opencode.json, а проверки гарда запускаются командой '
      + '(scan, kb-validate, record-gate, doctor). Автоматического отказа в момент записи здесь не существует',
  };
}

function checkHooks(bundle, name) {
  const file = join(bundle, 'settings.json');
  if (!existsSync(file)) {
    return existsSync(join(bundle, 'opencode.json'))
      ? checkOpencodePolicy(bundle)
      : { ok: false, why: 'settings.json отсутствует: без него не исполняется ни один хук, и кит становится набором советов' };
  }
  let settings;
  try {
    settings = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    return { ok: false, why: `settings.json не разбирается как JSON (${error && error.message}) — хост в этом случае не поднимет ни одного хука` };
  }
  const wired = Object.keys(settings.hooks || {});
  const missing = REQUIRED_HOOKS.filter((event) => !wired.includes(event));
  if (missing.length > 0) {
    return { ok: false, why: `не подключены события: ${missing.join(', ')} — обычно это следствие ручного слияния settings.json` };
  }
  const perimeter = JSON.stringify((settings.permissions || {}).deny || []);
  if (!perimeter.includes(`${name}/hooks/`) || !perimeter.includes(`${name}/rules/`)) {
    return { ok: false, why: 'прогон может править собственные хуки или правила: периметр расширяется одной правкой, после чего проходят все оставшиеся гейты' };
  }
  return { ok: true, why: '' };
}

/**
 * The state of one installation: version, drift, wiring, and what the run needs to work at all.
 *
 * @param hooksDir the directory this file lives in
 * @param cwd the project the kit is installed into
 */
export function diagnose(hooksDir, cwd) {
  const bundle = bundleRoot(hooksDir);
  const name = relative(cwd, bundle).split('\\').join('/') || BUNDLE;
  const report = { bundle: name, node: process.version, manifest: null, version: null, checks: [], problems: [] };

  const add = (level, subject, detail) => {
    report.checks.push({ level, subject, detail });
    if (level === 'problem') report.problems.push(`${subject}: ${detail}`);
  };

  const manifestPath = join(bundle, 'MANIFEST.json');
  if (!existsSync(manifestPath)) {
    add('problem', 'манифест', 'нет MANIFEST.json рядом с бандлом — установка была копированием каталога, '
      + 'и сказать, какая версия кита стоит и что в ней правлено, нечем. Переустановите: node install.mjs <target>');
    return report;
  }

  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (error) {
    add('problem', 'манифест', `MANIFEST.json не разбирается: ${error && error.message}`);
    return report;
  }
  report.manifest = manifestPath;
  report.version = manifest.version;

  const expected = Object.keys(manifest.files).filter((path) => path.startsWith(`${name}/`));
  const missing = [];
  const modified = [];
  for (const path of expected) {
    const file = join(cwd, path);
    if (!existsSync(file)) {
      missing.push(path);
      continue;
    }
    if (sha256(readFileSync(file)) !== manifest.files[path]) modified.push(path);
  }
  const present = new Set(walk(bundle, cwd).map((path) => path));
  const unknown = [...present].filter((path) => !expected.includes(path));

  if (expected.length === 0) {
    add('problem', 'манифест', `не описывает бандл '${name}' — либо кит установлен под другим именем, либо манифест от другой копии`);
  }
  if (missing.length > 0) add('problem', 'файлы', `не доехали: ${missing.length} (${missing.slice(0, 5).join(', ')}${missing.length > 5 ? ', …' : ''})`);
  if (modified.length > 0) {
    add('warn', 'файлы', `правлены после установки: ${modified.length} (${modified.slice(0, 5).join(', ')}${modified.length > 5 ? ', …' : ''}) — `
      + 'локальная правка гардрейла выглядит как обычный кит и переживает обновление');
  }
  if (unknown.length > 0) {
    add('warn', 'файлы', `есть в каталоге, но не в манифесте: ${unknown.length} (${unknown.slice(0, 5).join(', ')}${unknown.length > 5 ? ', …' : ''})`);
  }
  if (missing.length === 0 && modified.length === 0 && unknown.length === 0 && expected.length > 0) {
    add('ok', 'файлы', `${expected.length} на месте, содержимое совпадает с манифестом`);
  }

  // The clean answer is the branch's own, not a constant: the two hosts are clean in different ways,
  // and printing "подключены все 5 событий" over a host that has no events is the report claiming an
  // enforcement layer that is not there.
  const hooks = checkHooks(bundle, name);
  add(hooks.ok ? 'ok' : 'problem', 'хуки', hooks.why || `подключены все ${REQUIRED_HOOKS.length} событий`);

  const registry = ['stand-test-environments.yml', 'src/test/resources/stand-test-environments.yml',
    'src/test/resources/application.yml', 'application.yml'].find((path) => existsSync(join(cwd, path)));
  add(registry ? 'ok' : 'warn', 'реестр окружений', registry
    || 'не найден: без него не стартует и сам SDK, и бутстрапить базу знаний не из чего');
  add(existsSync(join(cwd, 'knowledge-base')) ? 'ok' : 'warn', 'база знаний',
    existsSync(join(cwd, 'knowledge-base')) ? 'на месте' : 'нет каталога knowledge-base — первый кейс будет допросом (/stand-test-bootstrap-kb)');

  return report;
}

export function render(report) {
  const lines = [`кит ${report.bundle}, версия ${report.version === null ? '—' : report.version}, node ${report.node}`];
  for (const check of report.checks) {
    lines.push(`${check.level === 'ok' ? '✔' : check.level === 'warn' ? '⚠' : '✖'} ${check.subject}: ${check.detail}`);
  }
  lines.push(report.problems.length === 0
    ? '\nпроблем нет. Замечания выше — то, что кит увидеть может; чего он увидеть не может, он не утверждает.'
    : `\nпроблем: ${report.problems.length}`);
  return lines.join('\n');
}
