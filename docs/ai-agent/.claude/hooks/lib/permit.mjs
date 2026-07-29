// Which part of the knowledge base a path belongs to, and what a write permit may cover.
//
// The curated knowledge base used to be unwritable by the agent outright. That read as a strong rule
// and was in fact a broken one: `/stand-test-kb-update --apply` and `/stand-test-apply-kb-candidates`
// exist to write exactly those files, so the promote step was documented in four assets and possible
// in none. A rule nothing can satisfy is not enforcement — it is a step people perform outside the
// tool, where nothing watches at all.
//
// What is here instead is a permit: the paths a write may touch, declared BEFORE the content exists.
// Read the honesty note on `reviewRecordFor` before adding anything to this file — the temptation is
// to make the permit look like an authorisation, and it cannot be one.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/** The staging channels the agent has always been allowed to write. */
const STAGING = /^knowledge-base\/(mappings|candidates)\//;

const SCHEMA = /^knowledge-base\/schema\//;

const KNOWLEDGE_BASE = /^knowledge-base\//;

// Horizontal whitespace only. `\s` crosses the line break, so `^reviewedBy:\s*\S` happily "found" a
// value on the NEXT line and reported an empty field as filled — a parsing slip that granted a permit,
// which is the one direction this file is not allowed to fail in. A test caught it; the comment above
// about not interpreting values is not caution for its own sake.
const H = '[^\\S\\n]';

/**
 * What kind of path this is — the whole decision, taken from the path alone.
 *
 * By path and not by content, deliberately: on the `Edit` route a hook sees the replacement fragment
 * rather than the resulting file, so any rule that reasons about what a file CONTAINS is unsound
 * before the write. Content is judged after it lands, by the `kb-write` gate, where the bytes are real.
 */
export function classify(path) {
  if (SCHEMA.test(path)) return 'schema';
  if (STAGING.test(path)) return 'staging';
  if (KNOWLEDGE_BASE.test(path)) return 'curated';
  return 'outside';
}

/**
 * Whether a permit may be issued for this file set at all.
 *
 * `schema/**` is refused HERE as well as at write time. Two checks for one rule is not redundancy:
 * the contract the whole kit derives from must be impossible to permit by construction, not merely
 * unreachable because the checks happen to run in a helpful order.
 */
export function validateFileSet(files) {
  if (files.length === 0) {
    return { ok: false, why: 'пермит должен назвать файлы: разрешение без области — это разрешение на всё' };
  }
  const schema = files.filter((file) => classify(file) === 'schema');
  if (schema.length > 0) {
    return { ok: false, why: `контракт схем не пишет прогон, который он ограничивает: ${schema.join(', ')}` };
  }
  const outside = files.filter((file) => classify(file) === 'outside');
  if (outside.length > 0) {
    return { ok: false, why: `не файлы базы знаний: ${outside.join(', ')} — этот пермит только про knowledge-base/` };
  }
  const staging = files.filter((file) => classify(file) === 'staging');
  if (staging.length > 0) {
    return { ok: false, why: `staging и так открыт, пермит на него ничего не значит: ${staging.join(', ')}` };
  }
  return { ok: true, why: '' };
}

/**
 * Whether a review record exists for this staging document, and looks like a review record.
 *
 * **This is a consistency check, not an authorisation, and it cannot become one.** The file lives
 * under `knowledge-base/candidates/`, which the agent writes: `reviewedBy: someone` written by the
 * model carries exactly the authority of nothing. What it catches is a mistyped document id and a
 * promote aimed at a document nobody reviewed — worth catching, and worth not overstating.
 *
 * Only three anchored line checks, and no value is interpreted beyond "present and shaped like a
 * date". Reading further — `status`, `review.decision`, per-candidate confidence — would mean
 * associating values with list items in YAML by regex, where a mis-association GRANTS a write. Every
 * other parsing weakness in this kit produces a miss or a false finding; that one would produce a
 * permission, which is the one failure direction the design does not accept.
 */
export function reviewRecordFor(cwd, documentId) {
  if (!documentId) {
    return { ok: false, why: '--document обязателен для --reason promote: промоут всегда про конкретный документ' };
  }
  const path = join('knowledge-base', 'candidates', documentId, 'review-decisions.yml');
  if (!existsSync(join(cwd, path))) {
    return { ok: false, why: `нет ${path}: документ с таким id не проходил ревью (или id набран с опечаткой)` };
  }
  let text;
  try {
    text = readFileSync(join(cwd, path), 'utf8');
  } catch (error) {
    return { ok: false, why: `${path} не читается: ${error && error.message}` };
  }
  if (!new RegExp(`^documentId:${H}*["']?${documentId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}["']?${H}*$`, 'm').test(text)) {
    return { ok: false, why: `${path} объявляет другой documentId — решение по ревью и каталог должны говорить об одном документе` };
  }
  if (!new RegExp(`^reviewedBy:${H}*\\S`, 'm').test(text)) {
    return { ok: false, why: `${path} не называет ревьюера (reviewedBy) — запись о ревью без человека не является записью о ревью` };
  }
  if (!new RegExp(`^reviewedOn:${H}*["']?\\d{4}-\\d{2}-\\d{2}["']?${H}*$`, 'm').test(text)) {
    return { ok: false, why: `${path} не называет дату ревью (reviewedOn: YYYY-MM-DD)` };
  }
  return { ok: true, why: '' };
}

/** The permit as a person reads it. */
export function describe(permit) {
  const scope = permit.reason === 'promote' ? `документ ${permit.document}`
    : permit.reason === 'update' ? `источник ${permit.source}`
      : 'починка находок kb-validate';
  return `пермит на запись: ${permit.reason} (${scope}), до ${permit.expiresAt}\n  ${permit.files.join('\n  ')}`;
}
