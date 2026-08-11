// What the environment registry declares, and what the knowledge base already knows about it.
//
// This exists for one question the cold start turns on: is an alias ATTESTED BY THE REGISTRY? An
// alias a person put in `stand-test-environments.yml` is curated by construction — the SDK does not
// start without that file — so a case naming it may proceed on a recorded assumption instead of a
// blocking question. An alias in neither the KB nor the registry is a real unknown and stays one.
//
// The reason this is a script and not a paragraph in a skill: "the registry attests it" must be a
// fact read off a file, not a recollection. The list below comes from the bytes on disk every time.
//
// The scanning is deliberately shallow. A full YAML parser here would be a second implementation of
// something the schema gate already owns, and it is not needed: aliases are map KEYS at a known
// depth under a known section, and nothing about their VALUES is read — which is also why no secret
// can travel through this file.

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/** Where a consumer project keeps the registry, in the order the SDK itself looks. */
const REGISTRY_PATHS = [
  'stand-test-environments.yml',
  'src/test/resources/stand-test-environments.yml',
  'src/test/resources/application.yml',
  'application.yml',
];

/** Registry section → the kind of thing its keys name. */
const SECTIONS = {
  services: 'service',
  topics: 'kafka-topic',
  datasources: 'datasource',
  'grpc-targets': 'grpc-target',
};

/** KB collections, as directory names under `knowledge-base/`. */
const COLLECTIONS = ['services', 'endpoints', 'kafka', 'db', 'grpc', 'environments'];

/**
 * Registry kind → the KB collection KEY that holds the same thing.
 *
 * By the top-level key of the file, not by its directory: `db/` holds datasources, tables and probes
 * together, and comparing a TABLE id against the registry's datasource aliases would report a finding
 * for every table in the base. A false finding here is worse than a missed one — this is the report
 * people are meant to keep running.
 */
export const REGISTRY_COLLECTIONS = {
  service: 'services',
  'kafka-topic': 'kafkaTopics',
  datasource: 'datasources',
  'grpc-target': 'grpcTargets',
};

const TOP_LEVEL_KEY = /^([A-Za-z][A-Za-z0-9]*):\s*$/m;

// The id of an ENTRY is the list-item one. An `alias:` beside it is the same entry seen twice, and
// counting both made every entry that carries an alias look like a duplicate of itself.
const ENTRY_ID = /^\s*-\s+id:\s*["']?([A-Za-z0-9][A-Za-z0-9._-]*)["']?\s*$/gm;

const ENTRY_ALIAS = /^\s*(?:-\s+)?alias:\s*["']?([A-Za-z0-9][A-Za-z0-9._-]*)["']?\s*$/gm;

const KEY = /^(\s*)([A-Za-z][A-Za-z0-9._-]*):\s*$/;

export function registryPath(cwd) {
  return REGISTRY_PATHS.find((path) => existsSync(join(cwd, path))) || null;
}

/**
 * The aliases the registry declares, by kind.
 *
 * Both spellings are read by the same scanner: the plain-JUnit `stand-test-environments.yml` and the
 * Spring `stand.test.environments.*` block nest their aliases identically, only deeper.
 */
export function registryAliases(cwd) {
  const path = registryPath(cwd);
  const found = { service: [], 'kafka-topic': [], datasource: [], 'grpc-target': [] };
  if (path === null) return { path, aliases: found };

  const lines = readFileSync(join(cwd, path), 'utf8').split('\n');
  for (let index = 0; index < lines.length; index += 1) {
    const opener = KEY.exec(lines[index]);
    if (opener === null || SECTIONS[opener[2]] === undefined) continue;
    const kind = SECTIONS[opener[2]];
    const outer = opener[1].length;
    let childIndent = null;
    for (let inner = index + 1; inner < lines.length; inner += 1) {
      const line = lines[inner];
      if (line.trim() === '' || line.trim().startsWith('#')) continue;
      const indent = line.length - line.trimStart().length;
      if (indent <= outer) break;
      const child = KEY.exec(line);
      if (child === null) continue;
      // Only the section's OWN keys are aliases; anything deeper is that alias's configuration.
      if (childIndent === null) childIndent = child[1].length;
      if (child[1].length === childIndent && !found[kind].includes(child[2])) found[kind].push(child[2]);
    }
  }
  return { path, aliases: found };
}

/** Every curated KB file: where it is, what collection it declares, and the ids in it. */
export function knowledgeBaseFiles(cwd) {
  const root = join(cwd, 'knowledge-base');
  if (!existsSync(root)) return [];
  const files = [];
  for (const collection of COLLECTIONS) {
    const directory = join(root, collection);
    if (!existsSync(directory)) continue;
    for (const name of readdirSync(directory).sort()) {
      if (!name.endsWith('.yml') && !name.endsWith('.yaml')) continue;
      const path = `knowledge-base/${collection}/${name}`;
      const text = readFileSync(join(cwd, path), 'utf8');
      const key = TOP_LEVEL_KEY.exec(text);
      files.push({
        path,
        collection,
        key: key === null ? null : key[1],
        ids: [...text.matchAll(ENTRY_ID)].map((match) => match[1]),
        aliases: [...text.matchAll(ENTRY_ALIAS)].map((match) => match[1]),
        text,
      });
    }
  }
  return files;
}

/**
 * Every `id:`/`alias:` the curated KB declares, by collection key and in total.
 *
 * <p>Two questions are asked of this, and they need DIFFERENT sets — which is why `byKey` and
 * `aliasesByKey` are both here rather than one being derived from the other:
 *
 * <ul>
 *   <li>"does the KB already know this REGISTRY alias?" — either spelling answers it, so `byKey` is
 *       the union. Missing an entry here would put a covered alias back on the bootstrap worklist.
 *   <li>"which KB aliases does no registry declare?" — only `alias:` answers it. An entry's `id` is
 *       the base's own key and is free to differ (a datasource id is kebab-case while its alias
 *       mirrors an underscored database name), so counting ids as aliases reports the entry as
 *       unregistered while its real alias sits in the registry. The union made that finding
 *       unavoidable for every entry whose two spellings differ.
 * </ul>
 *
 * <p>A file that declares no `alias:` at all falls back to its ids: going blind there would be worse
 * than a false positive, because a collection with no alias field is exactly where a typo hides.
 */
export function knowledgeBase(cwd) {
  const present = existsSync(join(cwd, 'knowledge-base'));
  const entries = {};
  const ids = new Set();
  const byKey = {};
  const aliasesByKey = {};
  for (const file of knowledgeBaseFiles(cwd)) {
    entries[file.collection] = (entries[file.collection] || 0) + file.ids.length;
    if (file.key !== null) {
      byKey[file.key] = byKey[file.key] || new Set();
      aliasesByKey[file.key] = aliasesByKey[file.key] || new Set();
    }
    [...file.ids, ...file.aliases].forEach((id) => {
      ids.add(id);
      if (file.key !== null) byKey[file.key].add(id);
    });
    const declaredAliases = file.aliases.length > 0 ? file.aliases : file.ids;
    if (file.key !== null) declaredAliases.forEach((alias) => aliasesByKey[file.key].add(alias));
  }
  return { present, entries, ids, byKey, aliasesByKey };
}

/**
 * The cold-start picture: what the registry declares, what the KB covers, and what is missing.
 *
 * `missing` is the bootstrap worklist — aliases a person already curated in the registry that the
 * knowledge base has never heard of. It is NOT a list of contract details: no registry anywhere
 * attests a path, a field, a table or a gRPC method, so those are never bootstrapped.
 */
export function kbStatus(cwd) {
  const { path, aliases } = registryAliases(cwd);
  const kb = knowledgeBase(cwd);
  const kinds = {};
  let missingTotal = 0;
  for (const [kind, declared] of Object.entries(aliases)) {
    const known = kb.byKey[REGISTRY_COLLECTIONS[kind]] || new Set();
    const missing = declared.filter((alias) => !known.has(alias));
    missingTotal += missing.length;
    kinds[kind] = { registry: declared, missing };
  }
  return {
    registry: path,
    knowledgeBase: { present: kb.present, entries: kb.entries, ids: kb.ids.size },
    kinds,
    missingTotal,
  };
}
