/**
 * Minimal cross-platform helpers for Claude Code hooks.
 * Trimmed to the only functions consumed by the wired hooks.
 */

/** Get the Claude config directory (~/.claude) */
export function getClaudeDir(): string;

/**
 * Ensure a directory exists, creating it recursively if needed.
 * Handles EEXIST race conditions from concurrent creation.
 * @throws If directory cannot be created (e.g., permission denied)
 */
export function ensureDir(dirPath: string): string;

/** Append to a text file, creating parent directories if needed */
export function appendFile(filePath: string, content: string): void;
