// The knowledge base, checked where it is actually used.
//
// The schemas are the contract, and in THIS repository a Gradle test validates every KB file against
// them. That test does not travel: the kit is copied into a consumer project, and there the twenty
// schemas are twenty documents nobody executes. So the base drifts at exactly the sites where it
// matters, and the first sign of it is a generated test failing against a stand for a reason that
// looks like anything but a stale KB.
//
// What follows is therefore not a JSON-Schema implementation. Writing one here would be a second
// implementation of the contract, and a validator that says PASS where the real schema says FAIL is
// worse than no validator at all. These are the checks a script can decide EXACTLY — lexical safety
// and identity — plus one cross-file question the schemas cannot ask at all, because an alias's
// counterpart lives in the environment registry rather than in the base. Everything else is reported
// as not checked, by name.

import { scanArtifact, blocking } from './scan.mjs';
import { knowledgeBaseFiles, knowledgeBase, registryAliases, REGISTRY_COLLECTIONS } from './kb.mjs';

/** Environment names that must never appear in a TEST knowledge base, whatever the allowlist says. */
const PRODUCTION_TOKENS = ['prod', 'prd', 'production', 'psi', 'промышленн'];

/** A reference is the bare NAME of an environment variable. Anything else is a value, or a trap. */
const BARE_NAME = /^[A-Z][A-Z0-9_]*$/;

// Only the keys that carry an ENVIRONMENT VARIABLE name. `schemaRef` points at a classpath resource
// and is none of this checker's business — a rule that fires on it is a rule people turn off.
// The prefix is OPTIONAL — `passwordRef` is the commonest of these keys and has nothing before the
// word, which a required prefix quietly excluded from the very check written for it.
const REF_KEY = /^\s*(?:-\s+)?([A-Za-z0-9]*(?:[Uu]rl|[Uu]sername|[Uu]ser|[Pp]assword|[Tt]oken|[Ss]ecret|[Cc]redential|[Tt]arget|[Bb]ootstrap[A-Za-z]*|[Ss]ervers|[Pp]rotocol|[Aa]pi[Kk]ey)(?:Ref|-ref))\s*:\s*(.+?)\s*$/;

const SECRET_KEY = /^\s*(?:-\s+)?([A-Za-z][A-Za-z0-9_-]*(?:password|secret|token|credential|apikey|api-key)[A-Za-z0-9_-]*)\s*:\s*(.+?)\s*$/i;

/** What this file checks, so a clean report cannot be read as more than it is. */
export const CHECKS = [
  'KB_ARTIFACT_SCAN',
  'KB_SECRET_IN_COMMENT',
  'KB_PRODUCTION_ENVIRONMENT',
  'KB_REF_NOT_BARE_NAME',
  'KB_SECRET_VALUE',
  'KB_LITERAL_MARKER',
  'KB_DUPLICATE_ID',
  'KB_EMPTY_COLLECTION',
];

/** What it does NOT, named rather than implied. The schemas remain the contract for these. */
export const NOT_CHECKED = [
  'соответствие JSON Schema (обязательные поля, enum, форматы)',
  'внутренние ссылки KB (service.endpoints → endpoints, endpoint.service → services)',
  'смысл записи: что путь/таблица/метод существуют в реальной системе',
];

/**
 * The document without its comments.
 *
 * A comment is not KB content, and the artifact detectors judge content: the reference base documents
 * an env var's SHAPE as `export SHOWCASE_MOCK_BASE_URL="http://<stand-host>:<port>"`, which is
 * exactly right and which the URL detector called a hardcoded address. What a comment can still ruin
 * is checked separately below, because a credential is committed wherever it is written.
 */
function withoutComments(text) {
  return text.split('\n').map((line) => (/^\s*#/.test(line) ? '' : line)).join('\n');
}

const SECRET_SHAPE = /([Bb]earer\s+[A-Za-z0-9._-]{6}|[Bb]asic\s+[A-Za-z0-9+/=]{8}|AKIA[0-9A-Z]{12}|eyJ[A-Za-z0-9._-]{10}|(?:password|passwd|secret|token)\s*[:=]\s*(?!["']?[A-Z][A-Z0-9_]*["']?\s*$)\S+)/;

function checkComments(file, findings) {
  for (const [index, line] of file.text.split('\n').entries()) {
    if (!/^\s*#/.test(line)) continue;
    const match = SECRET_SHAPE.exec(line);
    if (match !== null) {
      findings.push(finding('KB_SECRET_IN_COMMENT', 'BLOCK', `${file.path}:${index + 1}`,
        `в комментарии записан креденшел: '${match[1].slice(0, 40)}'`,
        'комментарий коммитится вместе с файлом; в базе знаний место только именам переменных'));
    }
  }
}

function finding(ruleId, severity, file, message, fix) {
  return { ruleId, severity, file, message, fix, source: 'STATIC_SCAN' };
}

function lineNumber(text, index) {
  return text.slice(0, index).split('\n').length;
}

function checkProductionNames(file, findings) {
  for (const [index, line] of file.text.split('\n').entries()) {
    const match = /^\s*(?:-\s+)?(?:id|alias|name):\s*["']?([A-Za-z0-9][A-Za-z0-9._-]*)["']?\s*$/.exec(line);
    if (match === null) continue;
    const value = match[1].toLowerCase();
    if (PRODUCTION_TOKENS.some((token) => new RegExp(`(^|[^a-zа-я])${token}([^a-zа-я]|$)`, 'i').test(value))) {
      findings.push(finding('KB_PRODUCTION_ENVIRONMENT', 'BLOCK', `${file.path}:${index + 1}`,
        `запись '${match[1]}' названа как production`,
        'база знаний описывает тестовый периметр; production-имя в ней рано или поздно окажется в сгенерированном тесте'));
    }
  }
}

function checkReferences(file, findings) {
  for (const [index, line] of file.text.split('\n').entries()) {
    const ref = REF_KEY.exec(line);
    if (ref !== null && !BARE_NAME.test(ref[2].replace(/^["']|["']$/g, ''))) {
      findings.push(finding('KB_REF_NOT_BARE_NAME', 'BLOCK', `${file.path}:${index + 1}`,
        `'${ref[1]}' содержит не голое ИМЯ переменной, а '${ref[2]}'`,
        'ref — это ИМЯ переменной окружения (UPPER_SNAKE). `${...}` тут — ловушка двойного резолвинга: Spring схлопнет плейсхолдер до значения, и SDK примет значение за имя переменной'));
    }
    const secret = SECRET_KEY.exec(line);
    if (secret !== null && !/(?:Ref|-ref)$/.test(secret[1]) && !BARE_NAME.test(secret[2].replace(/^["']|["']$/g, ''))) {
      findings.push(finding('KB_SECRET_VALUE', 'BLOCK', `${file.path}:${index + 1}`,
        `'${secret[1]}' похоже на креденшел со ЗНАЧЕНИЕМ`,
        'база знаний хранит ссылки, а не секреты: переименуй поле в *-ref и положи туда имя переменной'));
    }
  }
  const marker = file.text.indexOf('literal://');
  if (marker >= 0) {
    findings.push(finding('KB_LITERAL_MARKER', 'BLOCK', `${file.path}:${lineNumber(file.text, marker)}`,
      'встречен внутренний маркер SDK literal://',
      'этот маркер принадлежит внутренностям SDK и в пользовательской конфигурации отвергается fail-closed; убери его'));
  }
}

function checkIdentity(files, findings) {
  const seen = new Map();
  for (const file of files) {
    if (file.key === null) {
      findings.push(finding('KB_EMPTY_COLLECTION', 'HIGH', file.path,
        'у файла нет коллекции верхнего уровня',
        'файл базы знаний начинается с ключа коллекции (services:/endpoints:/…), иначе его не читает ни схема, ни поиск'));
      continue;
    }
    if (file.ids.length === 0) {
      findings.push(finding('KB_EMPTY_COLLECTION', 'HIGH', file.path,
        `коллекция '${file.key}' не содержит ни одной записи`,
        'пустой файл коллекции выглядит как покрытие, которого нет'));
    }
    for (const id of file.ids) {
      const key = `${file.key}/${id}`;
      if (seen.has(key)) {
        findings.push(finding('KB_DUPLICATE_ID', 'HIGH', file.path,
          `id '${id}' в коллекции '${file.key}' уже объявлен в ${seen.get(key)}`,
          'алиас — ключ связи между базой, реестром и тестом; два разных описания одного ключа резолвятся по порядку файлов, то есть случайно'));
      } else {
        seen.set(key, file.path);
      }
    }
  }
}

/** Every finding in the curated knowledge base, plus what was deliberately not looked at. */
export function validateKnowledgeBase(cwd) {
  const files = knowledgeBaseFiles(cwd);
  const findings = [];
  for (const file of files) {
    // The artifact detectors already decide URLs, JDBC strings and credential shapes exactly, and a
    // KB file is judged by the same table as everything else the kit writes.
    findings.push(...scanArtifact(withoutComments(file.text), file.path));
    checkComments(file, findings);
    checkProductionNames(file, findings);
    checkReferences(file, findings);
  }
  checkIdentity(files, findings);
  return { files: files.length, findings, checks: CHECKS, notChecked: NOT_CHECKED };
}

/**
 * The base against the registry: two different silences, both worth breaking.
 *
 * An alias the KB describes but no registry declares is the expensive one — the case reads as fully
 * resolved, the test generates, and it fails on a real stand with "alias is not in the registry".
 * The other direction is the bootstrap worklist and is only information.
 */
export function checkAliases(cwd) {
  const { path, aliases } = registryAliases(cwd);
  const kb = knowledgeBase(cwd);
  const kinds = {};
  const findings = [];
  for (const [kind, collection] of Object.entries(REGISTRY_COLLECTIONS)) {
    const declared = aliases[kind] || [];
    const known = [...(kb.byKey[collection] || new Set())].sort();
    // The two directions read DIFFERENT sets on purpose. "Covered by the KB" accepts either spelling
    // (`known`, the union of ids and aliases); "not in the registry" accepts only what the entry
    // declares as its ALIAS, because an id is the base's own key and may legitimately differ — and
    // when it does, the union reports the entry as unregistered while its alias sits in the registry.
    const knownAliases = [...(kb.aliasesByKey[collection] || new Set())].sort();
    const unregistered = path === null ? [] : knownAliases.filter((alias) => !declared.includes(alias));
    kinds[kind] = { collection, registry: declared, knowledgeBase: known, unregistered, missingFromKb: declared.filter((alias) => !known.includes(alias)) };
    for (const alias of unregistered) {
      findings.push(finding('KB_ALIAS_NOT_IN_REGISTRY', 'HIGH', `knowledge-base/*/${collection}`,
        `'${alias}' описан в базе знаний, но реестр окружений его не объявляет`,
        'добавь алиас в stand-test-environments.yml (или stand.test.environments.*) — иначе сгенерированный по этой записи тест упадёт на резолвинге, а выглядеть будет как ошибка теста'));
    }
  }
  return { registry: path, kinds, findings };
}

export { blocking };
