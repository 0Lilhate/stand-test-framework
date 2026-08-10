// Finding 18: the failure that stopped being reported.
//
// The other seventeen findings read one artifact and decide. This one cannot: an assertion that was
// DELETED is not in the file, and a timeout that GREW looks exactly like a timeout. The question needs
// two versions, which is why it shipped declared and switched off — and why "17 of 18" has been the
// honest number in every report until now.
//
// Both versions exist in two places. The write hook holds the file on disk and the content about to
// replace it, so a red test being quietly made green is refused as it happens. In CI the previous
// version comes from the base branch: `git show origin/main:path`, handed to `scan --against`.
//
// Severity is per signal rather than per detector, because these four are not equally certain. A
// deleted assertion and a bare @Disabled are decisions about coverage and block. A new catch or a
// grown timeout can be legitimate work, so they are reported as heuristics for a person to confirm —
// a gate that blocks on maybes is one people switch off, and it takes the certain findings with it.

import { withoutComments } from './source.mjs';

/**
 * What counts as an assertion, by artifact kind. Deliberately narrow: a miscount here is a false BLOCK.
 *
 * This is a MIRROR of the step-builder crib in `skills/stand-test-java-dsl-authoring/SKILL.md`, of the
 * UI surface in `skills/stand-test-ui-java-authoring/ui-sdk-surface-checklist.md` and of the executable
 * subset in `stand-test-scenario.schema.json`, and it has to move when they do. Twice now it had not.
 *
 * First: six of the ten Java entries — `.expectBody`, `.expectJsonPath`, `.expectHeader`,
 * `.expectField`, `.expectRow`, `.expectMessage` — named methods that do not exist in the SDK and never
 * did, while the `assertPath` family (the most-used assertion in the repository) and `.expectValue`
 * (the only one `db.expectEventually` has) were missing. Deleting the verification half of a generated
 * scenario changed the count by nothing. Three of the six phantoms survived that repair and are gone
 * now; a pattern that cannot match is not harmless, it is a line that makes the list look checked.
 *
 * Second, and the reason to touch this again: the whole UI vocabulary was absent. `assertVisible`,
 * `assertEnabled`, `assertText`, `assertTextContains`, `assertTextMatches`, `assertValue`,
 * `assertAttribute` and `assertProperty` are what a `ui.expect` step asserts WITH — and none of them
 * starts with `assertPath`, so deleting every check from a UI test moved the count by zero. The branch
 * whose worst artifact is an invented locator was the branch whose deleted assertions nothing counted.
 * The list is enumerated against the adapters' own `public` signatures rather than widened to
 * `\.assert[A-Z]\w*\(`: a miscount here is a false BLOCK, and a consumer's own helper named
 * `assertSomething` must not become one.
 *
 * The document entries were worse than incomplete, they were pointed at the wrong document. Neither
 * `assertions:` nor `matcher:` occurs anywhere in the executable scenario schema — they belong to the
 * test-PLAN and to the knowledge base — so on every YAML/JSON scenario this skill produces the count
 * was 0 before and 0 after, and the branch could not fire. What it did fire on was knowledge-base
 * curation, where removing an entry is the ordinary work of the job and a BLOCK is a false one.
 */
const ASSERTIONS = {
  java: [/\bassertThat\s*\(/g, /\bassertThatThrownBy\s*\(/g, /\bassertThatCode\s*\(/g,
    /\.assertPath[A-Za-z]*\s*\(/g, /\.expectStatus\s*\(/g, /\.expectValue\s*\(/g,
    /\.assert(?:Visible|Enabled|TextContains|TextMatches|Text|Value|Attribute|Property)\s*\(/g],
  document: [
    /"(?:equals|contains|matches|exists|notNull)"\s*:/g,
    /^\s*-?\s*(?:equals|contains|matches|exists|notNull)\s*:/gm,
    /"singleValue"\s*:/g,
    /^\s*singleValue\s*:/gm,
  ],
};

/**
 * Whether a document is an executable scenario, and so whether its keys mean what the catalogue above
 * assumes they mean.
 *
 * Without this question the document branch is not a check, it is a word search. `kindOf` calls every
 * `.json` and `.yaml` in the project a document, and the keys an assertion is spelled with are
 * ordinary words elsewhere: `status` is a review state in a knowledge-base candidate and a field in a
 * REST fixture, `contains` is a JSON Schema keyword, `equals` is a curated entry's own field. Counting
 * them everywhere turned promoting a KB candidate, editing a fixture and relaxing a schema into
 * blocking findings — the ordinary work of three different skills, refused by the gate that exists to
 * protect them. The narrower branch below misses an expectation in a document that never declares
 * itself a scenario; that is the safe direction, and the one that keeps the gate switched on.
 */
export function isExecutableScenario(content) {
  // `environment` and `steps`, because those are what the schema requires and what nothing else in the
  // kit carries together. Not `scenarioId`: the first version of this gate asked for it, and the
  // executable format has no such key — `required: ["id", "environment", "steps"]`, with
  // `additionalProperties: false`. The gate could therefore never open, the branch it guards was dead
  // on every real scenario, and the test that "proved" it passed only because its fixture was invalid
  // against the very schema it claimed to mirror.
  return /(?:"environment"\s*:|^\s*environment\s*:)/m.test(content) && /(?:"steps"\s*:|^\s*steps\s*:)/m.test(content);
}

const DISABLED = /@(?:Disabled|Ignore)\b(?:\s*\(\s*("(?:[^"\\]|\\.)*")\s*\))?/g;

/** A reason that names a ticket is a decision someone can follow up; a bare one is a disappearance. */
const TICKET = /[A-Z][A-Z0-9]+-\d+/;

const CATCH = /\bcatch\s*\(/g;

/**
 * Every bounded wait the SDK spells, normalised to milliseconds.
 *
 * `withinSeconds(n)` is the one the DSL actually uses — every awaiting step takes it, and it is the
 * only wait in most generated tests — and it was the one this list did not have. Blind to it, the
 * heuristic could not see the plainest form of "the test now waits ten times longer".
 */
const TIMEOUTS = [
  [/\bwithinSeconds\s*\(\s*(\d+)\s*\)/g, 1000],
  [/\bDuration\.ofMillis\s*\(\s*(\d+)\s*\)/g, 1],
  [/\bDuration\.ofSeconds\s*\(\s*(\d+)\s*\)/g, 1000],
  [/\bDuration\.ofMinutes\s*\(\s*(\d+)\s*\)/g, 60_000],
  [/["'](\d+)ms["']/g, 1],
  [/["'](\d+)s["']/g, 1000],
  [/["'](\d+)m["']/g, 60_000],
];

function count(text, patterns) {
  return patterns.reduce((total, pattern) => total + [...text.matchAll(pattern)].length, 0);
}

function timeouts(text) {
  const values = [];
  for (const [pattern, factor] of TIMEOUTS) {
    for (const match of text.matchAll(pattern)) values.push(Number(match[1]) * factor);
  }
  return values;
}

function finding(detector, severity, source, message, evidence) {
  return {
    finding: detector.finding,
    ruleId: detector.ruleId,
    severity,
    source,
    title: detector.title,
    message,
    evidence,
    fix: detector.fix,
  };
}

/**
 * What this change stopped checking.
 *
 * @param previous the artifact as it was, or '' when it is new (nothing to conceal)
 * @param next the artifact as it will be
 * @param kind 'java' | 'document' | …
 * @param detector the FAILURE_CONCEALMENT entry of the table, for its id and its fix
 */
export function concealment(previous, next, kind, detector, relativePath = '') {
  const findings = [];
  if (!previous) return findings;

  // A comment is not a check. Counting the raw text meant `assertThat(x)` → `// assertThat(x)` left
  // the count untouched, which is the whole of "make the red test green" in one keystroke — and the
  // same blindness let a `@Disabled` or a `catch` written inside a comment be reported as real.
  //
  // Decided by the file's SUFFIX rather than by `kind`, because `kind` is too coarse in exactly the
  // place it matters: `pom.xml` is a build file, and running Java comment rules over XML blanks from
  // the first `//` in an XPath or a URL to the end of the line — swallowing the very `"30s"` the
  // timeout heuristic reads — while leaving `<!-- … -->` untouched. Documents and reports are left
  // raw for the same reason: `//` inside `https://` is not a comment.
  //
  // `withoutComments` and not `codeOnly`, for every signal. Blanking literals as well would take the
  // ticket out of `@Disabled("ALFA-1234 …")` and the value out of `"30s"`; and an assertion hidden
  // inside a string literal is a contrivance, where an assertion hidden inside a comment is one
  // keystroke. The stricter projection stays where it is needed and cheap — the SQL sanction in
  // `scan.mjs`, which asks whether a call is code at all.
  const javaLike = /\.(?:java|gradle|gradle\.kts)$/i.test(relativePath.replace(/\.txt$/i, ''));
  const previousText = javaLike ? withoutComments(previous) : previous;
  const nextText = javaLike ? withoutComments(next) : next;

  const patterns = kind === 'document' && !isExecutableScenario(previous) ? undefined : ASSERTIONS[kind];
  if (patterns) {
    const before = count(previousText, patterns);
    const after = count(nextText, patterns);
    if (after < before) {
      findings.push(finding(detector, 'BLOCK', 'STATIC_SCAN',
        `проверок стало меньше: было ${before}, стало ${after}`,
        `assertions ${before}→${after}`));
    }
  }

  const disabledBefore = [...previousText.matchAll(DISABLED)].length;
  for (const match of nextText.matchAll(DISABLED)) {
    if (disabledBefore > 0) break;
    const reason = match[1] || '';
    const hasTicket = TICKET.test(reason);
    findings.push(finding(detector, hasTicket ? 'HIGH' : 'BLOCK', 'STATIC_SCAN',
      hasTicket
        ? `тест выключен со ссылкой на задачу: ${match[0]}`
        : `тест выключен без задачи: ${match[0]} — выключенный тест не отличим от отсутствующего`,
      match[0]));
  }

  const catchBefore = count(previousText, [CATCH]);
  const catchAfter = count(nextText, [CATCH]);
  if (catchAfter > catchBefore) {
    findings.push(finding(detector, 'HIGH', 'HEURISTIC',
      `появился catch: было ${catchBefore}, стало ${catchAfter} — перехват вокруг прогона сценария превращает падение в отчёт «прошло»`,
      `catch ${catchBefore}→${catchAfter}`));
  }

  // Only when the SAME waits got longer. A new step legitimately brings its own timeout, and calling
  // that concealment would fire on ordinary work — which is the one thing this finding must not do.
  const before = timeouts(previousText);
  const after = timeouts(nextText);
  if (before.length > 0 && before.length === after.length) {
    const grewBy = Math.max(...after) - Math.max(...before);
    if (grewBy > 0) {
      findings.push(finding(detector, 'HIGH', 'HEURISTIC',
        `таймаут вырос при том же числе ожиданий: ${Math.max(...before)}ms → ${Math.max(...after)}ms`,
        `timeout ${Math.max(...before)}→${Math.max(...after)}ms`));
    }
  }

  return findings.map((item) => ({ ...item, file: undefined }));
}
