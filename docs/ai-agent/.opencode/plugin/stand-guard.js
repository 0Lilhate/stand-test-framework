// The kit's enforcement layer under opencode: the same guard, wired to this host's events.
//
// Both bundle copies have always carried the same `hooks/stand-guard.mjs`. What did not travel was
// the BINDING: under Claude Code the host calls the guard on `PreToolUse` and friends because
// `settings.json` says so, and opencode has no `settings.json`. The result was a gate that depended
// on which host a run happened to use — that is, a gate that the choice of host could switch off,
// which is the one thing a gate must not be. This file closes that (task UITG-S022, SEC-06).
//
// The contract below is not remembered, it was read off the published plugin SDK (@opencode-ai/plugin
// 1.18.15, matching the installed binary) and the SDK's own generated types:
//
//   * plugins are auto-discovered from `.opencode/plugin/*.{js,ts}` — no config entry needed;
//   * a plugin module exports a FUNCTION `(input, options?) => Promise<Hooks>`, never an object;
//   * `tool.execute.before(input, output)` receives `{tool, sessionID, callID}` and `{args}`, and the
//     host awaits it BEFORE running the tool — so throwing here is what refuses the call;
//   * `tool.execute.after(input, output)` receives the same plus `args`, so the command that was run
//     is available after the fact without stashing it;
//   * opencode's write-shaped tools are `write` (`filePath`, `content`) and `edit` (`filePath`,
//     `oldString`, `newString`, `replaceAll`); its shell tool is `bash` (`command`);
//   * `event({event})` sees every server event, `session.idle` among them, and returns `Promise<void>`;
//   * `PluginInput` carries `client`, the full server SDK — including `session.get` (whose `parentID`
//     says whether a session is a subagent's) and `session.promptAsync` (which starts a new turn).
//
// ## The session-end gate, and why it is a RE-ENTRY rather than a hold
//
// Claude Code's `Stop` hook may exit 2, and the session then does not end: the model keeps working in
// the same turn. opencode's `event` returns void, so nothing thrown or returned here can stop a
// session from going idle. For a long time that was read as "the gate cannot exist on this host", and
// the bundle said so — but the conclusion did not follow from the premise. The gate does not need to
// PREVENT idleness; it needs the unreviewed artifact to be dealt with. `client.session.promptAsync`
// posts a new user turn into the session that just went idle, carrying the guard's refusal verbatim,
// which reaches the same end by the other side: the run does not finish with the artifact unreviewed.
//
// Two honest differences remain, and neither is smoothed over:
//
//   * **A headless one-shot (`opencode run …`) may exit before the re-entry is processed.** There the
//     gate degrades to its report on stderr. Under the TUI, where the process outlives the turn, the
//     re-entry lands.
//   * **The session is genuinely idle for the moment in between**, so a user typing in that instant
//     races the gate. Claude Code's hold has no such window.
//
// The loop-breaker is the guard's own: `stop_hook_active`. The first idle passes `false` and the guard
// may refuse; the second passes `true` and the guard reports `NOT-READY` and lets the session end. So
// one refusal buys exactly one re-entry — the same bound Claude Code applies, for the same reason: a
// gate that can never be satisfied is one people switch off, and it takes the working checks with it.
//
// ## `subagent-stop`, which used to be the other missing half
//
// opencode runs a subagent as a CHILD SESSION, so its completion is a `session.idle` whose session has
// a `parentID`. That is exactly the fact `subagent-stop` records, and recording it is not a nicety:
// `record-gate --gate safety-review --verdict PASS` refuses unless a subagent finished after the
// artifact was written, so without this binding the safety gate was not merely weaker on this host —
// it was UNSATISFIABLE, and the pipeline could not be completed at all.
//
// Which way the classification fails matters, so it is chosen rather than defaulted: when the session
// cannot be classified, no `subagent-stop` is recorded. A missing record costs a re-run of the review;
// a fabricated one forges the only evidence the safety gate has.

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

/** The one event that means "a turn just ended" — of the main session or of a subagent's. */
const SESSION_IDLE = "session.idle";

/**
 * Sessions whose end the gate already refused once, awaiting the second pass.
 *
 * Module scope is the right scope: one plugin instance serves one server, and the flag must outlive
 * the event that set it. Keyed by session so two sessions cannot clear each other's.
 */
const reEntered = new Set();

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
  return answer;
}

/**
 * Whether the idle session is a subagent's (`child`), the one the user drives (`main`), or unknown.
 *
 * `unknown` is a real answer and not an error to swallow: the two branches below do different things,
 * and guessing which one applies is how a `subagent-stop` gets recorded for a session that was never
 * a subagent.
 */
async function sessionKind(client, sessionID) {
  try {
    const answer = await client.session.get({ path: { id: sessionID } });
    if (!answer || answer.error || !answer.data) return "unknown";
    return answer.data.parentID ? "child" : "main";
  } catch {
    return "unknown";
  }
}

/** The message the gate posts back into the session — the guard's own words, attributed. */
function reEntryText(verdict) {
  return "⟦stand-guard⟧ Сессию завершить нельзя: гейт завершения отказал.\n\n"
    + `${verdict}\n\n`
    + "Это сообщение отправил плагин кита по событию session.idle, а не человек. Закройте перечисленное "
    + "и не объявляйте работу законченной до того, как гейт пройдёт. Если пройти он не может, скажите об "
    + "этом прямо — следующий отказ будет доложен и сессию уже не удержит.";
}

/**
 * Starts a new turn in the session that just went idle.
 *
 * `promptAsync` rather than `prompt`: the latter resolves when the model has answered, and awaiting a
 * whole turn from inside the event that ended the previous one is a deadlock waiting to be found.
 */
async function reEnter(client, sessionID, verdict) {
  try {
    const answer = await client.session.promptAsync({
      path: { id: sessionID },
      body: { parts: [{ type: "text", text: reEntryText(verdict) }] },
    });
    if (answer && answer.error) {
      throw new Error(typeof answer.error === "string" ? answer.error : JSON.stringify(answer.error));
    }
    return true;
  } catch (error) {
    process.stderr.write(
      `stand-guard: ГЕЙТ ЗАВЕРШЕНИЯ НЕ УДЕРЖАЛ СЕССИЮ: ${error.message}\n`
        + "  Отказ состоялся, вернуть работу в сессию не удалось — вердикт выше, и он не выполнен.\n",
    );
    return false;
  }
}

export default async ({ directory, client }) => {
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

    event: async ({ event }) => {
      if (!event || event.type !== SESSION_IDLE) return;
      const sessionID = (event.properties || {}).sessionID;
      if (!sessionID) return;

      const kind = await sessionKind(client, sessionID);
      if (kind === "child") {
        // A subagent finished. This is the whole of the evidence the safety gate has that the review
        // ran in a context other than the one that wrote the code.
        report("subagent-stop", { cwd, hook_event_name: "SubagentStop", session_id: sessionID });
        return;
      }

      const secondPass = reEntered.has(sessionID);
      const answer = runGuard("stop", {
        cwd,
        hook_event_name: "Stop",
        session_id: sessionID,
        stop_hook_active: secondPass,
      });
      const said = `${answer.stdout}${answer.stderr}`.trim();
      if (said) process.stderr.write(`${said}\n`);
      if (secondPass) reEntered.delete(sessionID);
      if (answer.status !== REFUSED) return;

      if (kind === "unknown") {
        // Refused, but it is not known whose session this is. Posting into a subagent's session would
        // answer the wrong context, so the gate reports and stops there — and says which half it lost.
        process.stderr.write(
          "stand-guard: ГЕЙТ ЗАВЕРШЕНИЯ ТОЛЬКО ДОЛОЖИЛ: сессию не удалось опознать (session.get не ответил).\n"
            + "  Отказ выше в силе; вернуть работу в сессию плагин не стал, чтобы не написать в чужую.\n",
        );
        return;
      }

      reEntered.add(sessionID);
      await reEnter(client, sessionID, said);
    },
  };
};
