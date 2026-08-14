---
description: Multi-agent orchestration via TeamCreate. Spawns a coordinated team of agents that share a task list and message each other.
---

# Orchestrate Command

Coordinate multiple agents on a complex task using **Claude Code Agent Teams** (`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`).

A team has a 1:1 task list. The orchestrator (you, the main session) creates the team, spawns teammates with assigned roles, dispatches work via tasks, and cleans up when done.

## Usage

`/orchestrate [workflow-type] [task-description]`

## Required Env

`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` must be set in `.claude/settings.local.json` env block. (Already set in this project.)

## Workflow Types

Each workflow defines a team composition and the initial task graph. The orchestrator runs the lifecycle.

### feature
Full feature implementation:
- Team members: `planner`, `java-reviewer` (or `kotlin-reviewer`), `architect` (optional)
- Initial tasks: design → implement → review

### bugfix
Investigate and fix a bug:
- Team members: `planner`, `java-build-resolver` (or `kotlin-build-resolver`), `java-reviewer` (or `kotlin-reviewer`)
- Initial tasks: reproduce → diagnose → fix → review

### refactor
Safe refactor across multiple files:
- Team members: `architect`, `planner`, `java-reviewer` (or `kotlin-reviewer`)
- Initial tasks: assess → plan → implement → review

### security
Security audit and remediation:
- Team members: `java-reviewer` (or `kotlin-reviewer`), `architect`
- Initial tasks: scan → triage CRITICAL → propose fixes → review

### custom
`/orchestrate custom <agent1>,<agent2>,... <description>` — caller specifies the team composition.

## Lifecycle

The orchestrator (main session) executes these steps in order:

### 1. Create the team

```
TeamCreate({
  team_name: "<workflow>-<short-id>",
  description: "<task-description>",
  agent_type: "team-lead"
})
```

This creates `~/.claude/teams/<team-name>/config.json` and `~/.claude/tasks/<team-name>/`.

### 2. Seed the task list

For each phase of the workflow, create a task:

```
TaskCreate({
  subject: "Plan the <feature> rollout",
  description: "...",
  activeForm: "Planning <feature>"
})
TaskCreate({ subject: "Implement <feature>", description: "...", activeForm: "Implementing" })
TaskCreate({ subject: "Review changes", description: "...", activeForm: "Reviewing" })
```

Optionally use `TaskUpdate({addBlockedBy: [...]})` to express dependencies (e.g. review blocked by implement).

### 3. Spawn teammates

Spawn each teammate with `team_name` so they join the team and inherit the task list. Use the project's custom agents from `.claude/agents/`:

```
Agent({
  name: "planner-1",
  subagent_type: "planner",
  team_name: "<team-name>",
  prompt: "You are the planner on team <team-name>. Read TaskList, claim 'Plan the <feature> rollout' (set owner: 'planner-1'), produce a phased plan, then mark the task complete and notify team-lead.",
  run_in_background: true
})

Agent({
  name: "implementer-1",
  subagent_type: "general-purpose",
  team_name: "<team-name>",
  prompt: "You are the implementer on team <team-name>. Wait for planner-1 to complete the planning task, then claim 'Implement <feature>'.",
  run_in_background: true
})

Agent({
  name: "reviewer-1",
  subagent_type: "java-reviewer",
  team_name: "<team-name>",
  prompt: "You are the reviewer on team <team-name>. Pick up 'Review changes' once it is unblocked.",
  run_in_background: true
})
```

Notes:
- Always use `name` so teammates are addressable via SendMessage.
- For read-only roles (planning, design discussion) prefer the `Plan` or `Explore` built-in agent types instead of `general-purpose`.
- Use `run_in_background: true` so multiple teammates run in parallel; the main session stays responsive.

### 4. Coordinate via TaskList + SendMessage

The orchestrator does **not** poll. Teammates send you messages automatically when they:
- complete a task
- need clarification
- raise a CRITICAL finding (reviewers)

To push direction:
```
SendMessage({
  to: "implementer-1",
  summary: "skip caching for now",
  message: "Drop the Redis caching layer from this iteration; we'll add it in v2."
})
```

To re-assign a task:
```
TaskUpdate({ taskId: "3", owner: "reviewer-2" })
```

### 5. Handle CRITICAL findings inline

There is **no `security-reviewer` agent in this project**. Reviewers (`java-reviewer`, `kotlin-reviewer`) raise CRITICAL findings inside their normal output. When you see one:
1. Create a new task `Fix CRITICAL: <issue>`.
2. Assign it to the implementer or spawn a fresh `java-build-resolver` / `kotlin-build-resolver`.
3. Block the merge task on its completion.

### 6. Shutdown

When the workflow is complete:

```
SendMessage({to: "planner-1",     message: {type: "shutdown_request"}})
SendMessage({to: "implementer-1", message: {type: "shutdown_request"}})
SendMessage({to: "reviewer-1",    message: {type: "shutdown_request"}})
```

After all teammates have shut down:
```
TeamDelete()
```

## Final Report Format

After `TeamDelete`, print:

```
ORCHESTRATION REPORT
====================
Team: <team-name>
Workflow: <workflow>
Task: <description>
Members: planner-1, implementer-1, reviewer-1

TASKS
-----
- [done] Plan the <feature> rollout (planner-1)
- [done] Implement <feature> (implementer-1)
- [done] Review changes (reviewer-1)
- [done] Fix CRITICAL: <...> (implementer-1)  ← if any

KEY OUTPUTS
-----------
planner-1:    <one-line summary>
implementer-1:<one-line summary>
reviewer-1:   <one-line summary>

FILES CHANGED
-------------
<list>

BUILD / TEST
------------
./gradlew check — <status>

SECURITY
--------
<CRITICAL findings raised, all resolved | open>

RECOMMENDATION
--------------
SHIP / NEEDS WORK / BLOCKED
```

## Parallel vs Sequential

- **Parallel** is the default — spawn all teammates at once with `run_in_background: true`, let them claim tasks from the shared list. Use `addBlockedBy` to enforce ordering at the task level.
- **Sequential** — only spawn the next teammate after the previous task is `completed`. Use this when later phases depend on artifacts only known after the earlier phase finishes.

## Idempotency / Reuse

If you re-run `/orchestrate` for the same workflow on the same branch, generate a new `<team-name>` (suffix with timestamp) — `TeamCreate` will fail on a name collision, and the old team's task list will not be merged.

## Tips

1. **Always seed the task list before spawning teammates.** They check `TaskList` on startup and claim work in ID order.
2. **Use `name` consistently** (e.g. `planner-1`, `reviewer-1`) — that's the only handle SendMessage accepts.
3. **CRITICAL findings raised by reviewers** go through a new task, not a separate agent.
4. **Don't poll teammates with terminal tools.** SendMessage is the only legitimate channel; idle notifications come automatically.
5. **`TeamDelete` fails if any teammate is still alive.** Send `shutdown_request` to each, wait for `shutdown_response: approve=true`, then delete.

## Available Agents (this project)

From `.claude/agents/`:
- `planner` — implementation plans
- `architect` — system design / architectural trade-offs
- `java-reviewer`, `kotlin-reviewer` — code review (incl. raising CRITICAL security findings inline)
- `java-build-resolver`, `kotlin-build-resolver` — fix build errors
- `database-reviewer` — SQL / schema review
- `doc-updater` — codemap and README updates
- `docs-lookup` — Context7-backed library lookups
- `bash-expert` — shell scripts
- `harness-optimizer` — `.claude/` config tuning

Plus built-in subagent_types: `general-purpose`, `Plan`, `Explore`, `Agent`, `architect`, etc. — pick the read-only ones for research roles.

## Custom Workflow Example

```
/orchestrate custom "architect,java-reviewer" "Redesign caching layer"
```

The orchestrator creates a team with one architect and one reviewer, seeds tasks "Design new cache topology" → "Review proposed design", and runs the lifecycle above.
