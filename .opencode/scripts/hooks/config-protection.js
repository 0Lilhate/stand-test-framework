#!/usr/bin/env node
/**
 * Config Protection Hook (Java / Kotlin / Spring Boot)
 *
 * Blocks modifications to static-analysis / formatting rule files.
 * Agents frequently modify these to make checks pass instead of fixing
 * the actual code. This hook steers the agent back to fixing the source.
 *
 * What is protected:
 *   - Checkstyle / PMD / SpotBugs / Sonar configs (Java)
 *   - detekt / ktlint configs (Kotlin)
 *   - .editorconfig (universal — formatting rules used by ktlint/IDEs)
 *
 * What is NOT protected (legitimate dev work):
 *   - build.gradle / build.gradle.kts / settings.gradle* — dependency / plugin changes
 *   - pom.xml — Maven dependencies / plugins
 *   - libs.versions.toml — Gradle version catalog
 *   - gradle.properties — JVM/build tuning
 *   - gradle-wrapper.properties — gradle version (intentionally bumpable)
 *
 * Exit codes:
 *   0 = allow (not a protected file)
 *   2 = block (rule-config modification attempted)
 *
 * Disabling temporarily:
 *   export ECC_DISABLED_HOOKS=pre:config-protection
 */

'use strict';

const path = require('path');

const MAX_STDIN = 1024 * 1024;
let raw = '';

const PROTECTED_FILES = new Set([
  // ── Java static analysis ────────────────────────────────────────
  // Checkstyle rules + suppressions
  'checkstyle.xml',
  'checkstyle-suppressions.xml',
  'suppressions.xml',
  'checkstyle-rules.xml',
  // PMD rulesets
  'pmd-ruleset.xml',
  'pmd-rules.xml',
  'pmd.xml',
  // SpotBugs / FindBugs filters
  'spotbugs-exclude.xml',
  'spotbugs-include.xml',
  'findbugs-exclude.xml',
  'findbugs-include.xml',
  'spotbugs.xml',
  // Sonar
  'sonar-project.properties',
  '.sonarcloud.properties',
  'sonar.properties',

  // ── Kotlin static analysis ──────────────────────────────────────
  'detekt.yml',
  'detekt.yaml',
  'detekt-config.yml',
  'detekt-config.yaml',
  '.detekt.yml',
  // ktlint baseline / editorconfig (ktlint reads .editorconfig)
  '.ktlint',
  'ktlint-baseline.xml',

  // ── Universal ───────────────────────────────────────────────────
  '.editorconfig',
]);

function parseInput(inputOrRaw) {
  if (typeof inputOrRaw === 'string') {
    try {
      return inputOrRaw.trim() ? JSON.parse(inputOrRaw) : {};
    } catch {
      return {};
    }
  }

  return inputOrRaw && typeof inputOrRaw === 'object' ? inputOrRaw : {};
}

/**
 * Exportable run() for in-process execution via run-with-flags.js.
 * Avoids the ~50-100ms spawnSync overhead when available.
 */
function run(inputOrRaw, options = {}) {
  if (options.truncated) {
    return {
      exitCode: 2,
      stderr:
        `BLOCKED: Hook input exceeded ${options.maxStdin || MAX_STDIN} bytes. ` +
        'Refusing to bypass config-protection on a truncated payload. ' +
        'Retry with a smaller edit or disable the config-protection hook temporarily.'
    };
  }

  const input = parseInput(inputOrRaw);
  const filePath = input?.tool_input?.file_path || input?.tool_input?.file || '';
  if (!filePath) return { exitCode: 0 };

  const basename = path.basename(filePath);
  if (PROTECTED_FILES.has(basename)) {
    return {
      exitCode: 2,
      stderr:
        `BLOCKED: Modifying ${basename} is not allowed.\n` +
        'This is a static-analysis / formatting rules file. Fix the Java / Kotlin source ' +
        'to satisfy the existing rules instead of weakening the config.\n' +
        'If this is a legitimate config change (new rule adoption, baseline regeneration), ' +
        'disable the hook temporarily: export ECC_DISABLED_HOOKS=pre:config-protection',
    };
  }

  return { exitCode: 0 };
}

module.exports = { run };

// Stdin fallback for spawnSync execution
let truncated = /^(1|true|yes)$/i.test(String(process.env.ECC_HOOK_INPUT_TRUNCATED || ''));
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  if (raw.length < MAX_STDIN) {
    const remaining = MAX_STDIN - raw.length;
    raw += chunk.substring(0, remaining);
    if (chunk.length > remaining) truncated = true;
  } else {
    truncated = true;
  }
});

process.stdin.on('end', () => {
  const result = run(raw, {
    truncated,
    maxStdin: Number(process.env.ECC_HOOK_INPUT_MAX_BYTES) || MAX_STDIN,
  });

  if (result.stderr) {
    process.stderr.write(result.stderr + '\n');
  }

  if (result.exitCode === 2) {
    process.exit(2);
  }

  process.stdout.write(raw);
});
