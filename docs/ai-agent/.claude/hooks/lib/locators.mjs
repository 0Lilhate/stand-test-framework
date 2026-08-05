// KPI-9 — the share of locators built on something other than `data-testid`, counted STATICALLY.
//
// The metric exists to pull a trigger: above 50% the BRD escalates the `data-testid` policy to the
// architecture committee (D-5, RISK-01). That is why it may not be read off the generation report,
// however convenient that would be — the report is the agent's own account of its work, and a metric
// that decides whether the agent's output is acceptable cannot be sourced from the agent. It is
// counted here, from merged Page Objects, by something that has never met the case.
//
// What counts as a locator is not a judgement: the SDK has exactly five factories, and `UiLocator`
// itself calls four of them fragile (`fragile()` is false only for `TEST_ID`). This module repeats
// that table and nothing more; `KpiLocatorCountTest` reads `UiLocator.java` and fails if a sixth
// factory appears without arriving here, because a strategy the counter does not know would be
// counted as no locator at all — a silent improvement of the number the metric exists to worsen.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { withoutComments } from './source.mjs';

/** The five factories of `UiLocator`, and whether each is fragile. Mirrors `UiLocator.fragile()`. */
export const STRATEGIES = Object.freeze({
  testId: false,
  role: true,
  label: true,
  text: true,
  css: true,
});

/** Above this share the BRD escalates the `data-testid` policy. Strictly greater — 50% is not a trigger. */
export const ESCALATION_THRESHOLD = 0.5;

/** Directories that hold build output or foreign code: counting them would measure somebody else's locators. */
const SKIPPED_DIRECTORIES = new Set(['build', 'target', 'out', '.git', '.gradle', 'node_modules', '.idea']);

const LOCATOR = /\bUiLocator\s*\.\s*(testId|role|label|text|css)\s*\(/g;

function javaFilesUnder(root) {
  const found = [];
  const walk = (directory) => {
    let entries;
    try {
      entries = readdirSync(directory, { withFileTypes: true });
    } catch {
      // An unreadable directory is reported as empty rather than as a crash: the counter runs in a
      // consumer's repository, where a stray symlink must not stand between a team and its metric.
      return;
    }
    for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        if (!SKIPPED_DIRECTORIES.has(entry.name)) walk(path);
      } else if (entry.isFile() && entry.name.endsWith('.java')) {
        found.push(path);
      }
    }
  };
  walk(root);
  return found;
}

function lineOf(content, index) {
  let line = 1;
  for (let at = 0; at < index; at += 1) {
    if (content[at] === '\n') line += 1;
  }
  return line;
}

/**
 * Counts the locators of every Java file under `root`.
 *
 * Comments are blanked before matching (`withoutComments`), so the line that documents the rule —
 * `// never UiLocator.css(...) here` — is prose about a locator rather than one. String literals are
 * kept: the locator's argument lives in one, and blanking those would lose the evidence the output
 * has to print.
 *
 * A file with no locator at all is not a Page Object and is not counted as one; `files` reports how
 * many actually contributed, so "denominator 0" can be told apart from "wrong directory".
 */
export function countLocators(root) {
  const fragile = [];
  const byStrategy = Object.fromEntries(Object.keys(STRATEGIES).map((name) => [name, 0]));
  const contributing = [];
  let scanned = 0;

  for (const file of javaFilesUnder(root)) {
    scanned += 1;
    let content;
    try {
      content = readFileSync(file, 'utf8');
    } catch {
      continue;
    }
    const code = withoutComments(content);
    let found = 0;
    LOCATOR.lastIndex = 0;
    let match = LOCATOR.exec(code);
    while (match !== null) {
      const strategy = match[1];
      found += 1;
      byStrategy[strategy] += 1;
      if (STRATEGIES[strategy]) {
        // The line is counted in the ORIGINAL, not in the projection: `withoutComments` preserves
        // every offset but blanks the newlines inside block comments along with the rest, so a line
        // number derived from it drifts by one per javadoc line — and a KPI report that points at
        // the wrong line is a report nobody checks twice.
        const line = lineOf(content, match.index);
        fragile.push({ file, line, strategy, evidence: content.split(/\r?\n/)[line - 1].trim() });
      }
      match = LOCATOR.exec(code);
    }
    if (found > 0) contributing.push({ file, locators: found });
  }

  const denominator = Object.values(byStrategy).reduce((sum, count) => sum + count, 0);
  const numerator = fragile.length;
  return {
    root,
    filesScanned: scanned,
    files: contributing,
    denominator,
    numerator,
    fragile,
    // Deliberately null rather than 0 when nothing was found: a directory with no locators has no
    // share, and printing 0% there would report the best possible KPI-9 for having measured nothing.
    ratio: denominator === 0 ? null : numerator / denominator,
    byStrategy,
    escalation: denominator > 0 && numerator / denominator > ESCALATION_THRESHOLD,
    threshold: ESCALATION_THRESHOLD,
  };
}

/** The human rendering: numerator, denominator, and every fragile locator — never a bare percentage. */
export function renderLocatorKpi(result) {
  const lines = [`KPI-9 — доля локаторов не по data-testid; каталог: ${result.root}`];
  if (result.denominator === 0) {
    lines.push('  знаменатель 0 — ни одного локатора не найдено');
    lines.push(`  просмотрено java-файлов: ${result.filesScanned}`);
    lines.push('  доля не вычисляется: у метрики без локаторов нет значения, и 0% здесь означало бы лучший возможный KPI-9 за отсутствие измерения');
    return lines.join('\n');
  }
  const percent = (result.ratio * 100).toFixed(1);
  lines.push(`  ${result.numerator} из ${result.denominator} (${percent}%)`);
  lines.push(`  по стратегиям: ${Object.entries(result.byStrategy).map(([name, count]) => `${name} ${count}`).join(', ')}`);
  lines.push(`  Page Object'ов с локаторами: ${result.files.length} из ${result.filesScanned} просмотренных java-файлов`);
  if (result.numerator === 0) {
    lines.push('  хрупких локаторов нет — каждый построен по data-testid');
  } else {
    lines.push('  хрупкие локаторы:');
    for (const item of result.fragile) {
      lines.push(`    ${item.file}:${item.line} [${item.strategy}] ${item.evidence}`);
    }
  }
  lines.push(result.escalation
    ? `\nпорог ${result.threshold * 100}% превышен — триггер эскалации политики data-testid (BRD D-5, RISK-01). Прогон НЕ блокируется: KPI-9 — метрика, а не гейт`
    : `\nпорог ${result.threshold * 100}% не превышен`);
  return lines.join('\n');
}
