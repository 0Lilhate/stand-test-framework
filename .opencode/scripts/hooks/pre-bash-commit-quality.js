#!/usr/bin/env node
/**
 * PreToolUse Hook: Pre-commit Quality Check (Java / Kotlin / Spring Boot)
 *
 * Runs lightweight pattern-level checks before `git commit`:
 *   - Detects staged .java / .kt / .kts files
 *   - Flags Java/Kotlin anti-patterns (debug-print, swallowed exceptions, ignored tests, ...)
 *   - Flags hardcoded credentials (JDBC password URLs, generic API keys)
 *   - Validates commit message format (Conventional Commits)
 *
 * Heavy linting (Checkstyle, detekt, ktlint, ./gradlew check) is intentionally
 * NOT run here — it's slow (minutes) and belongs in CI / IDE. This hook stays
 * fast (sub-second) so commits don't drag.
 *
 * Cross-platform (Windows, macOS, Linux).
 *
 * Exit codes:
 *   0 - Allow commit
 *   2 - Block commit (errors found; warnings alone don't block)
 *
 * Bypass:
 *   git commit --no-verify        (skips all hooks)
 *   ECC_DISABLED_HOOKS=pre:bash:commit-quality
 */

'use strict';

const { spawnSync } = require('child_process');

const MAX_STDIN = 1024 * 1024; // 1MB

// ── git helpers ─────────────────────────────────────────────────────

function getStagedFiles() {
  const result = spawnSync('git', ['diff', '--cached', '--name-only', '--diff-filter=ACMR'], {
    encoding: 'utf8',
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  if (result.status !== 0) return [];
  return result.stdout.trim().split('\n').filter(f => f.length > 0);
}

function getStagedFileContent(filePath) {
  const result = spawnSync('git', ['show', `:${filePath}`], {
    encoding: 'utf8',
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  if (result.status !== 0) return null;
  return result.stdout;
}

function shouldCheckFile(filePath) {
  return /\.(java|kt|kts)$/.test(filePath);
}

// ── pattern definitions ─────────────────────────────────────────────

/**
 * Each pattern: { regex, type, severity, message }
 * - severity: 'error' (blocks commit), 'warning' (informs), 'info'
 * - regex MUST anchor to source code only, not comments — we filter comment
 *   lines below to reduce false positives.
 */
const SOURCE_PATTERNS = [
  // ── Debug-print (warning) ──
  {
    regex: /\bSystem\.(out|err)\.(println|print|printf)\s*\(/,
    type: 'debug-print',
    severity: 'warning',
    label: 'System.out/err.print* — use SLF4J logger instead',
  },
  {
    // Bare println(...) at start of expression — Kotlin top-level print
    // Excludes obj.println(...) which usually prints to a writer.
    regex: /(^|[^.\w])println\s*\(/,
    type: 'debug-print',
    severity: 'warning',
    label: 'println(...) — use SLF4J logger instead',
    extensions: ['.kt', '.kts'],
  },
  {
    regex: /\bLog\.(d|v|i|w|e)\s*\(/,
    type: 'android-log',
    severity: 'warning',
    label: 'android.util.Log call — prefer SLF4J / Timber via DI',
  },

  // ── Anti-patterns (error) ──
  {
    regex: /\.printStackTrace\s*\(\s*\)/,
    type: 'print-stack-trace',
    severity: 'error',
    label: 'printStackTrace() — log via SLF4J: log.error("...", e)',
  },
  {
    // catch (X x) { } — empty catch, allows whitespace and same-line.
    regex: /catch\s*\([^)]*\)\s*\{\s*\}/,
    type: 'empty-catch',
    severity: 'error',
    label: 'empty catch block — at minimum log the exception',
  },

  // ── Disabled tests (warning) ──
  {
    // @Disabled or @Ignore on its own (no message arg => no documented reason)
    regex: /^\s*@(Disabled|Ignore|Ignored)\s*(\(\s*\))?\s*$/,
    type: 'disabled-test',
    severity: 'warning',
    label: '@Disabled / @Ignore without reason — pass a string explaining why',
  },

  // ── Suppressing inspections globally (warning) ──
  {
    regex: /@SuppressWarnings\s*\(\s*"all"\s*\)/,
    type: 'suppress-all',
    severity: 'warning',
    label: '@SuppressWarnings("all") — narrow to specific category',
  },
  {
    regex: /\/\/\s*noinspection\s+\w+/i,
    type: 'noinspection',
    severity: 'warning',
    label: '// noinspection — fix the underlying issue or narrow scope',
  },

  // ── Hardcoded credentials in code (error) ──
  {
    regex: /jdbc:[^"'\s]*[?&;:]password=[^"'\s&]+/i,
    type: 'jdbc-password',
    severity: 'error',
    label: 'JDBC URL with embedded password — use env var / Spring config',
  },
  {
    regex: /jdbc:[^"'\s]*:\/\/[^"'\s]*:[^@\s"']+@/i,
    type: 'jdbc-userpass',
    severity: 'error',
    label: 'JDBC URL with embedded user:password@ — externalise credentials',
  },

  // ── Generic secret patterns (error) ──
  {
    regex: /sk-[a-zA-Z0-9]{20,}/,
    type: 'secret-openai',
    severity: 'error',
    label: 'Possible OpenAI API key',
  },
  {
    regex: /ghp_[a-zA-Z0-9]{36}/,
    type: 'secret-github',
    severity: 'error',
    label: 'Possible GitHub PAT',
  },
  {
    regex: /AKIA[A-Z0-9]{16}/,
    type: 'secret-aws',
    severity: 'error',
    label: 'Possible AWS Access Key ID',
  },
  {
    // password = "..." or password: "..." — assignment with string literal
    // Allow @Value("${...}") or System.getenv(...) by checking the RHS.
    regex: /(?:\b|_)password\b\s*[=:]\s*"(?!\$\{|getenv\b)[^"$\s][^"\n]{3,}"/i,
    type: 'hardcoded-password',
    severity: 'error',
    label: 'Hardcoded password literal — use @Value("${...}") or env var',
  },
  {
    regex: /\bapi[_-]?key\b\s*[=:]\s*"(?!\$\{|getenv\b)[^"$\s][^"\n]+"/i,
    type: 'hardcoded-api-key',
    severity: 'error',
    label: 'Hardcoded API key literal — externalise to config',
  },

  // ── TODO/FIXME without ticket (info) ──
  {
    regex: /\/\/\s*(TODO|FIXME)\b\s*:?\s*(.*)/,
    type: 'todo-no-ticket',
    severity: 'info',
    label: 'TODO/FIXME without ticket reference',
    suppressIf: (m) => /(#\d+|JIRA|ALFA-|PROJ-|issue\s*\d+)/i.test(m[2] || ''),
  },
];

// ── per-file check ──────────────────────────────────────────────────

function isCommentLine(line) {
  const t = line.trim();
  return t.startsWith('//') || t.startsWith('*') || t.startsWith('/*');
}

function findFileIssues(filePath) {
  const issues = [];
  const content = getStagedFileContent(filePath);
  if (content == null) return issues;

  const ext = filePath.slice(filePath.lastIndexOf('.'));
  const lines = content.split('\n');

  lines.forEach((line, idx) => {
    const lineNum = idx + 1;

    for (const p of SOURCE_PATTERNS) {
      if (p.extensions && !p.extensions.includes(ext)) continue;

      // Comment-only patterns are allowed to match comment lines
      const isCommentMatch = p.type === 'todo-no-ticket' || p.type === 'noinspection';
      if (!isCommentMatch && isCommentLine(line)) continue;

      const m = line.match(p.regex);
      if (!m) continue;
      if (p.suppressIf && p.suppressIf(m)) continue;

      issues.push({
        type: p.type,
        message: p.label,
        line: lineNum,
        severity: p.severity,
        snippet: line.trim().slice(0, 120),
      });
    }
  });

  return issues;
}

// ── commit message validation ───────────────────────────────────────

function validateCommitMessage(command) {
  const messageMatch = command.match(/(?:-m|--message)[=\s]+["']?([^"']+)["']?/);
  if (!messageMatch) return null;

  const message = messageMatch[1];
  const issues = [];

  const conventionalCommit = /^(feat|fix|docs|style|refactor|test|chore|build|ci|perf|revert)(\(.+\))?:\s*.+/;
  if (!conventionalCommit.test(message)) {
    issues.push({
      type: 'format',
      message: 'Commit message does not follow Conventional Commits',
      suggestion: 'Use format: type(scope): description (e.g. "feat(auth): add login flow")',
    });
  }

  if (message.length > 72) {
    issues.push({
      type: 'length',
      message: `Commit message too long (${message.length} chars, max 72)`,
      suggestion: 'Keep the first line under 72 characters',
    });
  }

  if (conventionalCommit.test(message)) {
    const afterColon = message.split(':')[1];
    if (afterColon && /^[A-Z]/.test(afterColon.trim())) {
      issues.push({
        type: 'capitalization',
        message: 'Subject should start with lowercase after type',
        suggestion: 'Lowercase the first letter of the subject',
      });
    }
  }

  if (message.endsWith('.')) {
    issues.push({
      type: 'punctuation',
      message: 'Commit message should not end with a period',
      suggestion: 'Remove the trailing period',
    });
  }

  return { message, issues };
}

// ── main flow ───────────────────────────────────────────────────────

function evaluate(rawInput) {
  try {
    const input = JSON.parse(rawInput);
    const command = input.tool_input?.command || '';

    if (!command.includes('git commit')) {
      return { output: rawInput, exitCode: 0 };
    }
    if (command.includes('--amend')) {
      return { output: rawInput, exitCode: 0 };
    }

    const stagedFiles = getStagedFiles();
    if (stagedFiles.length === 0) {
      console.error('[Hook] No staged files. Use "git add" first.');
      return { output: rawInput, exitCode: 0 };
    }

    console.error(`[Hook] Checking ${stagedFiles.length} staged file(s) for Java/Kotlin issues...`);

    const filesToCheck = stagedFiles.filter(shouldCheckFile);
    let errorCount = 0;
    let warningCount = 0;
    let infoCount = 0;
    let totalIssues = 0;

    for (const file of filesToCheck) {
      const fileIssues = findFileIssues(file);
      if (fileIssues.length === 0) continue;

      console.error(`\n📁 ${file}`);
      for (const issue of fileIssues) {
        const icon = issue.severity === 'error' ? '❌'
                   : issue.severity === 'warning' ? '⚠️ '
                   : 'ℹ️ ';
        console.error(`  ${icon} L${issue.line}: ${issue.message}`);
        if (issue.snippet) console.error(`       ${issue.snippet}`);
        totalIssues++;
        if (issue.severity === 'error') errorCount++;
        else if (issue.severity === 'warning') warningCount++;
        else infoCount++;
      }
    }

    const messageValidation = validateCommitMessage(command);
    if (messageValidation && messageValidation.issues.length > 0) {
      console.error('\n📝 Commit Message Issues:');
      for (const issue of messageValidation.issues) {
        console.error(`  ⚠️  ${issue.message}`);
        if (issue.suggestion) console.error(`      💡 ${issue.suggestion}`);
        totalIssues++;
        warningCount++;
      }
    }

    if (totalIssues === 0) {
      console.error('\n[Hook] ✅ All quality checks passed.');
      return { output: rawInput, exitCode: 0 };
    }

    console.error(
      `\n📊 Summary: ${totalIssues} issue(s) — ${errorCount} error, ${warningCount} warning, ${infoCount} info`
    );

    if (errorCount > 0) {
      console.error('\n[Hook] ❌ Commit blocked: critical issues must be fixed.');
      console.error('[Hook]    Bypass (NOT recommended): git commit --no-verify');
      return { output: rawInput, exitCode: 2 };
    }

    console.error('\n[Hook] ⚠️  Warnings only — commit allowed. Consider addressing them.');
  } catch (error) {
    console.error(`[Hook] pre-bash:commit-quality error: ${error.message}`);
    // Non-blocking on internal error
  }

  return { output: rawInput, exitCode: 0 };
}

function run(rawInput) {
  return evaluate(rawInput).output;
}

// ── stdin entry point ───────────────────────────────────────────────
if (require.main === module) {
  let data = '';
  process.stdin.setEncoding('utf8');

  process.stdin.on('data', chunk => {
    if (data.length < MAX_STDIN) {
      const remaining = MAX_STDIN - data.length;
      data += chunk.substring(0, remaining);
    }
  });

  process.stdin.on('end', () => {
    const result = evaluate(data);
    process.stdout.write(result.output);
    process.exit(result.exitCode);
  });
}

module.exports = { run, evaluate };
