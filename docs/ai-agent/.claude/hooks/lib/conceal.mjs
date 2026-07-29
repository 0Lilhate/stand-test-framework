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

/** What counts as an assertion, by artifact kind. Deliberately narrow: a miscount here is a false BLOCK. */
const ASSERTIONS = {
  java: [/\bassertThat\s*\(/g, /\bassertThatThrownBy\s*\(/g, /\bassertThatCode\s*\(/g,
    /\.expectStatus\s*\(/g, /\.expectBody\s*\(/g, /\.expectJsonPath\s*\(/g, /\.expectHeader\s*\(/g,
    /\.expectField\s*\(/g, /\.expectRow\s*\(/g, /\.expectMessage\s*\(/g],
  document: [/"assertions"\s*:/g, /^\s*assertions\s*:/gm, /"matcher"\s*:/g, /^\s*-?\s*matcher\s*:/gm],
};

const DISABLED = /@(?:Disabled|Ignore)\b(?:\s*\(\s*("(?:[^"\\]|\\.)*")\s*\))?/g;

/** A reason that names a ticket is a decision someone can follow up; a bare one is a disappearance. */
const TICKET = /[A-Z][A-Z0-9]+-\d+/;

const CATCH = /\bcatch\s*\(/g;

/** Every bounded wait the SDK spells, normalised to milliseconds. */
const TIMEOUTS = [
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
export function concealment(previous, next, kind, detector) {
  const findings = [];
  if (!previous) return findings;

  const patterns = ASSERTIONS[kind];
  if (patterns) {
    const before = count(previous, patterns);
    const after = count(next, patterns);
    if (after < before) {
      findings.push(finding(detector, 'BLOCK', 'STATIC_SCAN',
        `проверок стало меньше: было ${before}, стало ${after}`,
        `assertions ${before}→${after}`));
    }
  }

  const disabledBefore = [...previous.matchAll(DISABLED)].length;
  for (const match of next.matchAll(DISABLED)) {
    if (disabledBefore > 0) break;
    const reason = match[1] || '';
    const hasTicket = TICKET.test(reason);
    findings.push(finding(detector, hasTicket ? 'HIGH' : 'BLOCK', 'STATIC_SCAN',
      hasTicket
        ? `тест выключен со ссылкой на задачу: ${match[0]}`
        : `тест выключен без задачи: ${match[0]} — выключенный тест не отличим от отсутствующего`,
      match[0]));
  }

  const catchBefore = count(previous, [CATCH]);
  const catchAfter = count(next, [CATCH]);
  if (catchAfter > catchBefore) {
    findings.push(finding(detector, 'HIGH', 'HEURISTIC',
      `появился catch: было ${catchBefore}, стало ${catchAfter} — перехват вокруг прогона сценария превращает падение в отчёт «прошло»`,
      `catch ${catchBefore}→${catchAfter}`));
  }

  // Only when the SAME waits got longer. A new step legitimately brings its own timeout, and calling
  // that concealment would fire on ordinary work — which is the one thing this finding must not do.
  const before = timeouts(previous);
  const after = timeouts(next);
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
