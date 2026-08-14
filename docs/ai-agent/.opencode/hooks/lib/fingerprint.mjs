// A failure, reduced to what stays the same when it happens again.
//
// This is the failureFingerprint the kit has never had, derived from artifacts a run really
// produces rather than from telemetry nobody collects. Everything that varies between two runs of
// the SAME defect — ids, timestamps, durations, hashes — is replaced by a token, so the two land on
// one fingerprint and can be counted.

import { createHash } from 'node:crypto';

const SUBSTITUTIONS = [
  [/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, '<uuid>'],
  [/\b\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?Z?\b/g, '<timestamp>'],
  [/\brun-[A-Za-z0-9_-]+/g, '<runid>'],
  [/\b[0-9a-f]{8,}\b/gi, '<hex>'],
  [/'[^']*[\\/][^']*'/g, "'<path>'"],
  [/@\d+/g, '@<id>'],
  // No trailing \b: the duration in `timed out after 30000ms` is followed by a word character, so a
  // bounded rule left untouched the one number that always differs between two runs of one defect.
  [/(?<![\w.])\d{3,}/g, '<num>'],
];

/** The message with everything run-varying replaced. Deterministic, and the same for both callers. */
export function normalise(message) {
  let text = String(message || '').toLowerCase().trim();
  for (const [pattern, token] of SUBSTITUTIONS) {
    text = text.replace(pattern, token);
  }
  return text.replace(/\s+/g, ' ');
}

/** A stable id for one shape of failure. */
export function fingerprint(failure) {
  const normalised = normalise(failure.message);
  return createHash('sha256')
    .update(`${failure.exceptionClass || ''}|${normalised}`)
    .digest('hex')
    .slice(0, 16);
}
