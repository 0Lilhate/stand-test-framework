// The kit's enforcement layer under opencode: the same guard, wired to this host's events.
//
// Both bundle copies have always carried the same `hooks/stand-guard.mjs`. What did not travel was
// the BINDING: under Claude Code the host calls the guard on `PreToolUse` and friends because
// `settings.json` says so, and opencode has no `settings.json`. The result was a gate that depended
// on which host a run happened to use — that is, a gate that the choice of host could switch off,
// which is the one thing a gate must not be. This file closes that (task UITG-S022, SEC-06).
//
// The contract below is not remembered, it was read off the installed binary (opencode 1.18.12):
//
//   * plugins are auto-discovered from `.opencode/plugin/*.{js,ts}` — no config entry needed;
//   * a plugin module exports a FUNCTION `(input, options?) => Promise<Hooks>`, never an object;
//   * `tool.execute.before(input, output)` receives `{tool, sessionID, callID}` and `{args}`, and the
//     host awaits it BEFORE running the tool — so throwing here is what refuses the call;
//   * `tool.execute.after(input, output)` receives the same plus `args`, so the command that was run
//     is available after the fact without stashing it;
//   * opencode's write-shaped tools are `write` (`filePath`, `content`) and `edit` (`filePath`,
//     `oldString`, `newString`, `replaceAll`); its shell tool is `bash` (`command`).
//
// What is deliberately NOT wired, so that nobody reads this file as full parity:
//
//   * the SESSION-END gate. Claude Code's `Stop` can hold a session open because a hook may exit 2;
//     opencode's `event` hook returns void, and a gate that cannot refuse is a report. Under opencode
//     the unreviewed-artifact gate therefore does not run at all — it is not silently downgraded.
//   * `subagent-stop`. opencode declares no subagents (the bundle says so), so the evidence that
//     stage 8 ran in a separate context does not exist on this host. That is `out_of_scope` of the
//     task that added this file, and it is why the safety-review gate proves less here.

import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/** The guard travels beside this file, in the same bundle: `.opencode/hooks/stand-guard.mjs`. */
const GUARD = join(dirname(fileURLToPath(import.meta.url)), "..", "hooks", "stand-guard.mjs");

/**
 * opencode's write-shaped tools, and the Claude Code tool name each is equivalent to.
 *
 * The guard reconstructs the FILE a call would leave behind and judges that, so it needs to know
 * which shape the arguments are in. `write` carries a whole file; `edit` carries a fragment.
 */
const WRITE_TOOLS = { write: "Write", edit: "Edit" };

/** Exit code 2 is the guard's refusal; everything else it means as "proceed". */
const REFUSED = 2;

function toolInput(tool, args) {
  const source = args || {};
  if (tool === "write") {
    return { file_path: source.filePath || "", content: source.content || "" };
  }
  return {
    file_path: source.filePath || "",
    old_string: typeof source.oldString === "string" ? source.oldString : "",
    new_string: typeof source.newString === "string" ? source.newString : "",
    // Carried through rather than dropped: the guard replays the edit to rebuild the file, and a
    // dropped `replaceAll` would make it judge a file the call was never going to produce.
    replace_all: source.replaceAll === true,
  };
}

/**
 * Runs one guard subcommand with a host-shaped payload on stdin.
 *
 * `node` by name rather than `process.execPath`: under opencode the running executable is the Bun
 * single-file binary, and handing it an `.mjs` written for Node is not the same thing.
 *
 * A guard that cannot start does NOT block the session — the same rule the guard applies to its own
 * bugs, for the same reason: a broken check must not become an unworkable repository. It does say so
 * loudly, because a perimeter that is silently absent is worse than one that is loudly absent.
 */
function runGuard(subcommand, payload) {
  const result = spawnSync("node", [GUARD, subcommand], {
    input: JSON.stringify(payload),
    encoding: "utf8",
  });
  if (result.error) {
    process.stderr.write(
      `stand-guard: ПРОВЕРКА НЕ ВЫПОЛНЕНА (${subcommand}): ${result.error.message}\n`
        + "  Периметр кита сейчас не работает: нужен node в PATH и файл .opencode/hooks/stand-guard.mjs.\n",
    );
    return { status: 0, stderr: "", stdout: "" };
  }
  return { status: result.status === null ? 0 : result.status, stderr: result.stderr || "", stdout: result.stdout || "" };
}

/** Refuses the tool call by throwing: the host awaits this hook, so the tool never runs. */
function refuseIfBlocked(subcommand, payload) {
  const answer = runGuard(subcommand, payload);
  if (answer.status === REFUSED) {
    throw new Error(answer.stderr.trim() || `stand-guard: вызов отклонён (${subcommand})`);
  }
  if (answer.stdout.trim()) {
    process.stderr.write(`${answer.stdout.trim()}\n`);
  }
}

/** Reporting subcommands: what they print is the run journal, and it must not stop the session. */
function report(subcommand, payload) {
  const answer = runGuard(subcommand, payload);
  const said = `${answer.stdout}${answer.stderr}`.trim();
  if (said) {
    process.stderr.write(`${said}\n`);
  }
}

export default async ({ directory }) => {
  const cwd = directory || process.cwd();

  return {
    "tool.execute.before": async (input, output) => {
      const claudeName = WRITE_TOOLS[input.tool];
      if (claudeName) {
        refuseIfBlocked("pre-write", { cwd, tool_name: claudeName, tool_input: toolInput(input.tool, output.args) });
        return;
      }
      if (input.tool === "bash") {
        refuseIfBlocked("pre-bash", { cwd, tool_name: "Bash", tool_input: { command: (output.args || {}).command || "" } });
      }
    },

    "tool.execute.after": async (input) => {
      const claudeName = WRITE_TOOLS[input.tool];
      if (claudeName) {
        report("post-write", { cwd, tool_name: claudeName, tool_input: toolInput(input.tool, input.args) });
        return;
      }
      if (input.tool === "bash") {
        report("post-run", { cwd, tool_name: "Bash", tool_input: { command: (input.args || {}).command || "" } });
      }
    },
  };
};
