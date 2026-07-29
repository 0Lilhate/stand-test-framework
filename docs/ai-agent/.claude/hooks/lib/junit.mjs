// JUnit XML, read instead of gradle's stdout.
//
// The README of this kit already says why, and it is the most expensive lie in the whole cycle: a
// test gated with @EnabledIfEnvironmentVariable that never ran still ends the build with
// BUILD SUCCESSFUL. The exit code says nothing about whether anything was executed; the XML does.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

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
 * What a test run actually did.
 *
 * @returns `{ files, tests, skipped, failures: [{ testClass, testMethod, exceptionClass, message }] }`
 */
export function readResults(root, since = 0) {
  const summary = { files: 0, tests: 0, skipped: 0, failures: [] };
  for (const file of resultFiles(root)) {
    if (statSync(file).mtimeMs < since) continue;
    const text = readFileSync(file, 'utf8');
    // The <testsuite> element, not the first tag in the file: the XML declaration comes first, and
    // slicing to the first '>' read its attributes instead — leaving every counter at zero, so a run
    // where nothing executed looked exactly like a run with nothing to report.
    const openTag = /<testsuite\b[^>]*>/.exec(text);
    if (openTag === null) continue;
    const suite = openTag[0];
    summary.files += 1;
    summary.tests += Number(attribute(suite, 'tests') || 0);
    summary.skipped += Number(attribute(suite, 'skipped') || 0);
    const testClass = attribute(suite, 'name') || file;

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
  return summary;
}

/**
 * The one line worth interrupting for.
 *
 * A suite that reports tests but skipped all of them is the shape of a run that touched no stand
 * while the build went green — and nothing else in the pipeline says so.
 */
export function skipWarning(summary) {
  if (summary.tests === 0 || summary.skipped < summary.tests) return null;
  return `BUILD SUCCESSFUL, но выполнено 0 тестов из ${summary.tests}: все пропущены (skipped=${summary.skipped}). `
    + 'Скорее всего сработал гейт @EnabledIfEnvironmentVariable — переменные окружения не доехали до тестовой JVM. '
    + 'Стенд не был затронут, и зелёная сборка об этом не говорит. Пробрасывать переменные надо в tasks.withType<Test>, '
    + 'а не через export перед ./gradlew.';
}
