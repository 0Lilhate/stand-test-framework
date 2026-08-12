/**
 * Minimal cross-platform helpers for Claude Code hooks.
 *
 * Trimmed to the only three functions consumed by the wired hooks
 * (`scripts/hooks/cost-tracker.js`):
 *   - getClaudeDir()
 *   - ensureDir(path)
 *   - appendFile(path, content)
 *
 * Works on Windows, macOS, and Linux.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');

function getClaudeDir() {
  return path.join(os.homedir(), '.opencode');
}

function ensureDir(dirPath) {
  try {
    if (!fs.existsSync(dirPath)) {
      fs.mkdirSync(dirPath, { recursive: true });
    }
  } catch (err) {
    if (err.code !== 'EEXIST') {
      throw new Error(`Failed to create directory '${dirPath}': ${err.message}`);
    }
  }
  return dirPath;
}

function appendFile(filePath, content) {
  ensureDir(path.dirname(filePath));
  fs.appendFileSync(filePath, content, 'utf8');
}

module.exports = {
  getClaudeDir,
  ensureDir,
  appendFile,
};
