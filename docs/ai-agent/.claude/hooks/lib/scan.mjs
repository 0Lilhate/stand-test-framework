// The detector engine: detectors.json in, findings out.
//
// Everything here is deterministic and reads only what it is handed. That is what lets the same
// function serve a PreToolUse hook (content about to be written), a whole-file sweep and a CI run
// without any of the three being able to disagree with the others.

import { readFileSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const HOOKS_DIR = dirname(dirname(fileURLToPath(import.meta.url)));  // .../.claude/hooks

let cachedTable = null;

/** The detector table, read once. */
export function detectors() {
  if (cachedTable === null) {
    cachedTable = JSON.parse(readFileSync(join(HOOKS_DIR, 'detectors.json'), 'utf8'));
  }
  return cachedTable;
}

/**
 * What kind of artifact a path is, which decides which detectors apply.
 *
 * Selection is by path rather than by content sniffing: a detector that guessed wrong about the kind
 * would apply Java rules to a JSON document and report nothing while looking like it checked.
 */
export function kindOf(path) {
  // A `.txt` piled on top of a real extension is the "nothing compiles this" marker the golden
  // corpus uses. The kind is the artifact's, not the storage form's.
  const relativePath = path.replace(/\.txt$/, '');
  const name = basename(relativePath);
  if (['build.gradle', 'build.gradle.kts', 'pom.xml'].includes(name)) return 'build';
  if (relativePath.endsWith('.java')) return 'java';
  if (/\.(json|ya?ml)$/.test(relativePath)) return 'document';
  return 'other';
}

function applies(detector, kind) {
  if (detector.implemented === false) return false;
  if (detector.appliesTo === 'any') return true;
  return detector.appliesTo === kind;
}

function finding(detector, message, evidence) {
  return {
    finding: detector.finding,
    ruleId: detector.ruleId,
    severity: detector.severity,
    source: detector.source,
    title: detector.title,
    message,
    evidence,
    fix: detector.fix,
  };
}

/** A document parsed as JSON, or null. YAML is left to the schema gate — this hook parses no YAML. */
function parseDocument(content) {
  try {
    const value = JSON.parse(content);
    return value !== null && typeof value === 'object' ? value : null;
  } catch {
    return null;
  }
}

function walkKeys(node, out) {
  if (Array.isArray(node)) {
    node.forEach((item) => walkKeys(item, out));
  } else if (node !== null && typeof node === 'object') {
    for (const [key, value] of Object.entries(node)) {
      out.push(key);
      walkKeys(value, out);
    }
  }
  return out;
}

function steps(document) {
  return document !== null && Array.isArray(document.steps) ? document.steps : [];
}

// ---------------------------------------------------------------------------------------------
// The SQL question, asked the way the SDK's own write-guard asks it.
// ---------------------------------------------------------------------------------------------

const SQL_LEADING = /^\s*(?:--[^\n]*\n|\/\*[\s\S]*?\*\/|\s)*([a-z]+)/i;

/** READ, WRITE-with-discriminator or neither. Mirrors SqlStatementClassifier's decision. */
function isPermittedRead(sql) {
  const match = SQL_LEADING.exec(sql);
  if (!match) return false;
  const keyword = match[1].toLowerCase();
  if (sql.includes(';') && sql.trim().replace(/;\s*$/, '').includes(';')) return false;
  if (keyword === 'select' || keyword === 'with') return true;
  if (['insert', 'update', 'delete', 'merge'].includes(keyword)) {
    // The write-guard's own condition: a write may run only when it binds the run discriminator.
    return /:testRunId\b/.test(sql) || /\btest_run_id\b/i.test(sql);
  }
  return false;
}

function extractAll(content, spec) {
  const found = [];
  const regex = new RegExp(spec.regex, spec.flags || 'g');
  let match;
  while ((match = regex.exec(content)) !== null) {
    found.push(match[1] !== undefined ? match[1] : match[2]);
    if (match[0] === '') regex.lastIndex += 1;
  }
  return found.filter((value) => value !== undefined);
}

// ---------------------------------------------------------------------------------------------
// One detector, one function. The dispatch below is by the shape a detector declares, so adding a
// detector to detectors.json needs code here only when it introduces a genuinely new shape.
// ---------------------------------------------------------------------------------------------

function runPatterns(detector, content, findings) {
  for (const pattern of detector.patterns || []) {
    const regex = new RegExp(pattern.regex, pattern.flags || 'g');
    const exemptKey = pattern.exemptKeyRegex ? new RegExp(pattern.exemptKeyRegex) : null;
    const exemptValue = pattern.exemptValueRegex ? new RegExp(pattern.exemptValueRegex) : null;
    const mutableType = pattern.mutableTypeRegex ? new RegExp(pattern.mutableTypeRegex) : null;
    const seen = new Set();
    let match;
    while ((match = regex.exec(content)) !== null) {
      if (match[0] === '') { regex.lastIndex += 1; continue; }

      // Shared-mutable-state carries its own question: a static FINAL field is a finding only when
      // the type it holds can change behind the reference. `final` binds the reference and says
      // nothing about the contents.
      if (mutableType) {
        const isFinal = match[1] !== undefined && match[1] !== null && /final/.test(match[1]);
        const type = (match[2] || '').trim();
        if (isFinal && !mutableType.test(type)) continue;
        const evidence = `${type} ${match[3] || ''}`.trim();
        if (seen.has(evidence)) continue;
        seen.add(evidence);
        findings.push(finding(detector, `static mutable field '${match[3]}' of type ${type}`, evidence));
        continue;
      }

      if (pattern.captureKey !== undefined) {
        const key = match[pattern.captureKey];
        const value = match[pattern.captureValue];
        if (!key || value === undefined) continue;
        if (value === '' || (exemptKey && exemptKey.test(key)) || (exemptValue && exemptValue.test(value))) continue;
        if (seen.has(key)) continue;
        seen.add(key);
        findings.push(finding(detector, `field '${key}' carries a value rather than a reference`, key));
        continue;
      }

      const evidence = match[1] !== undefined ? match[1] : match[0];
      if (exemptValue && exemptValue.test(evidence)) continue;
      if (seen.has(evidence)) continue;
      seen.add(evidence);
      findings.push(finding(detector, `${detector.title}: '${evidence.trim()}'`, evidence.trim()));
    }
  }
}

function runSql(detector, content, findings) {
  if (!detector.extract) return;
  for (const sql of extractAll(content, detector.extract)) {
    if (detector.rule === 'permittedRead' && !isPermittedRead(sql)) {
      findings.push(finding(detector, `the statement is not a permitted read: '${sql}'`, sql));
    }
    for (const pattern of detector.sqlPatterns || []) {
      if (new RegExp(pattern.regex, pattern.flags || 'g').test(sql)) {
        findings.push(finding(detector, `the statement calls a sleep function: '${sql}'`, sql));
      }
    }
  }
}

function runEnvironment(detector, content, findings, policy) {
  const allowed = policy.allowedEnvironments || [];
  for (const environment of new Set(extractAll(content, detector.extract))) {
    const lower = environment.toLowerCase();
    if ((detector.productionTokens || []).some((token) => new RegExp(`(^|[^a-z])${token}([^a-z]|$)`, 'i').test(lower))) {
      findings.push(finding(detector, `targets '${environment}', whose name is production-like`, environment));
    } else if (allowed.length > 0 && !allowed.includes(environment)) {
      findings.push(finding(detector, `targets '${environment}', which this run does not allow (allowed: ${allowed.join(', ')})`, environment));
    }
  }
}

function runKeys(detector, content, findings) {
  const document = parseDocument(content);
  if (document === null) return;
  const keys = new Set(walkKeys(document, []).map((key) => key.toLowerCase()));
  for (const key of detector.keys || []) {
    if (keys.has(key)) {
      findings.push(finding(detector, `the document carries the executable field '${key}'`, key));
    }
  }
}

function runTransport(detector, content, findings, kind) {
  if (kind === 'java') {
    const imported = extractAll(content, detector.imports);
    for (const [transport, prefixes] of Object.entries(detector.imports.groups)) {
      const matched = imported.filter((name) => prefixes.some((prefix) => name.startsWith(prefix)));
      if (matched.length > 0) {
        findings.push(finding(detector, `imports ${matched.join(', ')} and so reaches the transport itself (${transport})`, matched[0]));
      }
    }
  }
  if (kind === 'document') {
    const document = parseDocument(content);
    if (document === null) return;
    const keys = new Set(walkKeys(document, []));
    for (const [field, what] of Object.entries(detector.transportFields || {})) {
      if (keys.has(field)) {
        findings.push(finding(detector, `the document declares '${field}' and so configures the ${what} itself`, field));
      }
    }
  }
}

function runMarkers(detector, content, findings) {
  for (const [marker, what] of Object.entries(detector.markers || {})) {
    if (content.includes(marker)) {
      findings.push(finding(detector, `writes '${marker}', which ${what}`, marker));
    }
  }
}

function runDependencies(detector, content, findings, relativePath) {
  if (!(detector.buildFiles || []).includes(basename(relativePath))) return;
  const regex = new RegExp(detector.coordinate.regex, detector.coordinate.flags || 'g');
  const seen = new Set();
  let match;
  while ((match = regex.exec(content)) !== null) {
    const coordinate = `${match[1]}:${match[2]}`;
    if (seen.has(coordinate)) continue;
    seen.add(coordinate);
    if (!(detector.allowedPrefixes || []).some((prefix) => coordinate.startsWith(prefix))) {
      findings.push(finding(detector, `adds '${coordinate}', which is outside what the kit sanctions`, coordinate));
    }
  }
}

function runShapes(detector, content, findings) {
  for (const [what, spec] of Object.entries(detector.shapes || {})) {
    const match = new RegExp(spec.regex, spec.flags || 'g').exec(content);
    if (match) {
      findings.push(finding(detector, `carries something shaped like ${what} ('${match[0]}') — confirm it is synthetic`, match[0]));
    }
  }
}

function runTimeouts(detector, content, findings) {
  const document = parseDocument(content);
  if (document === null) return;
  const bounded = /^(?:[1-9][0-9]{0,4}ms|[1-9][0-9]{0,2}s|(?:[1-9]|[1-5][0-9]|60)m)$/;
  const needsTimeout = ['rest.expectEventually', 'kafka.expect', 'db.expectEventually', 'grpc.unary'];
  for (const step of steps(document)) {
    if (step === null || typeof step !== 'object') continue;
    const declared = step.timeout;
    if (declared === undefined) {
      if (needsTimeout.includes(step.type)) {
        findings.push(finding(detector, `step '${step.id}' is a ${step.type}, which waits, and declares no timeout`, step.id));
      }
    } else if (typeof declared !== 'string' || !bounded.test(declared)) {
      findings.push(finding(detector, `step '${step.id}' declares the timeout '${declared}', outside the bounded grammar`, String(declared)));
    }
  }
}

function runKafkaDiscriminator(detector, content, findings) {
  const document = parseDocument(content);
  if (document === null) return;
  for (const step of steps(document)) {
    if (step === null || typeof step !== 'object' || step.type !== 'kafka.expect') continue;
    const fromContext = step.correlation !== null && typeof step.correlation === 'object' && step.correlation.fromContext === true;
    const derivedKey = typeof step.key === 'string' && step.key.includes('${');
    if (!fromContext && !derivedKey) {
      findings.push(finding(detector, `step '${step.id}' expects a message with nothing that tells this run's messages from another's`, step.id));
    }
  }
}

/**
 * Every finding in one artifact.
 *
 * @param content what is about to be written, or what is on disk
 * @param relativePath the path it will have — decides the artifact kind
 * @param policy `{ allowedEnvironments: [] }`; an empty list checks production-likeness only
 */
export function scanArtifact(content, relativePath, policy = {}) {
  const kind = kindOf(relativePath);
  const findings = [];
  for (const detector of detectors().detectors) {
    if (!applies(detector, kind)) continue;
    if (detector.patterns) runPatterns(detector, content, findings);
    if (detector.rule === 'permittedRead') runSql(detector, content, findings);
    if (detector.productionTokens) runEnvironment(detector, content, findings, policy);
    if (detector.keys) runKeys(detector, content, findings);
    if (detector.imports || detector.transportFields) runTransport(detector, content, findings, kind);
    if (detector.markers) runMarkers(detector, content, findings);
    if (detector.buildFiles) runDependencies(detector, content, findings, relativePath);
    if (detector.shapes) runShapes(detector, content, findings);
    if (detector.rule === 'modelWalk' && detector.ruleId === 'UNBOUNDED_TIMEOUT') runTimeouts(detector, content, findings);
    if (detector.rule === 'modelWalk' && detector.ruleId === 'KAFKA_EXPECT_WITHOUT_DISCRIMINATOR') runKafkaDiscriminator(detector, content, findings);
  }
  return findings.map((item) => ({ ...item, file: relativePath }));
}

/** Which findings ran and which did not — a clean report from part of the set is not a clean report. */
export function gates() {
  const all = detectors().detectors;
  return {
    ran: all.filter((detector) => detector.implemented !== false).map((detector) => detector.ruleId),
    notRun: all.filter((detector) => detector.implemented === false).map((detector) => detector.ruleId),
  };
}

export function blocking(findings) {
  return findings.filter((item) => item.severity === 'BLOCK');
}

/** One finding as a person reads it: what, where, and what to do instead. */
export function render(item) {
  const mark = item.severity === 'BLOCK' ? '✖' : '⚠';
  const guess = item.source === 'HEURISTIC' ? ' [эвристика — подтвердите]' : '';
  return `${mark} ${item.ruleId} (находка ${item.finding})${guess}\n  ${item.file}: ${item.message}\n  → ${item.fix}`;
}
