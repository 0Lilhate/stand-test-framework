---
name: requesting-code-review
description: When to dispatch a code-review subagent at task boundaries — after each task, after a feature, before merge. Wires through the project's java-reviewer / kotlin-reviewer agents.
---

# Requesting Code Review

Dispatch a review subagent (`java-reviewer` or `kotlin-reviewer`) at natural task boundaries to catch issues before they cascade.

**Core principle:** review early, review often.

> This skill is about **when and how** to request review. The actual review checklists live inside the agents (`.opencode/agents/java-reviewer.md`, `.opencode/agents/kotlin-reviewer.md`). Don't duplicate them here.

## When to Request Review

**Mandatory:**
- After completing a task in subagent-driven development
- After completing a major feature
- Before merge to main / master / production branch
- After any change touching auth, payments, or PII

**Optional but valuable:**
- When stuck — fresh perspective from a structured review
- Before refactoring — establish a baseline
- After fixing a complex bug — verify the fix doesn't introduce regressions

## Choose the Reviewer

| Files changed | Agent |
|---|---|
| `*.java` | `java-reviewer` |
| `*.kt`, `*.kts` | `kotlin-reviewer` |
| Both | dispatch both **in parallel** (single message, two Agent calls) |
| `*.sql`, JPA entities, schema | also dispatch `database-reviewer` |

Don't use a generic / foreign agent — these specialised agents know the project's rules.

## How to Dispatch

Use the `Agent` (or `Task`) tool with `subagent_type` set to `java-reviewer` or `kotlin-reviewer`. The agent reads its own checklist; your job is to give it the diff range and context.

### Single-language change

```
Agent({
  subagent_type: "java-reviewer",
  description: "Review feature X",
  prompt: `
Review the changes between BASE_SHA..HEAD_SHA.

What was implemented:
{one-paragraph summary}

Requirements:
{plan or ticket reference, if any}

Files changed (focus area):
{paste output of: git diff --name-only BASE_SHA..HEAD_SHA}

Produce CRITICAL / HIGH / MEDIUM / LOW findings per your checklist.
Stop and raise the top finding if you spot a CRITICAL security issue.
`
})
```

### Mixed Java + Kotlin change (parallel)

Send a single message with two Agent calls — they run independently and faster than sequential:

```
[Agent #1: java-reviewer  → review only the .java files in the diff]
[Agent #2: kotlin-reviewer → review only the .kt files in the diff]
```

Then merge their outputs into one report.

### Diff-touching SQL or JPA

Add `database-reviewer` as a third parallel call.

## Getting the Diff Range

```bash
BASE_SHA=$(git merge-base HEAD origin/main)
HEAD_SHA=$(git rev-parse HEAD)
git diff --stat $BASE_SHA..$HEAD_SHA
```

Pass these SHAs to the reviewer in the prompt — never paste the full diff (the agent runs `git diff` itself).

## Acting on Feedback

Match severity → action:

| Severity | Action |
|---|---|
| **CRITICAL** | Fix immediately, before any other work |
| **HIGH** | Fix before proceeding to the next task / before merge |
| **MEDIUM** | Track in a follow-up task; fix in a separate change if scope allows |
| **LOW** | Optional. Log as nit, fix if trivial |

If the reviewer is wrong, push back **with technical reasoning**: cite code, tests, or invariants. Don't argue style.

## Example Flow

```
[Just completed Task 2: add OrderService.cancel()]

Step 1 — Get diff range:
BASE_SHA=$(git merge-base HEAD origin/main)   # e.g. a7981ec
HEAD_SHA=$(git rev-parse HEAD)                # e.g. 3df7661

Step 2 — Dispatch java-reviewer:
Agent({
  subagent_type: "java-reviewer",
  description: "Review OrderService.cancel()",
  prompt: `
Review changes between a7981ec..3df7661.

Implemented: OrderService.cancel(orderId) — sets status to CANCELLED,
emits OrderCancelledEvent, refunds payment via PaymentService.

Files changed:
  src/main/java/.../OrderService.java
  src/main/java/.../events/OrderCancelledEvent.java
  src/test/java/.../OrderServiceTest.java

Apply your full checklist (architecture, JPA, security, concurrency).
`
})

Step 3 — Reviewer returns:
  CRITICAL: none
  HIGH: refund call not idempotent — second cancel triggers double refund
  MEDIUM: missing @Transactional(readOnly = false) ambiguity
  Verdict: BLOCK until HIGH is fixed

Step 4 — Fix idempotency, re-run review on the fix.
Step 5 — Continue to Task 3.
```

## Integration With Project Workflows

- **/orchestrate `feature` workflow:** the chain already includes `java-reviewer` / `kotlin-reviewer` as the closing step. This skill describes when to invoke a review **outside** that orchestration, e.g. mid-task.
- **/code-review slash command:** runs a comprehensive review of uncommitted changes (uses the same agents under the hood). Use it when you don't have a clean SHA range.
- **TDD loop (`springboot-tdd` / `/kotlin-test`):** review **after** the GREEN+REFACTOR phase, not during RED, to avoid premature criticism.

## Red Flags

**Never:**
- Skip review because "it's simple" — simple changes ship CRITICAL bugs all the time
- Mark nitpicks as CRITICAL — devalues the severity scale
- Argue with valid technical feedback without code/test evidence
- Proceed to the next task with unfixed HIGH issues

## Related

- Agents: `agents/java-reviewer.md`, `agents/kotlin-reviewer.md`, `agents/database-reviewer.md`
- Commands: `/code-review`, `/orchestrate`, `/kotlin-review`
- Skills: `code-quality` (Java clean-code patterns), `springboot-verification` (full verify loop)
