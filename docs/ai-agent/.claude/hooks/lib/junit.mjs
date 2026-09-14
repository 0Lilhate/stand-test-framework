// JUnit XML, read instead of gradle's stdout.
//
// The README of this kit already says why, and it is the most expensive lie in the whole cycle: a
// test that never ran — skipped by a run gate, by @Disabled, by an unmet assumption — still ends the
// build with BUILD SUCCESSFUL. The exit code says nothing about whether anything was executed; the
// XML does.

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

// Directories a project keeps results in are never below these, and walking them is how a hook with a
// 20-second budget turns into a hook that times out.
const NOT_WORTH_WALKING = new Set(['node_modules', 'src', 'out', 'bin']);

/**
 * Output directory → the result directories inside it, by build tool.
 *
 * Maven was missing entirely, and silently: `target` sat in the list above as not worth walking, so a
 * Maven consumer's results were never found — and finding nothing is indistinguishable, from the
 * outside, from a run with nothing to report. That is the exact failure this file exists to prevent,
 * one build tool over. `pom.xml` has been in the dependency detector's own file list all along.
 */
const RESULT_DIRECTORIES = {
  build: ['test-results'],
  target: ['surefire-reports', 'failsafe-reports'],
};

const MAX_DEPTH = 4;

const ATTRIBUTE = (name) => new RegExp(`\\b${name}="([^"]*)"`);

function attribute(text, name) {
  const match = ATTRIBUTE(name).exec(text);
  return match ? match[1] : null;
}

function decode(text) {
  return text
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&#10;/g, '\n').replace(/&amp;/g, '&');
}

/**
 * Every `build/test-results` in the project, not only the one at the root.
 *
 * A single-module project keeps results in `build/test-results`; a multi-module one keeps them in
 * `<module>/build/test-results` and leaves the root directory absent entirely. Looking only at the
 * root therefore found nothing at all in exactly the projects most consumers have — and finding
 * nothing is indistinguishable, from the outside, from a run with nothing to report.
 */
function resultDirectories(root) {
  const found = [];
  const visit = (directory, depth) => {
    let entries;
    try {
      entries = readdirSync(directory, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name.startsWith('.') || NOT_WORTH_WALKING.has(entry.name)) continue;
      const path = join(directory, entry.name);
      const inside = RESULT_DIRECTORIES[entry.name];
      if (inside !== undefined) {
        // Only these directories under the output tree are ours, and descending further would walk
        // every class file of every module.
        for (const name of inside) {
          if (existsSync(join(path, name))) found.push(join(path, name));
        }
      } else if (depth < MAX_DEPTH) {
        visit(path, depth + 1);
      }
    }
  };
  visit(root, 0);
  return found;
}

/** Every TEST-*.xml under a directory tree, newest last. */
function resultFiles(root) {
  const found = [];
  const visit = (directory) => {
    let entries;
    try {
      entries = readdirSync(directory, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) visit(path);
      else if (entry.name.startsWith('TEST-') && entry.name.endsWith('.xml')) found.push(path);
    }
  };
  visit(root);
  return found.sort((a, b) => statSync(a).mtimeMs - statSync(b).mtimeMs);
}

/**
 * What THIS run did — every module of the project, and each result counted once.
 *
 * The second half of that sentence is the load-bearing one. Gradle rewrites only the files of the
 * tests it actually executed, and leaves the rest of the tree exactly as the previous run left it.
 * Reading the whole tree every time therefore re-reported old failures as new ones on every
 * subsequent command: the journal filled with copies of one failure, and the learning loop, which
 * promotes a signature once its fingerprint has been seen twice, would have been reading its own
 * echo. So a result is identified by its path AND its modification time, and one already consumed
 * under that pair is skipped. A file Gradle rewrote has a new time and is read again — which is
 * correct, because that is a second real occurrence.
 *
 * @param root the project directory
 * @param consumed `{ '<path relative to root>': mtimeMs }` of results already read
 * @returns `{ files, tests, skipped, failures: [...], consumed: {} }` — `consumed` is what to remember
 */
export function readResults(root, consumed = {}) {
  const summary = { files: 0, tests: 0, skipped: 0, failures: [], consumed: {} };
  for (const directory of resultDirectories(root)) {
    for (const file of resultFiles(directory)) {
      readOneResult(file, root, consumed, summary);
    }
  }
  return summary;
}

function readOneResult(file, root, consumed, summary) {
  const key = relative(root, file);
  const modified = statSync(file).mtimeMs;
  if (consumed[key] === modified) return;
  summary.consumed[key] = modified;

  const text = readFileSync(file, 'utf8');
  // The <testsuite> element, not the first tag in the file: the XML declaration comes first, and
  // slicing to the first '>' read its attributes instead — leaving every counter at zero, so a run
  // where nothing executed looked exactly like a run with nothing to report.
  const openTag = /<testsuite\b[^>]*>/.exec(text);
  if (openTag === null) return;
  const suite = openTag[0];
  summary.files += 1;
  summary.tests += Number(attribute(suite, 'tests') || 0);
  summary.skipped += Number(attribute(suite, 'skipped') || 0);
  const testClass = attribute(suite, 'name') || key;

  const caseRegex = /<testcase\b([^>]*)>([\s\S]*?)<\/testcase>/g;
  let match;
  while ((match = caseRegex.exec(text)) !== null) {
    const body = match[2];
    const problem = /<(failure|error)\b([^>]*)/.exec(body);
    if (!problem) continue;
    summary.failures.push({
      testClass,
      testMethod: attribute(match[1], 'name') || '',
      exceptionClass: attribute(problem[2], 'type') || '',
      message: decode(attribute(problem[2], 'message') || '').split('\n')[0],
    });
  }
}

/**
 * The one line worth interrupting for.
 *
 * A suite that reports tests but skipped all of them is the shape of a run that touched no stand
 * while the build went green — and nothing else in the pipeline says so.
 */
export function skipWarning(summary) {
  if (summary.tests === 0 || summary.skipped < summary.tests) return null;
  return `Сборка зелёная, но выполнено 0 тестов из ${summary.tests}: все пропущены (skipped=${summary.skipped}). `
    + 'Стенд не был затронут, и зелёная сборка об этом не говорит. Причину смотреть в XML: условная аннотация '
    + 'на классе (@EnabledIf*/@Disabled) либо невыполненное assumption. Если skip держит гейт по переменной '
    + 'окружения — такой гейт НЕ обязателен и чаще вреден: он не видит умолчаний ${VAR:default} из application.yml '
    + 'и глушит тест, который прекрасно прошёл бы на контуре по умолчанию.';
}
