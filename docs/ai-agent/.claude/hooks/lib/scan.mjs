// The detector engine: detectors.json in, findings out.
//
// Everything here is deterministic and reads only what it is handed. That is what lets the same
// function serve a PreToolUse hook (content about to be written), a whole-file sweep and a CI run
// without any of the three being able to disagree with the others.

import { existsSync, readFileSync } from 'node:fs';
import { concealment, isExecutableScenario } from './conceal.mjs';
import { codeOnly, withoutComments } from './source.mjs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const HOOKS_DIR = dirname(dirname(fileURLToPath(import.meta.url)));  // <bundle>/hooks

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
  // Lower-cased: an extension is not case-sensitive on the filesystems this kit runs on, and
  // `OrderTest.JAVA` reading as kind 'other' switched off every Java detector for that file while it
  // still compiled — one capital letter as a bypass.
  const relativePath = path.toLowerCase().replace(/\.txt$/, '');
  const name = basename(relativePath);
  if (['build.gradle', 'build.gradle.kts', 'pom.xml'].includes(name)) return 'build';
  if (relativePath.endsWith('.java')) return 'java';
  if (/\.(json|ya?ml)$/.test(relativePath)) return 'document';
  // Prose: the pipeline's own deliverables. The case analysis, the scenario design, the safety-review
  // report and the readiness report are all markdown, and they are the one kind of file that reaches
  // no stand — nothing loads them, no classpath carries them, and `Thread.sleep(` written in a
  // sentence does not wait. Named as a kind rather than left in 'other' because some detectors must
  // still run over them: a credential is committed wherever it is written.
  if (/\.(md|markdown|adoc|rst|txt)$/.test(relativePath)) return 'prose';
  return 'other';
}

/**
 * Whether a detector runs over this kind of artifact.
 *
 * `notOn` is the answer to a failure the kit hit on itself: a safety-review report that QUOTED its
 * own finding — `Thread.sleep(5000)`, `https://stand…` — was refused, and so was a case analysis that
 * cited the ticket it came from. Fourteen of the kit's own assets failed its own scan for the same
 * reason. A gate that refuses the report describing a violation is the gate people switch off, and it
 * takes the accurate findings with it.
 *
 * It is NOT the `exemptPaths` idea finding 1 records as rejected, and the difference is the whole
 * argument. That one switched a detector off by FILE NAME, for files whose job is to name addresses —
 * and `build.gradle.kts` names the corporate Nexus AND delivers system properties into the test JVM,
 * `application*.yml` matches fixtures too, so each exemption reopened a real delivery channel. This
 * one is by FILE FORMAT, and only for the detectors that ask "does this artifact deliver something to
 * a stand" — a question markdown cannot answer yes to. The detectors that ask "is something disclosed
 * here" (2 secrets, 13 masked secrets, 14 PII) keep running: those are about the file being committed,
 * which markdown does exactly as well as Java.
 */
/** Whether this kind of artifact is this detector's subject at all. */
function appliesToKind(detector, kind) {
  if ((detector.notOn || []).includes(kind)) return false;
  return detector.appliesTo === 'any' || detector.appliesTo === kind;
}

function applies(detector, kind) {
  // A finding that needs a previous version is not part of a single-artifact scan. It is not absent
  // either — `gates()` reports it as not run, and `scanDiff` is where it does run.
  if (detector.implemented === false || detector.requires !== undefined) return false;
  return appliesToKind(detector, kind);
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
// The declarative scenario, read whichever of its two spellings it arrived in.
// ---------------------------------------------------------------------------------------------

/**
 * A scalar as the scenario grammar spells them: quoted or bare, with a trailing comment removed.
 *
 * `true`/`false` are recognised because one of the two questions asked of this model —
 * `correlation.fromContext` — is a boolean, and a step whose discriminator is the string `"true"`
 * would otherwise read as having none.
 */
function yamlScalar(raw) {
  const text = raw.replace(/\s+#.*$/, '').trim().replace(/^["'](.*)["']$/, '$1');
  if (text === 'true') return true;
  if (text === 'false') return false;
  return text;
}

/** One `key: value` line into a map. Returns the key when the value is a nested block, else null. */
function yamlAssign(target, text) {
  const pair = /^([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(.*)$/.exec(text);
  if (pair === null) return null;
  if (pair[2].trim() === '') {
    target[pair[1]] = {};
    return pair[1];
  }
  target[pair[1]] = yamlScalar(pair[2]);
  return null;
}

/**
 * The `steps:` list of a YAML scenario, read by line.
 *
 * By line, and not by a YAML parser, for the reason `kb.mjs` gives about the same choice: a parser
 * here would be a second implementation of a contract the schema gate already owns, and one that
 * disagreed with it would be worse than none. What is needed is narrower than YAML — the ids, types,
 * timeouts, keys and the one nested `correlation:` block that findings 5 and 16 ask about — and every
 * shape this misses degrades toward an unparsed step, which is a missed finding rather than an
 * invented one.
 *
 * Nested list items (an `assertions:` block inside a step) are skipped rather than modelled: they are
 * deeper than the step's own dash, and neither question reaches into them.
 */
function yamlSteps(content) {
  const lines = content.split('\n');
  const opener = lines.findIndex((line) => /^\s*steps\s*:\s*$/.test(line));
  if (opener < 0) return [];
  const outer = lines[opener].length - lines[opener].trimStart().length;

  const found = [];
  let current = null;
  let itemIndent = null;
  let nested = null;
  for (let index = opener + 1; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.trim() === '' || /^\s*#/.test(line)) continue;
    const indent = line.length - line.trimStart().length;
    if (indent <= outer) break;
    const text = line.trimStart();

    if (text.startsWith('- ')) {
      if (itemIndent === null) itemIndent = indent;
      if (indent !== itemIndent) continue;
      current = {};
      found.push(current);
      nested = null;
      const block = yamlAssign(current, text.slice(2).trim());
      if (block !== null) nested = { key: block, indent };
      continue;
    }
    if (current === null) continue;
    if (nested !== null && indent > nested.indent) {
      yamlAssign(current[nested.key], text);
      continue;
    }
    nested = null;
    const block = yamlAssign(current, text);
    if (block !== null) nested = { key: block, indent };
  }
  return found;
}

/**
 * The steps of a declarative scenario, whichever spelling it arrived in — or none.
 *
 * `parseDocument` is `JSON.parse`, and `kindOf` calls every `.yaml` a document, so the three
 * model-walking findings (5 unbounded timeout, 7 script in a document, 16 kafka without a
 * discriminator) reported nothing at all on a YAML scenario while the report still said seventeen of
 * eighteen ran. The skill that produces these documents is named for YAML and ships a YAML template,
 * so the format the checks could not read was the format the checks were written for.
 *
 * The YAML branch is taken ONLY for a document that declares itself an executable scenario, on the
 * same reasoning as the concealment gate: `steps`, `type`, `key` and `code` are ordinary words in a
 * knowledge-base entry, a fixture and a registry, and a word search over those turns the ordinary work
 * of three skills into blocking findings.
 */
function scenarioSteps(content) {
  const document = parseDocument(content);
  if (document !== null) return steps(document);
  return isExecutableScenario(content) ? yamlSteps(content) : [];
}

/** Whether a YAML document should be asked the questions the JSON branch asks of a parsed model. */
function isYamlScenario(content) {
  return parseDocument(content) === null && isExecutableScenario(content);
}

/** Whether a key is declared anywhere in a YAML document, as a mapping key rather than as a value. */
function declaresKey(content, key) {
  return new RegExp(`^\\s*(?:-\\s+)?${key}\\s*:`, 'mi').test(content);
}

// ---------------------------------------------------------------------------------------------
// The SQL question, asked the way the SDK's own write-guard asks it.
// ---------------------------------------------------------------------------------------------

/**
 * The first word of a statement, past whatever leading comments and whitespace it carries.
 *
 * Stripped in a loop rather than matched by one regex. The regex it replaces —
 * `^\s*(?:--[^\n]*\n|\/\*[\s\S]*?\*\/|\s)*([a-z]+)` — nests a quantified alternation whose branches
 * can all match whitespace, so a statement that never reaches a letter makes the engine try every
 * partition of the prefix. Measured on the shipped version: 20 comment groups took 0.1s, 24 took 0.6s,
 * six-fold per four added. A file of a few hundred bytes therefore outruns the hook's 15-second
 * budget, the host kills it, and a killed hook exits non-zero — which the host reads as "errored, do
 * not block". A denial of service against the guard is a way to write anything at all.
 */
function leadingKeyword(sql) {
  let rest = sql;
  for (;;) {
    const trimmed = rest.replace(/^\s+/, '');
    if (trimmed.startsWith('--')) {
      const line = trimmed.indexOf('\n');
      if (line === -1) return null;
      rest = trimmed.slice(line + 1);
      continue;
    }
    if (trimmed.startsWith('/*')) {
      const close = trimmed.indexOf('*/', 2);
      if (close === -1) return null;
      rest = trimmed.slice(close + 2);
      continue;
    }
    const word = /^([a-z]+)/i.exec(trimmed);
    return word === null ? null : word[1];
  }
}

/** More than one statement in one string — refused before any other question is asked. */
function isMultiStatement(sql) {
  return sql.includes(';') && sql.trim().replace(/;\s*$/, '').includes(';');
}

/** The keywords core calls data-modifying. A `WITH` that embeds one is not a read, whatever it returns. */
const DATA_MODIFYING = /\b(?:INSERT|UPDATE|DELETE|MERGE|TRUNCATE|DROP|ALTER|CREATE|GRANT|REVOKE|CALL|EXEC|EXECUTE)\b/i;

/**
 * A READ, and nothing else. Mirrors `SqlStatementClassifier`'s decision on the same question.
 *
 * It used to answer a second question too — "a write is fine if it binds the run discriminator" — and
 * that blanket was wrong twice over. It permitted `MERGE`, which core does not classify at all (its
 * switch names SELECT, WITH, INSERT, UPDATE and DELETE; everything else is REJECTED) and which the
 * guardrail rules ban outright; and it permitted `INSERT … ON CONFLICT DO UPDATE` the moment the
 * statement mentioned `:testRunId`, although an upsert tail mutates pre-existing rows exactly as an
 * UPDATE does — core says so in as many words, the `db.write` sanction below refuses it, and the
 * safety checklist calls it a BLOCK. The blanket ran FIRST, so neither refusal was ever reached.
 *
 * A write is now permitted only where the SDK's own shapes permit it: through {@link isSanctionedWrite},
 * which reads the step that carries the statement. That is also what makes `db.seed` answerable at all
 * — a seed's INSERT binds `:testRunId` by construction, so under the blanket it passed whether or not
 * it declared the tag column, and the rule that the declared column must exist had nothing enforcing it.
 *
 * `SELECT … INTO` and a data-modifying `WITH` are rejected here for core's reason: both write, and both
 * arrive spelled as a read. Literals are blanked first so that a table called `'into'` in a string
 * cannot decide it.
 */
function isPermittedRead(sql) {
  const leading = leadingKeyword(sql);
  if (leading === null || isMultiStatement(sql)) return false;
  const keyword = leading.toLowerCase();
  if (keyword !== 'select' && keyword !== 'with') return false;
  const skeleton = sql.replace(/'[^']*'/g, "''");
  if (/\bINTO\b/i.test(skeleton)) return false;
  return !(keyword === 'with' && DATA_MODIFYING.test(skeleton));
}

/**
 * Which sanctioned step this statement sits in, if any: the nearest preceding `DbStep.cleanup(` or
 * `DbStep.write(` that has not already closed with `.build()`, and where that step ends.
 *
 * The factory is the anchor rather than the companion call, because the factory is what DECIDES the
 * shape — and because the SDK refuses to build either step without its key, so a statement provably
 * inside one has already been through that check. The companion is still required afterwards; two
 * conditions cost nothing and the second is what a reader looks for.
 */
function sanctionedStep(code, from, to) {
  const closes = [code.indexOf('.build()', to), code.indexOf(';', to)].filter((at) => at !== -1);
  const end = closes.length > 0 ? Math.min(...closes) : code.length;
  const factory = /DbStep\s*\.\s*(seed|cleanup|write)\s*\(/g;
  let step = null;
  let match;
  while ((match = factory.exec(code)) !== null) {
    if (match.index >= from) break;
    // The factory has to be part of the SAME expression as the statement. A `;` between them means it
    // is not: `var cleanup = DbStep.cleanup("db");` leaves a builder open in a local variable, and a
    // window that ignored the terminator let it vouch for every statement written afterwards —
    // including a raw `jdbc.query("DELETE FROM public.customers")` that has nothing to do with it.
    const between = code.slice(match.index, from);
    if (!between.includes('.build()') && !between.includes(';')) step = { factory: match[1], start: match.index, end };
  }
  return step;
}

/**
 * Whether the statement at this offset is the ONLY one the step carries.
 *
 * A step sanctions its own SQL and nothing else. The window that finds the factory is lexical, so any
 * second string that looks like a statement — a nested `helper.query("DELETE FROM public.customers")`
 * passed to `.param(...)`, a second `.sql(...)` in the same chain — used to fall inside it and take
 * the sanction for free, while the SDK appends `WHERE … = :testRunId` to one statement only. The
 * cheapest correct answer is to refuse to sanction an ambiguous step at all: with two statements in
 * it, neither is provably the one the write-guard would scope.
 */
function isTheStepsOwnStatement(detector, code, step, from) {
  const inside = extractOccurrences(code.slice(step.start, step.end), detector.extract)
    .filter((occurrence) => looksLikeStatement(occurrence.value));
  return inside.length === 1 && step.start + inside[0].from === from;
}

/**
 * Whether the SDK's own condition for this write is declared in the step that carries it.
 *
 * `db.cleanup` and `db.write` are sanctioned writes that no single statement can prove: a bare
 * `DELETE FROM schema.table` is scoped by the `WHERE` the SDK appends from `whereTestRunId`, and an
 * INSERT into a table with no marker column is undone by the primary key it names in `identifiedBy`.
 * Read without that neighbour, both look exactly like the destructive SQL this finding exists to
 * refuse — which is why the kit's own template could not be written through the kit's own hook.
 *
 * Java only. The declarative format has neither step, so a write there is a write, and a window that
 * found no `.build()` in a JSON document would otherwise have run to the end of the file.
 */
function isSanctionedWrite(detector, sql, content, from, to, kind) {
  if (kind !== 'java' || isMultiStatement(sql)) return false;
  const code = codeOnly(content);
  const step = sanctionedStep(code, from, to);
  if (step === null || !isTheStepsOwnStatement(detector, code, step, from)) return false;
  return (detector.sanctioned || []).some((rule) => rule.factory === step.factory
    && new RegExp(rule.statement, 'i').test(sql)
    && !new RegExp(rule.refuses, 'i').test(sql)
    // `requires` is what the statement itself must carry, as against `companion`, which is what the
    // step around it must declare. A seed needs both: the reserved bind IN the INSERT and the
    // `taggedByTestRunId` beside it — and the write-guard fails closed at run time when the declared
    // column is not the one bound, which is the case no static rule here can decide.
    && (rule.requires === undefined || new RegExp(rule.requires, 'i').test(sql))
    && new RegExp(rule.companion).test(code.slice(from, step.end)));
}

/**
 * Whether a captured value is a NAME rather than something to ask the SQL classifier about.
 *
 * The anchor has to stay on the field name — a step id like `create-request` starts with CREATE, and
 * a gate that calls an id a DDL statement is one people switch off. But `.query(` in Java is not a
 * field: it is `DbStep.query(<datasource alias>)` and `RestStep…query(k, v)`, and feeding those to
 * the classifier made the kit's own crib unwritable.
 *
 * So the test is for a plain identifier, and deliberately not for "starts with a SQL keyword". That
 * was the first attempt, and it inverted the detector's whole policy from deny-by-default to
 * allow-by-default: everything outside the keyword list — a DELETE behind a leading SQL comment,
 * `DO $$ … $$`, `LOCK TABLE`, `VACUUM FULL`, `REFRESH MATERIALIZED VIEW` — stopped being asked about
 * at all, and the sleep-function check further down the same loop went with it. An alias has no
 * whitespace and no comment; anything else goes to `isPermittedRead`, which already strips leading
 * comments and refuses whatever it does not recognise.
 */
const PLAIN_NAME = /^[A-Za-z_][A-Za-z0-9_.-]*$/;

/*
 * A single-word statement — `SHUTDOWN`, `VACUUM`, `CHECKPOINT` — is therefore not asked about, and
 * that gap is deliberate. Naming those words as statements was tried and cost more than it bought:
 * `RestStep…query("commit", …)` and `query("analyze", …)` are ordinary REST query parameters, and a
 * detector that calls them destructive SQL fires on ordinary work in a project it was installed to
 * protect. Nothing this hook can see distinguishes the two, because the difference is in the method
 * being called, not in the string. The SDK has no way to send a bare `VACUUM` either: `DbStep.sql`
 * goes through the write-guard, which refuses anything that is not a permitted read.
 */
function looksLikeStatement(sql) {
  return !PLAIN_NAME.test(sql.trim());
}

/**
 * Every match of an extraction spec, with the span it occupied — some questions need the neighbours.
 *
 * The value is the FIRST group that participated, whichever alternative matched. Reading exactly
 * `match[1] ?? match[2]` worked while every spec had two spellings and silently capped it there: the
 * third alternative — SQL in a Java text block — captured into group 3, `extractOccurrences` returned
 * `undefined`, and the statement was dropped before any detector saw it. A spec that grows a spelling
 * must not have to grow this function too.
 */
function extractOccurrences(content, spec) {
  const found = [];
  const regex = new RegExp(spec.regex, spec.flags || 'g');
  let match;
  while ((match = regex.exec(content)) !== null) {
    const value = match.slice(1).find((group) => group !== undefined);
    if (value !== undefined) found.push({ value, from: match.index, to: regex.lastIndex });
    if (match[0] === '') regex.lastIndex += 1;
  }
  return found;
}

function extractAll(content, spec) {
  return extractOccurrences(content, spec).map((occurrence) => occurrence.value);
}

// ---------------------------------------------------------------------------------------------
// One detector, one function. The dispatch below is by the shape a detector declares, so adding a
// detector to detectors.json needs code here only when it introduces a genuinely new shape.
// ---------------------------------------------------------------------------------------------

function runPatterns(detector, content, findings) {
  const raw = content;
  const projected = withoutComments(content);
  for (const pattern of detector.patterns || []) {
    const haystack = pattern.projection === 'withoutComments' ? projected : raw;
    const regex = new RegExp(pattern.regex, pattern.flags || 'g');
    const exemptKey = pattern.exemptKeyRegex ? new RegExp(pattern.exemptKeyRegex) : null;
    const exemptValue = pattern.exemptValueRegex ? new RegExp(pattern.exemptValueRegex) : null;
    const mutableType = pattern.mutableTypeRegex ? new RegExp(pattern.mutableTypeRegex) : null;
    const seen = new Set();
    let match;
    while ((match = regex.exec(haystack)) !== null) {
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

function runSql(detector, content, findings, kind) {
  if (!detector.extract) return;
  for (const { value: sql, from, to } of extractOccurrences(content, detector.extract)) {
    if (!looksLikeStatement(sql)) continue;
    if (!isPermittedRead(sql) && !isSanctionedWrite(detector, sql, content, from, to, kind)) {
      findings.push(finding(detector, `the statement is not a permitted read: '${sql}'`, sql));
    }
  }
}

/** The extraction spec that finds SQL statements. It belongs to the permitted-read detector, once. */
function sqlExtraction() {
  const owner = detectors().detectors.find((detector) => detector.rule === 'permittedRead');
  return owner === undefined || owner.extract === undefined ? null : owner.extract;
}

/**
 * Patterns asked of the SQL a step carries, rather than of the file.
 *
 * Finding 6 declares `sqlPatterns` and says of them "применяется к тому, что извлёк detector 3". The
 * table said it; nothing ran it. `runSql` was reached only through `rule === 'permittedRead'`, which
 * is finding 3's dispatch key, and it bailed on the first line for want of an `extract` that finding 6
 * has no reason to carry — so the ban on `pg_sleep`/`waitfor`/`dbms_lock`, which the guardrail rules
 * list as a BLOCK and `ForbiddenOperation` backs at run time, was dead in the scanner from the start.
 * Both a plain `.sql("SELECT pg_sleep(5)")` and the same statement in a JSON document passed clean.
 *
 * De-duplicated by statement for the same reason `runPatterns` de-duplicates by evidence: one sleep
 * written once is one finding, however many times the extractor sees it.
 */
function runSqlPatterns(detector, content, findings) {
  const extract = sqlExtraction();
  if (extract === null) return;
  const seen = new Set();
  for (const { value: sql } of extractOccurrences(content, extract)) {
    if (!looksLikeStatement(sql) || seen.has(sql)) continue;
    for (const pattern of detector.sqlPatterns) {
      if (new RegExp(pattern.regex, pattern.flags || 'g').test(sql)) {
        seen.add(sql);
        findings.push(finding(detector, `the statement calls a sleep function: '${sql}'`, sql));
        break;
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
  const yaml = isYamlScenario(content);
  if (document === null && !yaml) return;
  const keys = document === null ? null : new Set(walkKeys(document, []).map((key) => key.toLowerCase()));
  for (const key of detector.keys || []) {
    if (keys === null ? declaresKey(content, key) : keys.has(key)) {
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
    const yaml = isYamlScenario(content);
    if (document === null && !yaml) return;
    const keys = document === null ? null : new Set(walkKeys(document, []));
    for (const [field, what] of Object.entries(detector.transportFields || {})) {
      // The YAML branch is scenario-only on purpose: `bootstrap-servers` is the environment
      // registry's own vocabulary, and /stand-test-generate-env writes that file by design.
      if (keys === null ? declaresKey(content, field) : keys.has(field)) {
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

/**
 * The nearest end of a builder chain opened at `factoryAt`: the `.build()` or the statement `;`.
 *
 * The window is lexical and deliberately narrow. A `.build()` anywhere after the factory closes its
 * own chain, and a `;` means the chain was assigned to a local (and so never built) — in both cases
 * the search for the companion stops. A second factory whose chain happens to follow is not part of
 * the window: the guardrail is about a step declaring its own bound before it is built, and a step
 * that assigns the builder to a variable and calls `.role(...)` later has no static proof to offer.
 */
function builderWindow(content, from) {
  const closes = [content.indexOf('.build()', from), content.indexOf(';', from)].filter((at) => at !== -1);
  return closes.length > 0 ? Math.min(...closes) : content.length;
}

/**
 * A UI step that must carry a companion call before it is built: `ui.expectEventually(...)` must
 * bound its wait with `within(...)`/`withinSeconds(...)` (U17), `ui.login(...)` must name its role
 * with `.role(...)` (U5). The factory is the anchor; the companion is sought in the same chain.
 *
 * Factory and companion come from the detector so the same walk serves both. Matching over the
 * projected code (comments and string literals blanked) keeps a comment like "no within(...) here"
 * from vouching for a missing call — the pattern that funded the sanctioned-db-writes machinery.
 */
function runUiChain(detector, content, findings, kind) {
  if (kind !== 'java' || !detector.factory || !detector.companion) return;
  const code = codeOnly(content);
  const factory = new RegExp(detector.factory, 'g');
  const companion = new RegExp(detector.companion, 'g');
  let match;
  while ((match = factory.exec(code)) !== null) {
    const end = uiWindow(code, match.index + match[0].length);
    const window = code.slice(match.index + match[0].length, end);
    companion.lastIndex = 0;
    if (!companion.test(window)) {
      findings.push(finding(detector, `step '${match[0]}' does not declare its required companion before build`, match[0]));
    }
  }
}

/** One window for both UI-chain factories: this chain's own end, ignoring any later chain that opens. */
function uiWindow(code, from) {
  let depth = 0;
  for (let at = from; at < code.length; at += 1) {
    const ch = code[at];
    if (ch === '(') depth += 1;
    else if (ch === ')' && depth > 0) depth -= 1;
    else if (depth === 0 && (ch === ';')) return at;
    else if (depth === 0 && code.startsWith('.build()', at)) return at;
  }
  return code.length;
}

/**
 * A `UiLocator.*` constant that escaped its Page Object (U2). A Java file is that Page Object's home
 * only when its package declarations say so (`package … .ui.pages;` or `…ui.pageobject`) or when the
 * path places it under a `ui/pages` tree. A `UiLocator.*` factory call in any other Java file is
 * a locator living outside the Page Object the rules require, and every detection needs to be above
 * the code-with-comments projection so a commented-out locator is not read as the real one.
 */
function runUiLocator(detector, content, findings, relativePath) {
  const code = withoutComments(content);
  const hasLocator = /UiLocator\.testId|UiLocator\.role|UiLocator\.label|UiLocator\.css|UiLocator\.text|UiLocator\.byAttribute/.test(code)
    || /\bUiLocator\./.test(code);
  if (!hasLocator) return;
  const declaresPageObjectPackage = /^\s*package\s+[\w.]*\.?ui\.(?:pages|pageobject)[\w.]*\s*;/m.test(code);
  const sitsInPagesTree = /(?:^|[/\\.])ui[/\\]pages[/\\]/.test(relativePath);
  const isNamedPageObject = /page[-_]object/i.test(relativePath);
  if (declaresPageObjectPackage || sitsInPagesTree || isNamedPageObject) return;
  const regex = /\bUiLocator\s*\.\s*(\w+)\s*\(/g;
  let m;
  while ((m = regex.exec(code)) !== null) {
    findings.push(finding(detector, `UiLocator.${m[1]}(...) outside a Page Object (no ui.pages/ pageobject package and no ui/pages path)`, m[0]));
  }
}

/**
 * `${…}` where nothing resolves it (U9): inside a `UiStep.open(...)` path, or inside the expected
 * value of a UI assertion. Both factories and assert builders are sought over code-with-comments so
 * that `<script>` samples and prose mentioning `…` cannot vouch. Values that are legitimately
 * resolved (`${testRunId}`, captures) are preserved only when the resolved context says so; an expect
 * whose expected value carries a literal `${…}` is a test that compares against the un-resolved
 * string and fails — the rule the golden test itself explains in a comment.
 */
function runUiTemplate(detector, content, findings) {
  const code = withoutComments(content);
  const path = /\bUiStep\s*\.\s*open\s*\(\s*"[^"]*"\s*,\s*"(?=[^"]*\$\{)[^"]*"/g;
  let m;
  while ((m = path.exec(code)) !== null) {
    findings.push(finding(detector, `ui.open path carries '${stripQuotes(m[0])}' — a ${'${'+'…}'} the SDK will not resolve`, stripQuotes(m[0])));
  }
  const asserts = /\bassert(?:Text|Value|TextContains|TextMatches|Attribute|Property)\s*\([^;]*\$\{/g;
  let a;
  while ((a = asserts.exec(code)) !== null) {
    findings.push(finding(detector, `assertion expected value carries '${a[0]}' — a ${'${...}'} is not resolved in expected values`, a[0]));
  }
}

function stripQuotes(text) {
  return text.replace(/^"+|"+$/g, '').trim();
}

/**
 * A production address in the discovery/generation reports (U3, report half). The existing
 * HARDCODED_STAND_URL deliberately does not run over prose — a stage-8 report quoting a found address
 * must be writable, and finding 1's `$notOn: prose` records that decision. This is a NARROWER rule,
 * not a waiver of it: it applies only to files whose basename matches `Ui*Report.md`, the reports the
 * UI gate writes, and it re-uses the address shapes finding 1 knows. A `Ui*Report.md` must never
 * contain a live address whether or not a stage-8 read wants to quote it — the address is curated
 * behind the registry alias, and a report that reproduces it becomes a delivery channel.
 */
function runReportAddress(detector, content, findings, relativePath) {
  const name = basenamePosix(relativePath);
  if (!/^Ui.*Report\.md$/i.test(name)) return;
  const shapes = [
    /\b(?:https?|grpc|amqp|mongodb|redis):\/\/[^\s"'<>)\]}]*/gi,
    /\bjdbc:[a-z0-9]+:[^\s"'<>)\]}]*/gi,
    /\b(?:[a-z0-9-]+\.)+[a-z][a-z0-9-]*:\d{2,5}\b/gi,
  ];
  const seen = new Set();
  for (const shape of shapes) {
    let m;
    while ((m = shape.exec(content)) !== null) {
      if (seen.has(m[0])) continue;
      seen.add(m[0]);
      findings.push(finding(detector, `address '${m[0]}' reproduced in ${name} — the base address lives in the registry, never here`, m[0]));
    }
  }
}

function basenamePosix(path) {
  return path.split(/[\\/]/).pop() || path;
}

// ---------------------------------------------------------------------------------------------
// Finding 26 — UI_GENERATION_REPORT_INCOMPLETE (gate U16, UITG-F006).
// ---------------------------------------------------------------------------------------------

/**
 * The generation report's eight sections, and the snapshot that makes KPI-4 observable (gate U16).
 *
 * Two halves, and they fail for different reasons. The STRUCTURE half counts the eight numbered
 * headings — by NUMBER, not by wording, because the section titles get rewritten per scenario and a
 * detector demanding the template's exact prose would refuse legitimate reports. That makes it a
 * lower bound by construction: a heading with nothing under it passes here and is caught by the
 * human read at stage 7. Saying so is the point — the checklist records the gap rather than letting
 * a green scan read as a filled report.
 *
 * The SNAPSHOT half is the one with teeth. Without a preserved original there is nothing to diff the
 * merged file against, so KPI-4 is not merely imprecise — it is unobservable, and no later work
 * recovers it. The copies under `original/` a project may rule out by recorded decision; the hashes
 * may not, which is why the file checked is `original.sha256` and not the directory.
 *
 * Existence is resolved against the report's own directory first and the working directory second. A
 * report scanned outside its tree (a pre-write hook handed a bare name) can only be checked for the
 * mention — reported as a limit in the table, never as a check that passed.
 */
function runUiGenerationReport(detector, content, findings, relativePath) {
  const name = basenamePosix(relativePath);
  if (!/^Ui.*GenerationReport\.md$/i.test(name)) return;

  const required = detector.sections || 8;
  const missing = [];
  for (let section = 1; section <= required; section++) {
    if (!new RegExp(`^#{1,6}\\s*${section}\\.`, 'm').test(content)) missing.push(section);
  }
  if (missing.length > 0) {
    findings.push(finding(detector,
      `отчёт генерации не несёт секци${missing.length === 1 ? 'ю' : 'и'} ${missing.join(', ')} из ${required}: неполный отчёт выдаётся человеку как полный`,
      `sections missing: ${missing.join(',')}`));
  }

  const hashFile = detector.snapshotHashFile || 'original.sha256';
  const reference = new RegExp(`([^\\s"'\`<>()\\[\\]]*${hashFile.replace('.', '\\.')})`).exec(content);
  if (reference === null) {
    findings.push(finding(detector,
      `секция 8 не называет ${hashFile}: без сохранённого снимка исходной генерации KPI-4 нечем измерить — диффить не с чем`,
      `no ${hashFile}`));
    return;
  }

  const referenced = reference[1];
  const reportDirectory = relativePath.includes('/') || relativePath.includes('\\')
    ? dirname(relativePath)
    : '.';
  const candidates = [join(reportDirectory, referenced), referenced];
  if (!candidates.some((candidate) => existsSync(candidate))) {
    findings.push(finding(detector,
      `секция 8 ссылается на '${referenced}', которого на диске нет: снимок обязан быть записан ДО того, как отчёт на него сошлётся`,
      referenced));
  }
}

// ---------------------------------------------------------------------------------------------
// Finding 25 — UI_DISCOVERY_PARITY (gate U1, UITG-S021).
// ---------------------------------------------------------------------------------------------

/**
 * A locator expression as the discovery report spells it: `<strategy>=<value>`.
 *
 * The report's "Chosen locator" column is the canonical side (see ui-discovery-report-template.md):
 * `testId=request-status`, `role=button:Отправить`, `label=Тема`. The same expression is written in
 * Java as `UiLocator.testId("request-status")` etc. Normalising to one surface is what lets the two
 * be compared at all — a tester is not a table of strategy+operand, so nothing else does.
 */
function normaliseLocator(strategy, value, accessibleName) {
  if (strategy === 'role') {
    return `role=${value}:${accessibleName}`;
  }
  return `${strategy}=${value}`;
}

/**
 * The locators a discovery report observes, as a canonical set.
 *
 * The report is a fixed-shape markdown document (SP003 §7.2): an "Elements observed" table whose
 * columns include "Chosen locator", holding the adopted `<strategy>=<value>`. The column is located
 * by its HEADER rather than by a hard-coded index, so a column added before it (a new observed rung)
 * does not silently shift the parse: the header is the template's contract, and this reads it where
 * it actually is. A row whose chosen locator is `—` (nothing chosen) is an element discovery chose
 * not to address — it cannot vouch for a Java locator and is read as absent. Every row this misses
 * degrades toward an empty set, which is a missed finding rather than an invented one.
 *
 * @param report the raw markdown of a UiDiscoveryReport
 * @returns a Set of canonical `<strategy>=<value>` strings
 */
function discoveryLocatorSet(report) {
  const chosen = new Set();
  if (report === null || report === undefined) return chosen;
  let chosenIndex = -1;
  const lines = report.split('\n');
  for (const line of lines) {
    if (/^\s*\|/.test(line) === false) continue;
    const cells = splitRow(line);
    if (cells.some((cell) => /chosen\s*locator/i.test(cell))) {
      chosenIndex = cells.findIndex((cell) => /chosen\s*locator/i.test(cell));
      break;
    }
  }
  if (chosenIndex < 0) return chosen;
  for (const line of lines) {
    const cell = /^\s*\|\s*\d+\s*\|([^\n]*)\|/.exec(line);
    if (cell === null) continue;
    const segments = cell[1].split('|').map((segment) => segment.trim());
    const chosenCell = segments[chosenIndex - 1];
    if (chosenCell === undefined || chosenCell === '' || chosenCell === '—') continue;
    chosen.add(chosenCell.replace(/^`|`$/g, ''));
  }
  return chosen;
}

/** Splits one markdown table row into the text of its cells, the leading `#` column dropped. */
function splitRow(line) {
  const trimmed = line.trim();
  if (!trimmed.startsWith('|') || !trimmed.endsWith('|')) return [];
  return trimmed.slice(1, -1).split('|').map((cell) => cell.trim());
}

/**
 * Every `UiLocator.*` constant in a Page Object, as its canonical expression.
 *
 * Matched over code-with-comments blanked of comments and string literals there is NOTHING here that
 * needs the commented-out projection: a locator in a comment is not a locator, and quoting a locator
 * in a javadoc is quoting the rule, not breaking it. String-literals are PRESERVED by
 * `withoutComments`, which is exactly right — the operand lives in a string. The call shapes are
 * those the SDK's factories spell (UiLocator.java): testId/role/label/text/css. A `UiLocator.` call
 * whose factory these do not match (for example a future one) is not read as a locator at all — an
 * unknown strategy must not silently vouch for nothing, and a future factory that this fails to check
 * is a found gap, not a false pass.
 */
export function pageLocators(content) {
  const code = withoutComments(content);
  const found = [];
  const regex = /\bUiLocator\s*\.\s*(testId|role|label|text|css)\s*\(\s*"((?:[^"\\]|\\.)*)"(?:\s*,\s*"((?:[^"\\]|\\.)*)")?\s*\)/g;
  let match;
  while ((match = regex.exec(code)) !== null) {
    const factory = match[1];
    const value = match[2];
    if (value === undefined || value === '') continue;
    const name = match[3];
    if (factory === 'role' && (name === undefined || name === '')) continue;
    found.push({ factory, value, name, expression: normaliseLocator(factory, value, name) });
  }
  return found;
}

/**
 * Gate U1 — the invented locator (UITG-S021). A Page Object locator must trace to a row of the
 * discovery report; a locator the report does not say it observed is a candidate for a fabrication
 * (BR-03, RISK-03). The report is a different artifact and is supplied the same way the environment
 * allowlist is — as policy (`policy.discovery`), named by the caller with `--discovery <path>`. If
 * that name is missing from disk, the scan reports the absence as a BLOCK: a generation claiming
 * discovery evidence it cannot point at is not accepted. Presence is proven, never by the case text
 * or the KB — those are not the DOM, and only the report records what discovery actually observed.
 */
function runUiParity(detector, content, findings, policy) {
  // Parity is a REQUESTED check, not a background one: a single-file scan of an unrelated Java file
  // has no discovery side to compare and must stay quiet. The caller asks for it by naming the report
  // (`--discovery <path>`); if what it names does not exist, that is the negative the gate exists to
  // make loud — a generation claiming discovery evidence it cannot point at.
  const requested = policy !== null && policy !== undefined;
  if (!requested) return;
  if (policy.discoveryMissing === true) {
    findings.push(finding(detector,
      'заявлен отчёт разведки (--discovery), которого на диске нет: локатор без discovery-доказательства не принимается — генерация без разведки не проходит',
      'missing UiDiscoveryReport'));
    return;
  }
  const report = policy.discovery;
  if (report === null || report === undefined || report === '') return;
  const chosen = discoveryLocatorSet(report);
  for (const locator of pageLocators(content)) {
    if (!chosen.has(locator.expression)) {
      findings.push(finding(detector,
        `локатор '${locator.expression}' не прослеживается до строки UiDiscoveryReport.md (колонка «Chosen locator»)`,
        locator.expression));
    }
  }
}

/**
 * Every dependency coordinate a build file declares.
 *
 * Two spellings rather than one, because `pom.xml` was named in `buildFiles` from the start and could
 * never match: the only extraction spec was Gradle's quoted `"group:artifact:version"`, and Maven
 * spells a coordinate as two sibling elements. A Maven consumer therefore got a detector that reported
 * nothing while looking exactly like one that had checked — the shape of dead branch this table calls
 * worse than an absent one.
 *
 * `within` is what keeps the Maven spelling honest. A pom names a `groupId` for the project itself and
 * another for its parent, and reading those as dependencies would report every Maven project as
 * carrying two unsanctioned ones. So the search is scoped to `<dependency>` blocks first.
 */
function runDependencies(detector, content, findings, relativePath) {
  if (!(detector.buildFiles || []).includes(basename(relativePath).toLowerCase())) return;
  const specs = Array.isArray(detector.coordinate) ? detector.coordinate : [detector.coordinate];
  const seen = new Set();
  for (const spec of specs) {
    const scopes = spec.within === undefined
      ? [content]
      : extractAll(content, { regex: spec.within, flags: 'g' });
    for (const scope of scopes) {
      const regex = new RegExp(spec.regex, spec.flags || 'g');
      let match;
      while ((match = regex.exec(scope)) !== null) {
        const coordinate = `${match[1]}:${match[2]}`;
        if (seen.has(coordinate)) continue;
        seen.add(coordinate);
        if (!(detector.allowedPrefixes || []).some((prefix) => coordinate.startsWith(prefix))) {
          findings.push(finding(detector, `adds '${coordinate}', which is outside what the kit sanctions`, coordinate));
        }
      }
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
  const bounded = /^(?:[1-9][0-9]{0,4}ms|[1-9][0-9]{0,2}s|(?:[1-9]|[1-5][0-9]|60)m)$/;
  const needsTimeout = ['rest.expectEventually', 'kafka.expect', 'db.expectEventually', 'grpc.unary'];
  for (const step of scenarioSteps(content)) {
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
  for (const step of scenarioSteps(content)) {
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
    if (detector.rule === 'permittedRead') runSql(detector, content, findings, kind);
    if (detector.sqlPatterns) runSqlPatterns(detector, content, findings);
    if (detector.productionTokens) runEnvironment(detector, content, findings, policy);
    if (detector.keys) runKeys(detector, content, findings);
    if (detector.imports || detector.transportFields) runTransport(detector, content, findings, kind);
    if (detector.markers) runMarkers(detector, content, findings);
    if (detector.factory && detector.companion) runUiChain(detector, content, findings, kind);
    if (detector.rule === 'uiLocatorOutsidePages') runUiLocator(detector, content, findings, relativePath);
    if (detector.rule === 'uiTemplateInOpenOrAssert') runUiTemplate(detector, content, findings);
    if (detector.rule === 'uiReportAddress') runReportAddress(detector, content, findings, relativePath);
    if (detector.rule === 'uiGenerationReport') runUiGenerationReport(detector, content, findings, relativePath);
    if (detector.rule === 'uiDiscoveryParity') runUiParity(detector, content, findings, policy);
    if (detector.buildFiles) runDependencies(detector, content, findings, relativePath);
    if (detector.shapes) runShapes(detector, content, findings);
    if (detector.rule === 'modelWalk' && detector.ruleId === 'UNBOUNDED_TIMEOUT') runTimeouts(detector, content, findings);
    if (detector.rule === 'modelWalk' && detector.ruleId === 'KAFKA_EXPECT_WITHOUT_DISCRIMINATOR') runKafkaDiscriminator(detector, content, findings);
  }
  return findings.map((item) => ({ ...item, file: relativePath }));
}

/** Why a finding did not run, in the words the report uses. */
export const NOT_RUN_REASONS = {
  unimplemented: 'не реализована',
  previousVersion: 'нет прежней версии артефакта',
  kind: 'вид артефакта не является предметом находки',
};

/**
 * Which findings ran and which did not — a clean report from part of the set is not a clean report.
 *
 * The set has always depended on the ARTIFACT, and this function used not to ask. It answered from
 * the table alone: seventeen ran, one did not. On a Java file that was wrong by six — the timeout
 * walk, the script-key check, the fixed-id heuristic, the Kafka discriminator and the dependency
 * check have no Java branch at all — and the line "проверено находок: 17" appeared under every scan
 * of every kind, which is precisely the claim this kit spends the rest of its reporting refusing to
 * make. Adding the prose exclusions made the gap wider, and that is what made it worth closing rather
 * than inheriting.
 *
 * @param options
 *   `previousVersion: true` when the caller supplied the artifact's previous version — what finding 18
 *   needs; `kinds: [...]` the artifact kinds actually scanned. Omit `kinds` for the table-wide view
 *   (what `status` reports: how much of the table this installation could run at all).
 * @returns `{ ran, notRun, reasons }` — `reasons` maps each not-run id to a key of
 *   {@link NOT_RUN_REASONS}, because "did not run" and "did not apply" are different answers and a
 *   report that merges them says less than it knows.
 */
export function gates(options = {}) {
  const kinds = Array.isArray(options.kinds) ? options.kinds.filter(Boolean) : [];
  const ran = [];
  const reasons = {};
  for (const detector of detectors().detectors) {
    if (detector.implemented === false) {
      reasons[detector.ruleId] = 'unimplemented';
    } else if (detector.requires === 'previousVersion' && options.previousVersion !== true) {
      reasons[detector.ruleId] = 'previousVersion';
    } else if (kinds.length > 0 && !kinds.some((kind) => appliesToKind(detector, kind))) {
      reasons[detector.ruleId] = 'kind';
    } else {
      ran.push(detector.ruleId);
    }
  }
  return { ran, notRun: Object.keys(reasons), reasons };
}

/**
 * The gate summary as a person reads it: how much of the table ran, and what stopped the rest.
 *
 * Grouped by reason rather than listed flat, because the two reasons ask different things of the
 * reader. "Нет прежней версии" is something the caller can fix — pass `--against`. "Вид артефакта" is
 * not a gap at all, and printing it as one would train the reader to ignore the line.
 */
export function renderGates({ ran, notRun, reasons }, kinds = []) {
  const total = ran.length + notRun.length;
  const scope = kinds.length > 0 ? ` (${[...new Set(kinds)].sort().join(', ')})` : '';
  const lines = [`проверено находок: ${ran.length} из ${total}${scope}`];
  for (const [reason, label] of Object.entries(NOT_RUN_REASONS)) {
    const affected = notRun.filter((ruleId) => reasons[ruleId] === reason);
    if (affected.length > 0) lines.push(`  не проверено — ${label}: ${affected.join(', ')}`);
  }
  return lines.join('\n');
}

/**
 * What a CHANGE stopped checking — the findings that need both versions of an artifact.
 *
 * @param previous the artifact as it was; '' for a new file, where nothing can have been concealed
 * @param next the artifact as it will be
 * @param relativePath the path it will have
 */
export function scanDiff(previous, next, relativePath) {
  const detector = detectors().detectors.find((item) => item.requires === 'previousVersion');
  if (detector === undefined) return [];
  return concealment(previous, next, kindOf(relativePath), detector, relativePath)
    .map((item) => ({ ...item, file: relativePath }));
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
