// What this run has produced and what has been checked about it.
//
// The one idea worth stating: a gate records the HASH of the content it passed, not the fact that it
// ran. Edit the file afterwards and the record stops covering it, automatically. That closes "passed
// the review, then quietly adjusted it" without anyone having to notice, which is the only way a
// gate like that ever holds.

import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, appendFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';

const STATE_DIR = '.claude/.stand-test';
const STATE_FILE = join(STATE_DIR, 'state.json');
const JOURNAL_FILE = join(STATE_DIR, 'run-journal.jsonl');
const UNKNOWN_FILE = join(STATE_DIR, 'unknown-signatures.jsonl');

const EMPTY = { artifacts: {}, curated: {}, permit: null, gates: {}, runs: [], results: {}, subagents: [] };

/** Long enough for one promote, short enough that a forgotten permit is not a standing licence. */
const PERMIT_MINUTES = 60;

/** More than enough to answer "was there a fresh context after this file was written". */
const SUBAGENT_HISTORY = 50;

export function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

function ensureDirectory(file) {
  mkdirSync(dirname(file), { recursive: true });
}

export function readState(cwd = process.cwd()) {
  const file = join(cwd, STATE_FILE);
  if (!existsSync(file)) return structuredClone(EMPTY);
  try {
    return { ...structuredClone(EMPTY), ...JSON.parse(readFileSync(file, 'utf8')) };
  } catch {
    // Corrupt state is treated as no state: a hook that crashed on its own bookkeeping would block
    // every write in the session, which is a worse failure than re-running a gate.
    return structuredClone(EMPTY);
  }
}

export function writeState(state, cwd = process.cwd()) {
  const file = join(cwd, STATE_FILE);
  ensureDirectory(file);
  writeFileSync(file, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
}

/** Records that this path now holds this content. Any gate covering the old content stops covering it. */
export function recordArtifact(relativePath, content, cwd = process.cwd()) {
  const state = readState(cwd);
  state.artifacts[relativePath] = { sha: sha256(content), at: new Date().toISOString() };
  writeState(state, cwd);
  return state;
}

/**
 * Records a gate verdict against the exact content it saw.
 *
 * A verdict covers the files it NAMES and nothing else. An empty list used to mean "everything",
 * which made one file-less command certify the whole session without a single re-scan — the default
 * has to fail closed, so now it covers nothing.
 */
export function recordGate(gate, verdict, files, cwd = process.cwd()) {
  const state = readState(cwd);
  const covers = {};
  // Both ledgers, because a gate covers what it named: generated artifacts answer to the safety
  // review, curated knowledge-base files to `kb-write`, and each verdict reaches its own.
  for (const [path, file] of [...Object.entries(state.artifacts), ...Object.entries(state.curated)]) {
    if (files.includes(path)) covers[path] = file.sha;
  }
  state.gates[gate] = { verdict, at: new Date().toISOString(), covers };
  writeState(state, cwd);
  return state;
}

/**
 * Artifacts no PASS verdict of this gate covers — because it never ran, or because they changed
 * after it did.
 */
export function staleArtifacts(gate, cwd = process.cwd()) {
  const state = readState(cwd);
  const record = state.gates[gate];
  const covers = record !== undefined && record.verdict === 'PASS' ? record.covers || {} : {};
  return Object.entries(state.artifacts)
    .filter(([path, artifact]) => covers[path] !== artifact.sha)
    .map(([path]) => path);
}

/**
 * Opens a window in which the named curated files may be written.
 *
 * A permit is a DECLARATION OF INTENT AND SCOPE, not a lock. The model can issue one — the guard
 * script is in the run's allow-list — so nothing here proves a human agreed to anything. What it does
 * buy is real and worth having: the paths are declared before the content exists, so a write that
 * strays outside them is refused while it is still recoverable, and the reason is on the record where
 * a person reading the session can see it. The human decision happens at the write itself, through the
 * host's permission prompt, which the model cannot forge.
 */
export function issuePermit(permit, cwd = process.cwd()) {
  const state = readState(cwd);
  const previous = state.permit;
  const issuedAt = new Date();
  state.permit = {
    ...permit,
    issuedAt: issuedAt.toISOString(),
    expiresAt: new Date(issuedAt.getTime() + PERMIT_MINUTES * 60_000).toISOString(),
    consumed: {},
  };
  writeState(state, cwd);
  return previous;
}

/** The permit if one is standing and still in date, otherwise null. */
export function activePermit(cwd = process.cwd()) {
  const permit = readState(cwd).permit;
  if (permit === null || permit === undefined) return null;
  return Date.parse(permit.expiresAt) > Date.now() ? permit : null;
}

/**
 * Records that a permitted path was written. Deliberately does NOT spend the permit: one apply
 * touches several files and re-touches the owning service's rollup, and a single-use permit would
 * turn that into four refusals — the shape of gate people switch off.
 */
export function consumePermit(path, cwd = process.cwd()) {
  const state = readState(cwd);
  if (state.permit === null || state.permit === undefined) return null;
  state.permit.consumed = { ...state.permit.consumed, [path]: (state.permit.consumed[path] || 0) + 1 };
  writeState(state, cwd);
  return state.permit;
}

export function revokePermit(cwd = process.cwd()) {
  const state = readState(cwd);
  const previous = state.permit;
  state.permit = null;
  writeState(state, cwd);
  return previous;
}

/**
 * Records what a curated knowledge-base file now holds.
 *
 * Kept apart from {@link recordArtifact} on purpose: an artifact answers to the safety review of a
 * generated test, a curated KB file answers to the `kb-write` gate. One ledger for both would make
 * each gate hold files it has nothing to say about.
 */
export function recordCurated(relativePath, content, cwd = process.cwd()) {
  const state = readState(cwd);
  state.curated[relativePath] = { sha: sha256(content), at: new Date().toISOString() };
  writeState(state, cwd);
  return state;
}

/** Curated files no PASS of the `kb-write` gate covers — because it never ran, or they changed after. */
export function staleCurated(gate, cwd = process.cwd()) {
  const state = readState(cwd);
  const record = state.gates[gate];
  const covers = record !== undefined && record.verdict === 'PASS' ? record.covers || {} : {};
  return Object.entries(state.curated)
    .filter(([path, file]) => covers[path] !== file.sha)
    .map(([path]) => path);
}

/**
 * Records that a subagent finished.
 *
 * This is the whole of what the host lets a hook know about delegation: that a separate context ran
 * and ended, at this moment. Not which one, not what it was asked, not what it concluded. It is
 * enough for one question — "did anything other than the writing context run since this file was
 * written" — and that question is the difference between a review by a fresh context and a review
 * declared by the context that wrote the code.
 */
export function recordSubagentStop(cwd = process.cwd()) {
  const state = readState(cwd);
  state.subagents = [...state.subagents, new Date().toISOString()].slice(-SUBAGENT_HISTORY);
  writeState(state, cwd);
  return state;
}

/**
 * Whether a subagent finished no earlier than the given moment.
 *
 * Ties are resolved in the caller's favour: a stop in the same millisecond as a write cannot really
 * have reviewed it, but a false refusal here costs more than that theoretical tie — it is the shape
 * of gate people switch off.
 */
export function subagentRanSince(moment, cwd = process.cwd()) {
  return readState(cwd).subagents.some((at) => Date.parse(at) >= Date.parse(moment));
}

/** The JUnit result files already read, as `{ '<path relative to cwd>': mtimeMs }`. */
export function consumedResults(cwd = process.cwd()) {
  return readState(cwd).results;
}

/**
 * Remembers the results just read, so a later run does not report them a second time.
 *
 * Entries whose file is gone are dropped: `./gradlew clean` deletes the tree, and a remembered time
 * for a path that no longer exists would only make the map grow.
 */
export function rememberResults(consumed, cwd = process.cwd()) {
  const state = readState(cwd);
  const kept = {};
  for (const [path, modified] of Object.entries({ ...state.results, ...consumed })) {
    if (existsSync(join(cwd, path))) kept[path] = modified;
  }
  state.results = kept;
  writeState(state, cwd);
  return state;
}

export function appendJournal(entry, cwd = process.cwd()) {
  const file = join(cwd, JOURNAL_FILE);
  ensureDirectory(file);
  appendFileSync(file, `${JSON.stringify(entry)}\n`, 'utf8');
}

export function appendUnknownSignature(entry, cwd = process.cwd()) {
  const file = join(cwd, UNKNOWN_FILE);
  ensureDirectory(file);
  appendFileSync(file, `${JSON.stringify(entry)}\n`, 'utf8');
}

export const paths = { STATE_DIR, STATE_FILE, JOURNAL_FILE, UNKNOWN_FILE };
