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

const EMPTY = { artifacts: {}, gates: {}, runs: [] };

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

/** Records a gate verdict against the exact content it saw. */
export function recordGate(gate, verdict, files, cwd = process.cwd()) {
  const state = readState(cwd);
  const covers = {};
  for (const [path, artifact] of Object.entries(state.artifacts)) {
    if (files.length === 0 || files.includes(path)) covers[path] = artifact.sha;
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
