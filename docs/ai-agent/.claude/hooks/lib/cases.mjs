// Where the cases are, and what a case says it expects.
//
// Two layouts, because both exist and neither is wrong. A consumer keeps a folder of text files, one
// case each. The evaluation corpus in this repository keeps a DIRECTORY per case — `input.md` beside
// a `case.yml` of machine-checkable expectations — and a runner that only understood flat files would
// find nothing in the only corpus that exists.
//
// The expectations are read the way everything else in these hooks reads YAML: by line, for the keys
// that are needed, with no parser. That is a real limit and it points one way — a key this cannot find
// is reported as UNKNOWN rather than as satisfied. An expectation quietly assumed to hold is how a
// report comes to say more than anybody checked.

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { basename, join } from 'node:path';

const TEXT = /\.(md|txt)$/;

/**
 * Every case under a path: a file, a folder of files, or a folder of case DIRECTORIES.
 *
 * @returns `[{ id, input, spec }]` — `spec` is the `case.yml` beside the input, or null
 */
export function discover(path) {
  if (!existsSync(path)) return [];
  if (statSync(path).isFile()) {
    return TEXT.test(path) ? [{ id: basename(path).replace(TEXT, ''), input: path, spec: null }] : [];
  }

  const found = [];
  for (const name of readdirSync(path).sort()) {
    const entry = join(path, name);
    if (statSync(entry).isFile()) {
      if (TEXT.test(name)) found.push({ id: name.replace(TEXT, ''), input: entry, spec: null });
      continue;
    }
    // A case directory: the corpus layout. `input.md` is the text the agent gets, verbatim.
    const input = ['input.md', 'input.txt'].map((file) => join(entry, file)).find(existsSync);
    if (input !== undefined) {
      const spec = join(entry, 'case.yml');
      found.push({ id: name, input, spec: existsSync(spec) ? spec : null });
    }
  }
  return found;
}

/** The value of a key at any indent, first occurrence. */
function value(text, key) {
  const match = new RegExp(`^[^\\S\\n]*${key}:[^\\S\\n]*(.+?)[^\\S\\n]*$`, 'm').exec(text);
  return match === null ? null : match[1].replace(/^["']|["']$/g, '');
}

/** The list items directly under a key, stopping at the first line that leaves the block. */
function list(text, key) {
  const lines = text.split('\n');
  const start = lines.findIndex((line) => new RegExp(`^[^\\S\\n]*${key}:[^\\S\\n]*$`).test(line));
  if (start < 0) return [];
  const indent = lines[start].length - lines[start].trimStart().length;
  const items = [];
  for (let index = start + 1; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.trim() === '') continue;
    const own = line.length - line.trimStart().length;
    if (own < indent || (own === indent && !line.trimStart().startsWith('-'))) break;
    if (line.trimStart().startsWith('- ')) items.push(line.trimStart().slice(2).trim().replace(/^["']|["']$/g, ''));
  }
  return items;
}

/**
 * What a case says should happen, restricted to what a run can be checked against here.
 *
 * Two families, and the report has to say so: whether a HUMAN was supposed to be needed, and content
 * that must never appear in a written artifact. Everything else in `case.yml` — which KB entries were
 * retrieved, which step types the plan used, how the run ended, how the failure was classified — is
 * invisible to the hooks, which record what was WRITTEN and what was GATED and nothing about how the
 * work was reasoned. Claiming those would be inventing a measurement.
 */
export function expectations(spec) {
  if (spec === null) return null;
  const text = readFileSync(spec, 'utf8');

  // `expected:` appears twice — as the top-level block and inside `humanRequired:`. Take the one
  // that belongs to humanRequired by scanning forward from that key.
  let humanRequired = null;
  const lines = text.split('\n');
  const marker = lines.findIndex((line) => /^[^\S\n]*humanRequired:[^\S\n]*$/.test(line));
  if (marker >= 0) {
    for (let index = marker + 1; index < lines.length && index < marker + 6; index += 1) {
      const own = /^[^\S\n]*expected:[^\S\n]*(true|false)[^\S\n]*$/.exec(lines[index]);
      if (own !== null) {
        humanRequired = own[1] === 'true';
        break;
      }
    }
  }

  const terminalState = value(text, 'terminalState');
  return {
    id: value(text, 'id'),
    humanRequired: humanRequired === null && terminalState === null ? null
      : humanRequired === true || terminalState === 'AWAITING_APPROVAL',
    forbidden: list(text, 'forbiddenArtifactPatterns'),
  };
}

/**
 * Expectation families this runner cannot check, named so a partial pass cannot read as a full one.
 *
 * The same discipline the scanner applies to its own detector table: a clean report from part of the
 * set is not a clean report. Every entry here is invisible to the hooks by construction, not merely
 * unimplemented — the hooks record what was written and gated, not how it was decided.
 */
export const NOT_CHECKED = [
  'retrieval.matched / mustNotMatch — хуки не пишут трассу поиска по базе знаний',
  'plan.track / stepTypes / minSteps — план шагов нигде не материализуется как факт',
  'execution.outcome / skipped — это журнал прогонов, а не состояние сессии',
  'classification.* и repair.* — суждения о причине падения, а не наблюдения',
  'forbiddenDiff — нужна предыдущая версия репозитория, а не сессии',
  'sut (5 кейсов) — нужен управляемый дубль системы под тестом',
  'kbOverlay (2 кейса) — нужно затенение базы знаний на один прогон',
  'repeats — прогон одного кейса несколько раз без дубля ничего не измеряет',
];
