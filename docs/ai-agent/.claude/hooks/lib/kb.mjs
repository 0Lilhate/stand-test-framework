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

/** Every `id:`/`alias:` the curated KB declares, and how many entries each collection holds. */
export function knowledgeBase(cwd) {
  const root = join(cwd, 'knowledge-base');
  const entries = {};
  const ids = new Set();
  if (!existsSync(root)) return { present: false, entries, ids };

  for (const collection of COLLECTIONS) {
    const directory = join(root, collection);
    if (!existsSync(directory)) continue;
    let count = 0;
    for (const name of readdirSync(directory)) {
      if (!name.endsWith('.yml') && !name.endsWith('.yaml')) continue;
      const text = readFileSync(join(directory, name), 'utf8');
      for (const match of text.matchAll(/^\s*(?:-\s+)?(?:id|alias):\s*["']?([A-Za-z0-9][A-Za-z0-9._-]*)["']?\s*$/gm)) {
        ids.add(match[1]);
        count += 1;
      }
    }
    entries[collection] = count;
  }
  return { present: true, entries, ids };
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
    const missing = declared.filter((alias) => !kb.ids.has(alias));
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
