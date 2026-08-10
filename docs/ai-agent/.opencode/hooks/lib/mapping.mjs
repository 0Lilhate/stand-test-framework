// The traceability record: which case a generated test answers.
//
// `knowledge-base/mappings/` is the one collection the agent may write, and whether it DOES write has
// been good faith all along. A test with no mapping entry is a file whose reason for existing lives
// in a chat transcript: six months later nobody can say which case it covers, whether that case is
// still current, or what was assumed while writing it. Nothing else in the base can answer that,
// because the mapping is the only place the join is recorded.
//
// What is enforced here is that the record EXISTS, not what it says. Half the entry is interpretation
// — `matched`, `missing`, `assumptions`, the title — and a hook that authored those would be writing
// knowledge it does not have; a hook that rewrote the file to add the half it does have would delete
// the half it does not. So the machine checks for the reference and refuses to let the session end
// without one, and the writing stays where the knowledge is.

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { basename, join } from 'node:path';

const ENTRY = /^\s*-\s+caseId:\s*["']?([A-Za-z0-9][A-Za-z0-9._-]*)["']?\s*$/;

const CLASS_NAME = /^\s*className:\s*["']?([A-Za-z_$][A-Za-z0-9_$]*)["']?\s*$/;

const STATUS = /^\s*status:\s*["']?([a-z]+)["']?\s*$/;

/**
 * Every mapping entry in the collection, across every file that declares it.
 *
 * Across FILES deliberately: a collection spans them, and reading one chosen file is how two
 * different `ift` environments once lived in this base at the same time.
 */
export function mappings(cwd) {
  const directory = join(cwd, 'knowledge-base', 'mappings');
  if (!existsSync(directory)) return [];

  const entries = [];
  for (const name of readdirSync(directory).sort()) {
    if (!name.endsWith('.yml') && !name.endsWith('.yaml')) continue;
    const path = `knowledge-base/mappings/${name}`;
    let current = null;
    for (const line of readFileSync(join(cwd, path), 'utf8').split('\n')) {
      const opener = ENTRY.exec(line);
      if (opener !== null) {
        current = { caseId: opener[1], file: path, classNames: [], status: null };
        entries.push(current);
        continue;
      }
      if (current === null) continue;
      const className = CLASS_NAME.exec(line);
      if (className !== null) current.classNames.push(className[1]);
      const status = STATUS.exec(line);
      if (status !== null && current.status === null) current.status = status[1];
    }
  }
  return entries;
}

/**
 * Whether a generated test is claimed by some mapping entry.
 *
 * Matched by class name, which is exact: the file is `…/OrderScenarioTest.java` and the entry says
 * `className: OrderScenarioTest`. Only java artifacts are asked for — a fixture or a scenario document
 * has no class name to join on, and inventing a fuzzy match would produce refusals nobody can act on.
 */
export function claimsArtifact(entries, path) {
  const className = basename(path).replace(/\.java$/i, '');
  return entries.some((entry) => entry.classNames.includes(className));
}

/**
 * A Page Object, by the same predicate the `UI_LOCATOR_OUTSIDE_PAGES` detector uses.
 *
 * Path only, because that is all the Stop gate has — it works off the artifact ledger, whose keys are
 * paths, and the sanctioned layout puts these classes under `…/ui/pages/` (the Page Object skill says
 * so, and the detector already reads the same shape).
 */
const PAGE_OBJECT = [/(?:^|[/\\])ui[/\\]pages?[/\\]/i, /(?:^|[/\\])ui[/\\]pageobjects?[/\\]/i, /page[-_]object/i];

/**
 * Whether a generated artifact needs a mapping at all.
 *
 * A mapping entry records the join "case → the test that answers it", and its schema has exactly one
 * place for a class: `generatedTest.className`. A **Page Object is not a test** and has nowhere to go
 * in that entry — so demanding one for it asks for a record that cannot be written.
 *
 * That is what this used to do. Every `.java` outside `knowledge-base/` was asked for a mapping, and
 * the UI branch produces a Page Object per screen beside the one test: a UI generation therefore ended
 * every session with the Stop gate naming `NewApplicationPage.java` among "tests that passed review
 * and are not declared in knowledge-base/mappings/". Not a hard block — the second pass reports rather
 * than loops — but a NOT-READY line that is wrong every time is worse than none: it is the line the
 * whole gate exists to make people read, spent on an artifact that was never supposed to be there.
 */
export function needsMapping(path) {
  if (!/^(?!knowledge-base\/).*\.java$/i.test(path)) return false;
  return !PAGE_OBJECT.some((pattern) => pattern.test(path));
}

/** The entry a person can paste, with every field the schema requires already in place. */
export function template(path, caseId) {
  const className = basename(path).replace(/\.java$/i, '');
  const suggested = caseId || className.replace(/Test$/, '').replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
  const packageName = (path.split('/').slice(0, -1).join('.').replace(/^.*java\./, '') || 'ru.alfa.qa.test');
  return `knowledge-base/mappings/${suggested}.yml:

testCaseMappings:
  - caseId: ${suggested}
    title: <что проверяет тест — одной строкой>
    environment: <id окружения из реестра>
    matched: {}          # что дал kb-lookup: services/endpoints/kafkaTopics/datasources/…
    missing: []          # то, чего в базе знаний не нашлось
    assumptions: []      # допущения, записанные по дороге
    generatedTest:
      module: <gradle-модуль>
      package: ${packageName}
      className: ${className}
    status: generated
    updated: "${new Date().toISOString().slice(0, 10)}"`;
}
