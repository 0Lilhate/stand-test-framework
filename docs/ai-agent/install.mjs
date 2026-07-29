#!/usr/bin/env node
//
// The kit, installed by its manifest rather than by `cp -R`.
//
// Two problems, one cause. A copied directory carries whatever happens to be in it — that is how a
// file holding a developer's allow-list, absolute paths into an unrelated repository and a curl
// command with DEV stand credentials once sat in the bundle, one copy away from every consumer. And
// once installed, nothing at the consumer can say which version arrived or what has been edited
// since: every mechanism that holds this kit together — the schema tests, the parity tests, the
// inventory — lives in the SDK repository and stops existing the moment the bundle leaves it.
//
// So the manifest travels with the kit: every shipped path and the hash of its content. Install
// copies exactly what it lists and nothing else; `stand-guard.mjs doctor` compares an installation
// against it and answers both questions in about a second.
//
// Usage:
//   node install.mjs --manifest                     regenerate MANIFEST.json from this tree
//   node install.mjs <target> [--host claude|opencode] [--apply]
//                                                   dry-run by default, like every other write here

import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));

const MANIFEST = 'MANIFEST.json';

/** The two bundle copies. Everything shipped lives under one of them. */
const BUNDLES = ['.claude', '.opencode'];

/**
 * Never shipped, whatever the directory holds.
 *
 * Machine-local residue is gitignored, so it never reaches a remote — and a directory copy takes it
 * anyway. That is the whole reason this list is here rather than in a `.gitignore`.
 */
const NOT_SHIPPED = new Set(['settings.local.json', 'scheduled_tasks.lock', '.DS_Store']);

const NOT_SHIPPED_PREFIXES = ['.env', '.fetched-'];

/** Per-run state written by the hooks. It belongs to an installation, not to the kit. */
const NOT_SHIPPED_DIRECTORIES = new Set(['.stand-test']);

function sha256(buffer) {
  return `sha256:${createHash('sha256').update(buffer).digest('hex')}`;
}

function shipped(name) {
  return !NOT_SHIPPED.has(name) && !NOT_SHIPPED_PREFIXES.some((prefix) => name.startsWith(prefix));
}

/** Every shipped file of a bundle copy, as paths relative to the bundle root, sorted. */
export function shippedFiles(root = ROOT) {
  const found = [];
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.isDirectory()) {
        if (!NOT_SHIPPED_DIRECTORIES.has(entry.name)) visit(join(directory, entry.name));
      } else if (shipped(entry.name)) {
        found.push(relative(root, join(directory, entry.name)).split('\\').join('/'));
      }
    }
  };
  for (const bundle of BUNDLES) {
    if (existsSync(join(root, bundle))) visit(join(root, bundle));
  }
  return found.sort();
}

/** The manifest this tree describes: what ships, and the content it ships with. */
export function buildManifest(root = ROOT, version = 1) {
  const files = {};
  for (const path of shippedFiles(root)) {
    files[path] = sha256(readFileSync(join(root, path)));
  }
  return { kit: 'stand-test-ai-agent-kit', version, files };
}

export function readManifest(root = ROOT) {
  return JSON.parse(readFileSync(join(root, MANIFEST), 'utf8'));
}

function commandRegenerate() {
  const previous = existsSync(join(ROOT, MANIFEST)) ? readManifest(ROOT) : { version: 0 };
  const manifest = buildManifest(ROOT, previous.version || 1);
  writeFileSync(join(ROOT, MANIFEST), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  process.stdout.write(`${MANIFEST}: ${Object.keys(manifest.files).length} файлов, версия кита ${manifest.version}\n`
    + '  Версия правится руками — она про смысл набора промтов, а не про то, что кто-то поправил опечатку.\n');
}

function commandInstall(argv) {
  const target = argv.find((argument) => !argument.startsWith('--'));
  if (!target) {
    process.stderr.write('нужен каталог назначения: node install.mjs <target> [--host claude|opencode] [--apply]\n');
    process.exit(1);
  }
  const host = argumentValue(argv, '--host') || 'claude';
  if (!BUNDLES.includes(`.${host}`)) {
    process.stderr.write(`--host принимает claude или opencode, а не '${host}'\n`);
    process.exit(1);
  }
  const apply = argv.includes('--apply');
  const manifest = readManifest(ROOT);
  const files = Object.keys(manifest.files).filter((path) => path.startsWith(`.${host}/`));

  const planned = [];
  const conflicts = [];
  for (const path of files) {
    const destination = join(target, path);
    if (!existsSync(destination)) {
      planned.push(['новый', path]);
      continue;
    }
    const current = sha256(readFileSync(destination));
    if (current === manifest.files[path]) planned.push(['без изменений', path]);
    else conflicts.push(path);
  }

  for (const [what, path] of planned) process.stdout.write(`  ${what.padEnd(14)} ${path}\n`);
  for (const path of conflicts) process.stdout.write(`  ПРАВЛЕН ЛОКАЛЬНО ${path}\n`);

  if (!apply) {
    process.stdout.write(`\ndry-run: ничего не записано. ${planned.length} файлов к установке`
      + `${conflicts.length > 0 ? `, ${conflicts.length} правлены локально (перезапишет только --apply --force)` : ''}\n`
      + '  → повторите с --apply\n');
    process.exit(0);
  }

  const force = argv.includes('--force');
  let written = 0;
  for (const path of files) {
    if (conflicts.includes(path) && !force) continue;
    const destination = join(target, path);
    mkdirSync(dirname(destination), { recursive: true });
    copyFileSync(join(ROOT, path), destination);
    written += 1;
  }
  // The manifest travels with the kit: without it the consumer has no way to answer "what version is
  // this" or "what has been edited", which is the entire point of installing by manifest.
  copyFileSync(join(ROOT, MANIFEST), join(target, `.${host}`, MANIFEST));
  process.stdout.write(`\nустановлено файлов: ${written} в ${target}/.${host}\n`
    + (conflicts.length > 0 && !force ? `пропущено правленных локально: ${conflicts.length} (--force перезапишет)\n` : '')
    + `→ проверка: node .${host}/hooks/stand-guard.mjs doctor\n`);
}

function argumentValue(argv, name) {
  const index = argv.indexOf(name);
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : null;
}

const argv = process.argv.slice(2);
if (argv.includes('--manifest')) {
  commandRegenerate();
} else if (argv.length > 0) {
  commandInstall(argv);
} else {
  process.stdout.write('node install.mjs --manifest | node install.mjs <target> [--host claude|opencode] [--apply] [--force]\n');
}
