// The same gate, in the format CI already understands.
//
// Nothing here is a new check. The detectors are the ones the write hook runs, and the point of the
// format is that they stop depending on a session existing at all: a pull request gets the findings
// as annotations whether or not anybody ran an agent, and a branch nobody generated tests on still
// answers for the ones already committed.
//
// Two properties of the kit's own reporting survive the translation, and they are the reason this is
// not a five-line JSON.stringify:
//
//   - a clean report from PART of the detector set is not a clean report. Every finding is declared
//     as a rule, and the one that cannot run in a full scan is declared DISABLED rather than omitted,
//     so a dashboard shows 17 of 18 instead of implying eighteen;
//   - a finding points at a LINE. SARIF without a region puts every annotation on line 1, which in a
//     large generated test is the same as not saying where. The line is derived from the evidence the
//     detector matched — the first occurrence, which is honest about being the first and not
//     necessarily the only one.

import { detectors, NOT_RUN_REASONS } from './scan.mjs';

const SARIF_SCHEMA = 'https://json.schemastore.org/sarif-2.1.0.json';

/** BLOCK stops a build; HIGH is a warning a person still has to read. */
function levelOf(severity) {
  return severity === 'BLOCK' ? 'error' : 'warning';
}

/**
 * Every detector as a SARIF rule, in the table's own order, so ruleIndex is stable across runs.
 *
 * `enabled` reflects THIS invocation rather than the table: finding 18 needs the artifact's previous
 * version, so it is off in a plain scan and on when `--against` supplied one. A rule that claimed to
 * be enabled while nothing ran it would be the same lie by a different route.
 */
function rules(notRun) {
  return detectors().detectors.map((detector) => ({
    id: detector.ruleId,
    name: detector.ruleId,
    shortDescription: { text: detector.title },
    fullDescription: { text: `Находка ${detector.finding}. ${detector.title}. Применяется к: ${detector.appliesTo}.` },
    help: { text: detector.fix },
    defaultConfiguration: {
      level: levelOf(detector.severity),
      // Not omitted — declared and switched off. The difference is the whole reporting discipline of
      // this kit: what did not run has to be visible, or a partial pass reads as a full one.
      enabled: !notRun.includes(detector.ruleId),
    },
    properties: {
      finding: detector.finding,
      source: detector.source,
      severity: detector.severity,
      ...(detector.limit === undefined ? {} : { limit: detector.limit }),
    },
  }));
}

/**
 * The 1-based line the evidence sits on, or 1 when it cannot be located.
 *
 * Deliberately the FIRST occurrence: a detector reports one finding per distinct evidence, not one
 * per occurrence, so pointing at the first is the truthful answer to "where does this start".
 */
function lineOf(content, evidence) {
  if (!content || !evidence) return 1;
  const index = content.indexOf(String(evidence).trim());
  return index < 0 ? 1 : content.slice(0, index).split('\n').length;
}

/**
 * A finding's file and line, from either shape the kit produces.
 *
 * The artifact scanner reports a path and separate evidence; the knowledge-base checks already know
 * their line and spell it `path:line`. One reader for both, because a CI run wants one file.
 */
function locationOf(finding, contents) {
  const raw = String(finding.file || '');
  const embedded = /^(.*):(\d+)$/.exec(raw);
  if (embedded !== null) {
    return { uri: embedded[1], line: Number(embedded[2]) };
  }
  return { uri: raw, line: lineOf(contents[raw], finding.evidence) };
}

/**
 * A SARIF 2.1.0 log for one run of the scanner.
 *
 * @param findings what the detectors reported
 * @param contents `{ path: content }` for the files scanned, so a finding can be placed on a line
 * @param notRun rule ids that did not run in this mode
 */
export function toSarif(findings, contents = {}, notRun = [], reasons = {}) {
  const declared = rules(notRun);
  const index = new Map(declared.map((rule, position) => [rule.id, position]));

  const results = findings.map((finding) => {
    const location = locationOf(finding, contents);
    return {
      ruleId: finding.ruleId,
      ruleIndex: index.has(finding.ruleId) ? index.get(finding.ruleId) : undefined,
      level: levelOf(finding.severity),
      message: { text: `${finding.message}\n→ ${finding.fix}` },
      locations: [{
        physicalLocation: {
          artifactLocation: { uri: location.uri },
          region: { startLine: location.line },
        },
      }],
      properties: { source: finding.source, evidence: finding.evidence },
    };
  });

  return {
    $schema: SARIF_SCHEMA,
    version: '2.1.0',
    runs: [{
      tool: {
        driver: {
          name: 'stand-guard',
          informationUri: 'https://stand-test.alfa.ru/kit',
          version: String(detectors().version || '1'),
          rules: declared,
        },
      },
      results,
      invocations: [{
        executionSuccessful: true,
        // The honest half of the report, in the one place SARIF has for it. A consumer reading only
        // `results` would conclude the whole table ran.
        // The reason travels with the notification, because "did not apply to this file" and "the
        // caller did not supply what it needs" are different facts and a dashboard that merges them
        // shows a gap where there is none — or hides one where there is.
        toolConfigurationNotifications: notRun.map((ruleId) => ({
          descriptor: { id: ruleId },
          level: 'note',
          message: {
            text: `находка не проверялась в этом прогоне: ${ruleId}`
              + (NOT_RUN_REASONS[reasons[ruleId]] === undefined ? '' : ` — ${NOT_RUN_REASONS[reasons[ruleId]]}`),
          },
        })),
      }],
    }],
  };
}
